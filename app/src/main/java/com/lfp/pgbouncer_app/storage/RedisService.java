package com.lfp.pgbouncer_app.storage;

import java.net.URI;
import java.util.Objects;
import java.util.function.BiFunction;

import com.google.common.base.Optional;
import com.lfp.data.redis.RedisConfig;
import com.lfp.data.redisson.client.RedissonClientLFP;
import com.lfp.data.redisson.client.RedissonClients;
import com.lfp.joe.core.cache.Instances;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.net.http.uri.URIs;
import com.lfp.joe.utils.Utils;
import com.lfp.pgbouncer.service.config.PGBouncerServiceConfig;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;
import com.lfp.pgbouncer_app.storage.StorageCrypto.DecryptException;

import io.mikael.urlbuilder.UrlBuilder;
import one.util.streamex.StreamEx;

public class RedisService extends Scrapable.Impl {

	public static RedisService get() {
		return Instances.get(RedisService.class, () -> {
			var cfg = Configs.get(PGBouncerAppConfig.class);
			var redisURI = cfg.storageRedisURI();
			if (URIs.parseCredentials(redisURI) == null) {
				var password = cfg.storageKeyPassword();
				if (Utils.Strings.isNotBlank(password))
					redisURI = UrlBuilder.fromUri(redisURI).withUserInfo(String.format(":%s", password)).toUri();
			}
			var storageCrypto = StorageCrypto.tryGet().orElse(null);
			var host = Configs.get(PGBouncerServiceConfig.class).uri().getHost();
			return new RedisService(host, redisURI, storageCrypto);
		});
	}

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);

	private final URI uri;
	private final RedissonClientLFP client;
	private final String keyPrefix;
	private final StorageCrypto storageCrypto;

	public RedisService(String addressHost, URI redisURI, StorageCrypto storageCrypto) {
		this.uri = Objects.requireNonNull(redisURI);
		this.client = RedissonClients.get(RedisConfig.fromURIs(this.uri));
		this.keyPrefix = new KeyPrefixService(this.client, addressHost).getKeyPrefix();
		this.storageCrypto = storageCrypto;
	}

	public Optional<StorageCrypto> getStorageCrypto() {
		return Optional.of(storageCrypto);
	}

	public URI getURI() {
		return uri;
	}

	public RedissonClientLFP getClient() {
		return client;
	}

	public boolean isTlsEnabled() {
		var scheme = getURI().getScheme();
		if (Utils.Strings.equalsIgnoreCase(scheme, "rediss"))
			return true;
		return URIs.isSecure(getURI());
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public StreamEx<StorageReader> streamReaders() {
		return streamReaders();
	}

	public StreamEx<StorageReader> streamReaders(
			BiFunction<StorageReader, DecryptException, byte[]> decryptErrorHandler) {
		var keysByPattern = this.client.getKeys().getKeysByPattern(keyPrefix + "*");
		var readerStream = Utils.Lots.stream(keysByPattern).map(key -> {
			var blder = StorageReader.builder().client(client).storageCrypto(storageCrypto).keyPrefix(keyPrefix)
					.key(key);
			if (decryptErrorHandler != null)
				blder.decryptErrorHandler(decryptErrorHandler);
			return blder.build();
		});
		return readerStream;
	}

}
