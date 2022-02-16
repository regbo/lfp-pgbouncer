package com.lfp.pgbouncer_app.cert;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.threadly.concurrent.future.FutureUtils;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.net.MediaType;
import com.lfp.connect.undertow.UndertowUtils;
import com.lfp.connect.undertow.retrofit.RetrofitHandler;
import com.lfp.joe.certigo.service.CertificateInfo;
import com.lfp.joe.core.config.MachineConfig;
import com.lfp.joe.core.function.Nada;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.core.process.CentralExecutor;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.net.http.ServiceConfig;
import com.lfp.joe.net.http.uri.URIs;
import com.lfp.joe.threads.Threads;
import com.lfp.joe.utils.Utils;
import com.lfp.pgbouncer.service.CertificateChain;
import com.lfp.pgbouncer.service.PGBouncerService;
import com.lfp.pgbouncer.service.config.PGBouncerServiceConfig;
import com.lfp.pgbouncer_app.ENVService;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;

import at.favre.lib.bytes.Bytes;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import one.util.streamex.StreamEx;

public class PGBouncerServiceImpl extends RetrofitHandler<PGBouncerService>
		implements PGBouncerService, Scrapable.Delegating {

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final String PEM_FILE_EXTENSION = ".pem";
	private static final String PEM_FILE_CONTENT_TYPE = "application/x-pem-file";
	private static final String CERTIFICATE_CHAIN_PEM_PATH = Utils.Strings.stripEnd(CERTIFICATE_CHAIN_PATH, "/")
			+ PEM_FILE_EXTENSION;
	private static final Duration CERTIFICATE_CHAIN_CACHE_REFRESH_DURATION = Duration.ofSeconds(1);
	private static final boolean CERTIFICATE_CHAIN_CACHE_HASH_SORT_PEMS = true;
	private final Scrapable _delegateScrapable = Scrapable.create();
	private final LoadingCache<Nada, CertificateInfo> certificateInfoCache = Caffeine.newBuilder().maximumSize(1)
			.executor(CentralExecutor.INSTANCE).refreshAfterWrite(CERTIFICATE_CHAIN_CACHE_REFRESH_DURATION)
			.build(new CacheLoader<Nada, CertificateInfo>() {

				private Bytes previousCertificateChainHash;

				@Override
				public @Nullable CertificateInfo load(@NonNull Nada key) throws Exception {
					var certificateInfos = loadCertificateChains();
					var hashParts = Utils.Lots.stream(certificateInfos).map(CertificateChain::getPem);
					if (CERTIFICATE_CHAIN_CACHE_HASH_SORT_PEMS)
						hashParts = hashParts.sorted();
					var certificateInfoHash = Utils.Crypto.hashMD5(hashParts.toArray());
					if (!Objects.equals(previousCertificateChainHash, certificateInfoHash)) {
						onCertificateChainsUpdated(certificateInfoHash,
								Optional.ofNullable(previousCertificateChainHash), certificateInfos);
						previousCertificateChainHash = certificateInfoHash;
					}

					return certificateInfos;
				}

			});

	public PGBouncerServiceImpl() {
		super(PGBouncerService.class);
		var pemFilename = ServiceConfig.discover(this).map(ServiceConfig::uri).map(URIs::toAddress).nonNull()
				.map(v -> String.format("%s_%s", v.getHostString(), v.getPort())).findFirst().orElse("certificate")
				+ PEM_FILE_EXTENSION;
		this.addExactPath(CERTIFICATE_CHAIN_PEM_PATH, exchange -> {
			exchange.setStatusCode(StatusCodes.OK);
			String contentDispositionType;
			if (UndertowUtils.isHtmlAccepted(exchange)) {
				exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.toString());
				contentDispositionType = "inline";
			} else {
				exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, PEM_FILE_CONTENT_TYPE);
				contentDispositionType = "attachment";
			}
			exchange.getResponseHeaders().add(Headers.CONTENT_DISPOSITION,
					String.format("%s; filename=\"%s\"", contentDispositionType, pemFilename));
			exchange.startBlocking();
			var body = Optional.ofNullable(certificateInfo()).map(CertificateChain::getPem).orElse("");
			exchange.getResponseSender().send(body);
			return;
		});
		Runnable pollTask = () -> {
			try {
				certificateInfoCache.get(Nada.get());
			} catch (Throwable t) {
				if (!Utils.Exceptions.isCancelException(t))
					logger.warn("error during certificateInfoCache poll", t);
			}
		};
		Supplier<Boolean> loopTest = () -> !this.isScrapped();
		var pollFuture = FutureUtils.scheduleWhile(Threads.Pools.centralPool(),
				CERTIFICATE_CHAIN_CACHE_REFRESH_DURATION.toMillis(), true, pollTask, loopTest);
		Threads.Futures.onScrapCancel(this, pollFuture, true);
	}

	@Override
	public CertificateInfo certificateInfo() {
		return certificateInfoCache.get(Nada.get());
	}

	@Override
	public Scrapable _delegateScrapable() {
		return _delegateScrapable;
	}

	protected void onCertificateChainsUpdated(Bytes certificateInfoHash, Optional<Bytes> previousCertificateChainHashOp,
			CertificateInfo certificateInfos) {
		if (!Configs.get(PGBouncerAppConfig.class).logCertificateSummaries())
			return;
		logger.info("Chain Hash Modified {} -> {}",
				previousCertificateChainHashOp.map(Bytes::encodeBase64).orElse(null),
				certificateInfoHash.encodeBase64());
		for (int i = 0; i < certificateInfos.size(); i++) {
			var summary = CertSummary.get(certificateInfos.get(i).getCertificates().toArray(Certificate[]::new));
			logger.info("Chain Updated {} --------------------------\n{}", i, summary);
		}
	}

	protected CertificateInfo loadCertificateChains() throws IOException {
		return loadCertificateChainsCertInfo();
	}

	protected CertificateInfo loadCertificateChainsCertInfo() throws IOException {
		var pems = CertInfoService.get().execute(getCertificateAddress());
		return StreamEx.of(pems).map(v -> CertificateChain.builder().pem(v).build()).toImmutableList();
	}

	private static InetSocketAddress getCertificateAddress() {
		var uri = Configs.get(PGBouncerServiceConfig.class).uri();
		if (uri == null && MachineConfig.isDeveloper())
			uri = URI.create("https://google.com");
		return URIs.toAddress(uri);
	}

	public static void main(String[] args) throws Exception {
		ENVService.init();
		try (var impl = new PGBouncerServiceImpl();) {
			for (long i = 0;; i++) {
				if (i > 0)
					Thread.sleep(1000);
				System.out.println(impl.certificateInfo().getPem());
			}
		}
	}

}
