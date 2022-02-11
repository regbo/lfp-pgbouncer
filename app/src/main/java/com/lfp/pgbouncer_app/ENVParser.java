package com.lfp.pgbouncer_app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.joda.beans.MetaProperty;

import com.google.gson.annotations.SerializedName;
import com.lfp.caddy.core.config.Storage;
import com.lfp.data.redis.RedisConfig;
import com.lfp.data.redisson.client.RedissonClients;
import com.lfp.joe.beans.JodaBeans;
import com.lfp.joe.core.config.MachineConfig;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.serial.gson.InetSocketAddressJsonSerializer;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.time.TimeParser;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;

import one.util.streamex.StreamEx;

public class ENVParser {

	protected ENVParser() {

	}

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);

	private static final Supplier<Map<String, List<String>>> ENV_MAP_S = Utils.Functions.memoize(() -> {
		var map = Utils.Machine.streamEnvironmentVariables("_", "_FILE").toMap();
		if (MachineConfig.isDeveloper()) {
			var configPrivate = new File("src/main/resources/config-private");
			if (configPrivate.exists()) {
				for (var file : configPrivate.listFiles()) {
					var name = file.getName();
					var splitAt = Utils.Strings.lastIndexOf(name, ".");
					String ext;
					if (splitAt < 0)
						ext = "";
					else {
						ext = name.substring(splitAt + 1);
						name = name.substring(0, splitAt);
					}
					if (Utils.Strings.equalsIgnoreCase(name, "env") || Utils.Strings.equalsIgnoreCase(ext, "env")) {
						var append = Utils.Machine.streamEnvironmentVariables(new FileInputStream(file), "_", "_FILE")
								.flatMapValues(Collection::stream);
						map = Utils.Lots.streamMultimap(map).append(append).distinct().grouping();
					}
				}

			}
		}
		return Collections.unmodifiableMap(map);
	}, null);

	public static Optional<InetSocketAddress> getAddress() {
		return getValue(Configs.get(PGBouncerAppConfig.class).addressEnvironmentVariableName()).flatMap(v -> {
			var result = InetSocketAddressJsonSerializer.INSTANCE.fromString(v);
			return Optional.ofNullable(result);
		});
	}

	public static Optional<Date> getRefreshBefore() {
		return getValue(Configs.get(PGBouncerAppConfig.class).refreshBeforeEnvironmentVariableName()).flatMap(v -> {
			return TimeParser.tryParseDate(v);
		});
	}

	public static Optional<String> getStorageAesKey() {
		return getValue(Storage.meta().aesKey());
	}

	public static Optional<RedisConfig> getRedisConfig() {
		var uris = streamRedisURIs().toArray(URI.class);
		if (uris.length == 0)
			return Optional.empty();
		return Optional.of(RedisConfig.fromURIs(uris));
	}

	private static StreamEx<URI> streamRedisURIs() {
		var meta = Storage.meta();
		boolean tlsEnabled = getValue(meta.tlsEnabled()).map(Boolean.TRUE.toString()::equalsIgnoreCase).orElse(true);
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
		return streamNames(metaProperty).map(name -> getValue(name)).mapPartial(Function.identity()).findFirst();
	}

	private static Optional<String> getValue(String name) {
		return streamValues(name).filter(Utils.Strings::isNotBlank).findFirst();
	}

	private static StreamEx<String> streamValues(MetaProperty<?> metaProperty) {
		return streamNames(metaProperty).map(name -> streamValues(name)).chain(Utils.Lots::flatMap);
	}

	private static StreamEx<String> streamValues(String name) {
		var estream = Utils.Lots.streamMultimap(ENV_MAP_S.get());
		estream = estream.filterKeys(v -> {
			return Utils.Strings.equalsIgnoreCase(v, name);
		});
		return estream.values();
	}

	private static StreamEx<String> streamNames(MetaProperty<?> metaProperty) {
		if (metaProperty == null)
			return StreamEx.empty();
		StreamEx<String> nameStream = JodaBeans.streamNames(metaProperty);
		var annos = metaProperty.annotations();
		if (annos != null)
			nameStream = nameStream.append(StreamEx.of(annos).select(SerializedName.class).map(SerializedName::value));
		nameStream = nameStream.filter(Utils.Strings::isNotBlank);
		var prefix = Configs.get(PGBouncerAppConfig.class).storageEnvironmentVariablePrefix();
		nameStream = nameStream.map(v -> prefix + v);
		nameStream = nameStream.distinct();
		return nameStream;
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
