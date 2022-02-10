package com.lfp.pgbouncer_app;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.joda.beans.MetaProperty;

import com.google.gson.annotations.SerializedName;
import com.lfp.caddy.core.config.Storage;
import com.lfp.data.redis.RedisConfig;
import com.lfp.data.redisson.client.RedissonClients;
import com.lfp.joe.beans.JodaBeans;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.serial.gson.InetSocketAddressJsonSerializer;
import com.lfp.joe.utils.Utils;

import one.util.streamex.StreamEx;

public class ENVParser {
	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final String STORAGE_PREFIX = "STORAGE_";
	private static final Supplier<Map<String, List<String>>> ENV_MAP_S = Utils.Functions
			.memoize(() -> Utils.Machine.streamEnvironmentVariables("_", "_FILE").toMap());

	public static Optional<String> getStorageAesKey() {
		return getValue(Storage.meta().aesKey());
	}

	public static Optional<String> getStorageKeyPrefix() {
		return getValue(Storage.meta().keyPrefix());
	}

	public static Optional<RedisConfig> getRedisConfig() {
		var uris = streamRedisURIs().toArray(URI.class);
		if (uris.length == 0)
			return Optional.empty();
		return Optional.of(RedisConfig.fromURIs(uris));
	}

	private static StreamEx<URI> streamRedisURIs() {
		var meta = Storage.meta();
		boolean tlsEnabled = getValue(meta.tlsEnabled()).map(Boolean.TRUE.toString()::equalsIgnoreCase).orElse(false);
		String password = getValue(meta.password()).orElse(null);
		StreamEx<URI> uris = StreamEx.empty();
		{
			var addrStream = streamValues(meta.address())
					.map(v -> InetSocketAddressJsonSerializer.INSTANCE.fromString(v));
			addrStream = addrStream.nonNull().distinct();
			var append = addrStream.map(addr -> {
				return toRedisURI(tlsEnabled, password, addr.getHostString(), addr.getPort());
			});
			uris = uris.append(append);
		}
		{
			var hosts = streamValues(meta.host()).toList();
			var ports = streamValues(meta.port()).toList();
			for (int i = 0; i < Math.max(hosts.size(), ports.size()); i++) {
				var host = hosts.get(i);
				if (Utils.Strings.isBlank(host))
					continue;
				var port = Utils.Strings.parseNumber(ports.get(i)).map(Number::intValue).orElse(null);
				if (port == null)
					continue;
				uris = uris.append(toRedisURI(tlsEnabled, password, host, port));
			}
		}
		uris = uris.nonNull().distinct();
		return uris;
	}

	private static URI toRedisURI(boolean tlsEnabled, String password, String hostString, int port) {
		var url = String.format("redis%s://%s%s:%s", tlsEnabled ? "s" : "",
				password != null ? String.format(":%s@", password) : "", hostString, port);
		return URI.create(url);
	}

	private static Optional<String> getValue(MetaProperty<?> metaProperty) {
		return streamValues(metaProperty).filter(Utils.Strings::isNotBlank).findFirst();
	}

	private static StreamEx<String> streamValues(MetaProperty<?> metaProperty) {
		var valueStream = streamNames(metaProperty).map(name -> {
			var estream = Utils.Lots.streamMultimap(ENV_MAP_S.get());
			estream = estream.filterKeys(v -> {
				return Utils.Strings.equalsIgnoreCase(v, name);
			});
			return estream.values();
		}).chain(Utils.Lots::flatMap);
		return valueStream;
	}

	private static StreamEx<String> streamNames(MetaProperty<?> metaProperty) {
		if (metaProperty == null)
			return StreamEx.empty();
		List<String> nameList;
		{
			var nameStream = JodaBeans.streamNames(metaProperty);
			var annos = metaProperty.annotations();
			if (annos != null)
				nameStream = nameStream
						.append(StreamEx.of(annos).select(SerializedName.class).map(SerializedName::value));
			nameStream = nameStream.filter(Utils.Strings::isNotBlank);
			nameStream = nameStream.distinct();
			nameList = nameStream.toList();
		}
		var resultStream = Utils.Lots.stream(nameList).map(v -> STORAGE_PREFIX + v);
		resultStream = resultStream.append(nameList);
		resultStream = resultStream.distinct();
		return resultStream;
	}

	public static void main(String[] args) throws IOException {
		File outFile0 = Utils.Files.tempFile(THIS_CLASS, "outFile0.txt");
		File outFile1 = Utils.Files.tempFile(THIS_CLASS, "outFile1.txt");
		for (var file : List.of(outFile0, outFile1)) {
			Files.writeString(file.toPath(), "192.168.1.71:" + Utils.Crypto.getRandomInclusive(1000, 10000),
					StandardCharsets.UTF_8);
		}
		Utils.Machine.setEnvironmentVariable("PASSWORD", "password123");
		Utils.Machine.setEnvironmentVariable("STORAGE_TLS_ENABLED", true);
		Utils.Machine.setEnvironmentVariable("ADDRESS_FILE", outFile0.getAbsolutePath());
		Utils.Machine.setEnvironmentVariable("STORAGE_ADDRESS_1_FILE", outFile1.getAbsolutePath());
		System.out.println(Serials.Gsons.getPretty().toJson(ENV_MAP_S.get()));
		streamRedisURIs().forEach(v -> {
			System.out.println(v);
		});
		var redisConfig = ENVParser.getRedisConfig().get();
		var db = RedissonClients.get(redisConfig);

	}
}
