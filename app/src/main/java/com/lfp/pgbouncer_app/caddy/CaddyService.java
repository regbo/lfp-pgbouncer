package com.lfp.pgbouncer_app.caddy;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.lfp.caddy.core.CaddyConfig;
import com.lfp.caddy.core.CaddyConfigs;
import com.lfp.caddy.core.config.Apps;
import com.lfp.caddy.core.config.Automation;
import com.lfp.caddy.core.config.Certificates;
import com.lfp.caddy.core.config.Challenges;
import com.lfp.caddy.core.config.ConnectionPolicy;
import com.lfp.caddy.core.config.Dns;
import com.lfp.caddy.core.config.DnsProvider;
import com.lfp.caddy.core.config.DynamicDns;
import com.lfp.caddy.core.config.HandleLayer4;
import com.lfp.caddy.core.config.IpSource;
import com.lfp.caddy.core.config.Issuer;
import com.lfp.caddy.core.config.Layer4;
import com.lfp.caddy.core.config.MatchLayer4;
import com.lfp.caddy.core.config.MatchTls;
import com.lfp.caddy.core.config.Policy;
import com.lfp.caddy.core.config.Provider;
import com.lfp.caddy.core.config.RouteLayer4;
import com.lfp.caddy.core.config.ServerLayer4;
import com.lfp.caddy.core.config.Storage;
import com.lfp.caddy.core.config.Tls;
import com.lfp.caddy.core.config.UpstreamLayer4;
import com.lfp.caddy.run.Caddy;
import com.lfp.joe.beans.JodaBeans;
import com.lfp.joe.core.config.MachineConfig;
import com.lfp.joe.core.function.MemoizedSupplier;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.net.dns.TLDs;
import com.lfp.joe.net.http.ip.IPs;
import com.lfp.joe.net.http.uri.URIs;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.Requires;
import com.lfp.joe.utils.process.CompletableStartedProcess;
import com.lfp.pgbouncer.service.config.PGBouncerServiceConfig;
import com.lfp.pgbouncer_app.ENVService;
import com.lfp.pgbouncer_app.config.CaddyExecConfig;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;
import com.lfp.pgbouncer_app.storage.RedisService;

import one.util.streamex.StreamEx;

public class CaddyService implements Supplier<CompletableStartedProcess> {
	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final List<String> APPEND_DOT_PROVIDERS = List.of("route53");

	private final InetSocketAddress serviceAddress;
	private final InetSocketAddress pgBouncerServiceAddress;
	private final RedisService redisService;
	private final MemoizedSupplier<CaddyConfig> caddyConfigSupplier;

	public CaddyService(InetSocketAddress serviceAddress, InetSocketAddress pgBouncerServiceAddress,
			RedisService redisService) {
		this.serviceAddress = Objects.requireNonNull(serviceAddress);
		if (pgBouncerServiceAddress == null && MachineConfig.isDeveloper())
			pgBouncerServiceAddress = InetSocketAddress.createUnresolved(IPs.getLocalIPAddress(), 6433);
		this.pgBouncerServiceAddress = Requires.nonNull(pgBouncerServiceAddress);
		this.redisService = Objects.requireNonNull(redisService);
		this.caddyConfigSupplier = Utils.Functions.memoize(() -> {
			var caddyConfig = CaddyConfig.builder().build();
			caddyConfig = CaddyConfigs.withAdminDisabled(caddyConfig);
			caddyConfig = JodaBeans.set(caddyConfig, getStorage(), CaddyConfig.meta().storage());
			var dynamicDnsOp = getDynamicDNS();
			if (dynamicDnsOp.isPresent())
				caddyConfig = JodaBeans.set(caddyConfig, dynamicDnsOp.get(), CaddyConfig.meta().apps(),
						Apps.meta().dynamicDns());
			caddyConfig = JodaBeans.set(caddyConfig, getTls(), CaddyConfig.meta().apps(), Apps.meta().tls());
			caddyConfig = JodaBeans.set(caddyConfig, getLayer4(), CaddyConfig.meta().apps(), Apps.meta().layer4());
			if (MachineConfig.isDeveloper())
				logger.info("caddy config:\n{}", Serials.Gsons.getPretty().toJson(caddyConfig));
			return caddyConfig;
		});
	}

	protected PGBouncerServiceConfig getServiceConfig() {
		return Configs.get(PGBouncerServiceConfig.class);
	}

	protected Optional<DynamicDns> getDynamicDNS() {
		var cfg = Configs.get(PGBouncerAppConfig.class);
		if (!cfg.dynamicDnsEnabled())
			return Optional.empty();
		var blder = DynamicDns.builder();
		blder.dnsProvider(DnsProvider.builder().name(cfg.dynamicDnsProvider()).build());
		blder.checkInterval(String.format("%sms", cfg.dynamicDnsCheckInterval().toMillis()));
		StreamEx<IpSource> ipSources;
		{
			var ipSourcesInput = Utils.Lots.stream(cfg.dynamicDnsIPSources());
			ipSourcesInput = ipSourcesInput.filter(Utils.Strings::isNotBlank);
			ipSourcesInput = ipSourcesInput.ifEmpty("upnp", "https://api.ipify.org", "https://api64.ipify.org");
			ipSourcesInput = ipSourcesInput.distinct();
			ipSources = ipSourcesInput.distinct().map(v -> {
				var uri = URIs.parse(v).orElse(null);
				if (uri == null)
					return IpSource.builder().source(v).build();
				return IpSource.builder().source("simple_http").endpoints(uri.toString()).build();
			});
		}
		blder.ipSources(ipSources.toList());
		var domains = Utils.Lots.stream(cfg.dynamicDnsDomains()).prepend(getServiceConfig().uri().getHost()).map(v -> {
			var domain = TLDs.parseDomainName(v);
			if (Utils.Strings.isBlank(domain))
				return null;
			var subDomain = Utils.Strings.substringBeforeLast(v, "." + domain);
			if (Utils.Strings.isBlank(subDomain))
				return null;
			return Map.entry(domain, subDomain);
		}).nonNull().distinct().mapToEntry(Entry::getKey, Entry::getValue).grouping();
		var appendDot = Utils.Lots.stream(APPEND_DOT_PROVIDERS)
				.anyMatch(v -> v.equalsIgnoreCase(cfg.dynamicDnsProvider()));
		if (appendDot)
			domains = Utils.Lots.streamMultimap(domains).mapKeys(v -> v + ".").distinct().grouping();
		blder.domains(domains);
		return Optional.of(blder.build());

	}

	protected Tls getTls() {
		var cfg = Configs.get(PGBouncerAppConfig.class);
		var blder = Tls.builder();
		blder.certificates(Certificates.builder().automate(getServiceConfig().uri().getHost()).build());
		var issuerBlder = Issuer.builder();
		issuerBlder.module(cfg.tlsIssuerModule());
		issuerBlder.email(cfg.tlsIssuerContactEmail());
		issuerBlder.apiKey(cfg.tlsIssuerAPIKey());
		issuerBlder.challenges(Challenges.builder()
				.dns(Dns.builder().provider(Provider.builder().name(cfg.tlsIssuerDnsProvider()).build()).build())
				.build());
		blder.automation(Automation.builder().policies(Policy.builder().issuers(issuerBlder.build()).build()).build());
		return blder.build();
	}

	protected Storage getStorage() {
		var cfg = Configs.get(PGBouncerAppConfig.class);
		var blder = Storage.builder();
		blder.module("redis");
		blder.db(0l);
		blder.keyPrefix(this.redisService.getKeyPrefix());
		blder.timeout(cfg.storageTimeout().toSeconds());
		if (this.redisService.getStorageCrypto().isPresent())
			blder.aesKey(this.redisService.getStorageCrypto().get().getAesSecret());
		var storageRedisURI = this.redisService.getURI();
		blder.host(storageRedisURI.getHost());
		blder.port(Objects.toString(storageRedisURI.getPort()));
		blder.tlsEnabled(this.redisService.isTlsEnabled());
		var creds = URIs.parseCredentials(storageRedisURI);
		if (creds != null) {
			var password = creds.getKey();
			if (password == null)
				password = "";
			if (Utils.Strings.isNotBlank(creds.getValue().orElse(null)))
				password = password + creds.getValue().get();
			blder.password(password);
		}
		var storageDirectory = Configs.get(CaddyExecConfig.class).storageDirectory();
		if (MachineConfig.isDeveloper())
			storageDirectory = Utils.Files.tempFile("caddy", "data",
					Utils.Crypto.hashMD5(storageDirectory.getAbsolutePath()).encodeHex());
		return blder.build();
	}

	protected Layer4 getLayer4() {
		var cfg = Configs.get(PGBouncerAppConfig.class);
		var blder = ServerLayer4.builder();
		var uri = getServiceConfig().uri();
		var port = uri.getPort();
		if (port < 0)
			port = URIs.isSecure(uri) ? 443 : 80;
		blder.listen(List.of(String.format(":%s", port)));
		List<RouteLayer4> routes = new ArrayList<>();
		{
			var sniMatch = MatchLayer4.builder().tls(MatchTls.builder().build()).build();
			var tlsHandle = HandleLayer4.builder().handler("tls")
					.connectionPolicies(ConnectionPolicy.builder().alpn("http/1.1").build()).build();
			var proxyHandle = HandleLayer4.builder().handler("proxy")
					.upstreams(UpstreamLayer4.builder().dial(
							String.format("%s:%s", this.serviceAddress.getHostString(), this.serviceAddress.getPort()))
							.build())
					.build();
			routes.add(RouteLayer4.builder().match(sniMatch).handle(tlsHandle, proxyHandle).build());
		}
		{
			var proxyHandle = HandleLayer4.builder().handler("proxy")
					.upstreams(UpstreamLayer4.builder().dial(String.format("%s:%s",
							pgBouncerServiceAddress.getHostString(), pgBouncerServiceAddress.getPort())).build())
					.build();
			routes.add(RouteLayer4.builder().handle(proxyHandle).build());
		}
		var server = blder.routes(routes).build();
		return Layer4.builder().servers(Map.of("proxy", server)).build();
	}

	@Override
	public CompletableStartedProcess get() {
		var caddyExec = Configs.get(CaddyExecConfig.class).caddyExec();
		if (!caddyExec.exists() && MachineConfig.isDeveloper()) {
			List<String> modules = new ArrayList<>();
			modules.add("github.com/gamalan/caddy-tlsredis");
			modules.add("github.com/mholt/caddy-l4");
			modules.add("github.com/mholt/caddy-dynamicdns");
			modules.add("github.com/caddy-dns/route53");
			caddyExec = Caddy.getExecutable(modules.toArray(String[]::new));
		}
		Requires.isTrue(caddyExec.exists() && caddyExec.canExecute(), "caddy not found:%s",
				caddyExec.getAbsolutePath());
		return Caddy.start(caddyExec, this.caddyConfigSupplier.get());
	}

	public static void main(String[] args) {
		ENVService.init();
		var generator = new CaddyService(InetSocketAddress.createUnresolved("localhost", 1234), null,
				RedisService.get());
		System.out.println(Serials.Gsons.getPretty().toJson(generator.get()));
	}
}
