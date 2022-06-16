package com.lfp.pgbouncer_app.storage;

import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;

import com.google.common.reflect.TypeToken;
import com.lfp.data.redisson.client.RedissonUtils;
import com.lfp.data.redisson.client.codec.GsonCodec;
import com.lfp.data.redisson.tools.concurrent.KeepAliveSemaphore;
import com.lfp.joe.cache.StatValue;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.threads.Threads;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.KeyGenerator;
import com.lfp.joe.utils.function.Requires;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;

public class KeyPrefixService {

	private static final Class<?> THIS_CLASS = new Object() {}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final int VERSION = 3;
	@SuppressWarnings("serial")
	private static final TypeToken<StatValue<String>> KEY_PREFIX_VALUE_STORE_TT = new TypeToken<StatValue<String>>() {};
	private static final Codec KEY_PREFIX_VALUE_STORE_CODEC = new GsonCodec(TypeToken.of(String.class),
			KEY_PREFIX_VALUE_STORE_TT);
	private final RedissonClient redisClient;
	private final String host;
	private String _value;

	public KeyPrefixService(RedissonClient redisClient, String host) {
		this.redisClient = Requires.nonNull(redisClient);
		this.host = Requires.notBlank(host);
	}

	public String getKeyPrefix() {
		if (_value == null)
			synchronized (this) {
				if (_value == null) {
					var value = Configs.get(PGBouncerAppConfig.class).storageKeyPrefix();
					if (Utils.Strings.isBlank(value))
						value = Threads.Futures.join(getOrCreateValue()).getValue();
					_value = value;
				}
			}
		return _value;
	}

	private String getStorageKey() {
		return KeyGenerator.apply(this.host, "key", "prefix", THIS_CLASS, VERSION);
	}

	private ListenableFuture<StatValue<String>> getOrCreateValue() {
		return getOrCreateValue(false);
	}

	private ListenableFuture<StatValue<String>> getOrCreateValue(boolean locked) {
		var currentValueFuture = getCurrentValue();
		return currentValueFuture.flatMap(statValue -> {
			if (statValue != null)
				return FutureUtils.immediateResultFuture(statValue);
			if (!locked) {
				var semaphore = new KeepAliveSemaphore(this.redisClient, 1, getStorageKey(), "lock");
				var acquireFuture = semaphore.acquireAsyncKeepAliveFlatSupply(() -> getOrCreateValue(true));
				return acquireFuture;
			}
			var newStatValue = StatValue.build(KeyGenerator.apply(this.host, Utils.Crypto.getSecureRandomString()));
			var rfuture = this.redisClient.getBucket(this.getStorageKey(), KEY_PREFIX_VALUE_STORE_CODEC)
					.setAsync(newStatValue);
			return RedissonUtils.asListenableFuture(rfuture).map(nil -> newStatValue);
		});
	}

	@SuppressWarnings("unchecked")
	private ListenableFuture<StatValue<String>> getCurrentValue() {
		var rfuture = this.redisClient.getBucket(this.getStorageKey(), KEY_PREFIX_VALUE_STORE_CODEC).getAsync();
		return RedissonUtils.asListenableFuture(rfuture).map(obj -> {
			StatValue<String> sv = (StatValue<String>) obj;
			if (sv == null)
				return null;
			if (Utils.Strings.isBlank(sv.getValue()))
				return null;
			if (sv.getCreatedAt() == null)
				return null;
			var refreshBefore = Configs.get(PGBouncerAppConfig.class).storageKeyPrefixRefreshBefore();
			if (refreshBefore != null && sv.getCreatedAt().before(refreshBefore))
				return null;
			return sv;
		});
	}

}
