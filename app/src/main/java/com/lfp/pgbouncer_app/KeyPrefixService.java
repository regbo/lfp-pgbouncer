package com.lfp.pgbouncer_app;

import java.time.Duration;
import java.util.Date;
import java.util.Objects;

import org.redisson.client.codec.Codec;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;

import com.google.common.reflect.TypeToken;
import com.lfp.data.redisson.client.RedissonClientLFP;
import com.lfp.data.redisson.client.RedissonClients;
import com.lfp.data.redisson.client.RedissonUtils;
import com.lfp.data.redisson.client.codec.GsonCodec;
import com.lfp.data.redisson.tools.accessor.KeepAliveSemaphore;
import com.lfp.data.redisson.tools.accessor.KeepAliveSemaphore.ReleaseCallback;
import com.lfp.joe.cache.StatValue;
import com.lfp.joe.core.cache.Instances;
import com.lfp.joe.threads.Threads;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.KeyGenerator;
import com.lfp.joe.utils.function.Requires;

public class KeyPrefixService {

	public static KeyPrefixService get() {
		return Instances.get(KeyPrefixService.class, () -> {
			var redisConfig = ENVParser.getRedisConfig().get();
			var client = RedissonClients.get(redisConfig);
			var address = ENVParser.getAddress().get();
			return new KeyPrefixService(client, address.getHostString(),
					ENVParser.getStorageKeyPrefixRefreshBefore().orElse(null));
		});
	}

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final int VERSION = 2;
	@SuppressWarnings("serial")
	private static final TypeToken<StatValue<String>> KEY_PREFIX_VALUE_STORE_TT = new TypeToken<StatValue<String>>() {
	};
	private static final Codec KEY_PREFIX_VALUE_STORE_CODEC = new GsonCodec(TypeToken.of(String.class),
			KEY_PREFIX_VALUE_STORE_TT);
	private static final Duration LOCK_LEASE_DURATION = Duration.ofSeconds(10);
	private final RedissonClientLFP client;
	private final String host;
	private final Date refreshBefore;
	private String _value;

	public KeyPrefixService(RedissonClientLFP client, String host, Date refreshBefore) {
		this.client = Objects.requireNonNull(client);
		this.host = Requires.notBlank(host);
		this.refreshBefore = refreshBefore;
	}

	public String getKeyPrefix() {
		if (_value == null)
			synchronized (this) {
				if (_value == null) {
					_value = ENVParser.getStorageKeyPrefix().orElseGet(() -> {
						return Threads.Futures.getUnchecked(getOrCreateValue()).getValue();
					});
				}
			}
		return _value;
	}

	private String getStorageKey() {
		return KeyGenerator.apply(this.host, "key", "prefix", THIS_CLASS, VERSION);
	}

	private ListenableFuture<StatValue<String>> getOrCreateValue() {
		return getOrCreateValue(null);
	}

	private ListenableFuture<StatValue<String>> getOrCreateValue(ReleaseCallback releaseCallback) {
		var currentValueFuture = getCurrentValue();
		return currentValueFuture.flatMap(statValue -> {
			if (statValue != null)
				return FutureUtils.immediateResultFuture(statValue);
			if (releaseCallback == null) {
				var semaphore = new KeepAliveSemaphore(client, 1, getStorageKey(), "lock");
				var acquireFuture = semaphore.acquireAsyncKeepAlive(LOCK_LEASE_DURATION);
				return acquireFuture.flatMap(cb -> {
					var future = getOrCreateValue(cb);
					return future.listener(() -> cb.release());
				});
			}
			var newStatValue = StatValue.build(KeyGenerator.apply(this.host, Utils.Crypto.getSecureRandomString()));
			var rfuture = client.getBucket(this.getStorageKey(), KEY_PREFIX_VALUE_STORE_CODEC).setAsync(newStatValue);
			return RedissonUtils.asListenableFuture(rfuture).map(nil -> newStatValue);
		});
	}

	@SuppressWarnings("unchecked")
	private ListenableFuture<StatValue<String>> getCurrentValue() {
		var rfuture = client.getBucket(this.getStorageKey(), KEY_PREFIX_VALUE_STORE_CODEC).getAsync();
		return RedissonUtils.asListenableFuture(rfuture).map(obj -> {
			StatValue<String> sv = (StatValue<String>) obj;
			if (sv == null)
				return null;
			if (Utils.Strings.isBlank(sv.getValue()))
				return null;
			if (sv.getCreatedAt() == null)
				return null;
			if (refreshBefore != null && sv.getCreatedAt().before(refreshBefore))
				return null;
			return sv;
		});
	}

	public static void main(String[] args) {
		System.out.println(KeyPrefixService.get().getKeyPrefix());
	}

}
