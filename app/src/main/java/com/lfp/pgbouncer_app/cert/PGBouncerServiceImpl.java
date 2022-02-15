package com.lfp.pgbouncer_app.cert;

import java.io.IOException;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.throwable.beanref.BeanRef;
import com.google.common.net.MediaType;
import com.lfp.connect.undertow.UndertowUtils;
import com.lfp.connect.undertow.retrofit.RetrofitHandler;
import com.lfp.joe.cache.Caches;
import com.lfp.joe.core.function.Nada;
import com.lfp.joe.net.http.ServiceConfig;
import com.lfp.joe.net.http.uri.URIs;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.crypto.CertificateParser;
import com.lfp.pgbouncer.service.PGBouncerService;
import com.lfp.pgbouncer_app.ENVService;

import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import one.util.streamex.StreamEx;

public class PGBouncerServiceImpl extends RetrofitHandler<PGBouncerService> implements PGBouncerService {

	private static final String PEM_FILE_EXTENSION = ".pem";
	private static final String PEM_FILE_CONTENT_TYPE = "application/x-pem-file";
	private static final String CERTIFICATE_CHAIN_PEM_PATH = Utils.Strings.stripEnd(CERTIFICATE_CHAIN_PATH, "/")
			+ PEM_FILE_EXTENSION;

	private final LoadingCache<Nada, List<String>> cache = Caches.newCaffeineBuilder(1, Duration.ofSeconds(1), null)
			.build(nil -> loadCertificateChains());
	private final CertStoreService certStoreService;

	public PGBouncerServiceImpl(CertStoreService certStoreService) {
		super(PGBouncerService.class);
		this.certStoreService = Objects.requireNonNull(certStoreService);
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
			var body = certificateChain().stream()
					.collect(Collectors.joining(Objects.toString(Utils.Strings.newLine())));
			exchange.getResponseSender().send(body);
			return;
		});
	}

	@Override
	public List<String> certificateChain() {
		return cache.get(Nada.get());
	}

	private List<String> loadCertificateChains() throws IOException {
		var certStore = certStoreService.streamCertStores().findFirst().orElse(null);
		if (certStore == null)
			return List.of();
		var certValue = certStore.getCert().getValue();
		return StreamEx.of(CertificateParser.parse(certValue)).map(Utils.Crypto::encodePEM).toImmutableList();
	}

	public static void main(String[] args) throws Exception {
		ENVService.init();
		URL destinationURL = ServiceConfig.discover(PGBouncerService.class).map(v -> v.uri()).findFirst().get().toURL();
		HttpsURLConnection conn = (HttpsURLConnection) destinationURL.openConnection();
		conn.connect();
		Certificate[] connCerts = conn.getServerCertificates();
		for (var connCert : connCerts) {
			System.out.println(Utils.Crypto.encodePEM(connCert));
		}
		conn.disconnect();
		var impl = new PGBouncerServiceImpl(CertStoreService.get());
		var certs = impl.certificateChain();
		var beanRef = BeanRef.$(ECPublicKey.class);
		var bps = beanRef.all();
		for (var bp : bps)
			System.out.println(bp.getPath());
	}

}
