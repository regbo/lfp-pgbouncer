package com.lfp.pgbouncer_app.storage;

import java.util.Objects;
import java.util.function.BiFunction;

import com.lfp.data.redisson.client.RedissonClientLFP;
import com.lfp.data.redisson.client.RedissonClients;
import com.lfp.joe.core.cache.Instances;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.Requires;
import com.lfp.pgbouncer_app.ENVParser;
import com.lfp.pgbouncer_app.storage.StorageCrypto.DecryptException;

import one.util.streamex.StreamEx;

public class RedisService extends Scrapable.Impl {

	public static RedisService get() {
		return Instances.get(RedisService.class, () -> {
			var redisConfig = ENVParser.getRedisConfig().get();
			var client = RedissonClients.get(redisConfig);
			var storageCrypto = StorageCrypto.tryGet().orElse(null);
			return new RedisService(client, storageCrypto, KeyPrefixService.get().getKeyPrefix());
		});
	}

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);

	private final RedissonClientLFP client;
	private final StorageCrypto storageCrypto;
	private final String storageKeyPrefix;

	public RedisService(RedissonClientLFP client, StorageCrypto storageCrypto, String storageKeyPrefix) {
		this.client = Objects.requireNonNull(client);
		this.storageCrypto = storageCrypto;
		this.storageKeyPrefix = Requires.notBlank(storageKeyPrefix);
	}

	public StreamEx<StorageReader> streamReaders() {
		return streamReaders(null);
	}

	public StreamEx<StorageReader> streamReaders(
			BiFunction<StorageReader, DecryptException, byte[]> decryptErrorHandler) {
		var keysByPattern = this.client.getKeys().getKeysByPattern(storageKeyPrefix + "*");
		var readerStream = Utils.Lots.stream(keysByPattern).map(key -> {
			var blder = StorageReader.builder().client(client).storageCrypto(storageCrypto).keyPrefix(storageKeyPrefix)
					.key(key);
			if (decryptErrorHandler != null)
				blder.decryptErrorHandler(decryptErrorHandler);
			return blder.build();
		});
		return readerStream;
	}

	public RedissonClientLFP getClient() {
		return client;
	}
}
