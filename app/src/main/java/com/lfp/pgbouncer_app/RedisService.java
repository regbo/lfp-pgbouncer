package com.lfp.pgbouncer_app;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.redisson.client.RedisException;
import org.threadly.concurrent.future.FutureUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.lfp.data.redisson.client.RedissonClientLFP;
import com.lfp.data.redisson.client.RedissonClients;
import com.lfp.joe.core.cache.Instances;
import com.lfp.joe.core.function.Nada;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.threads.Threads;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.Requires;
import com.lfp.pgbouncer_app.StorageCrypto.DecryptException;

import at.favre.lib.bytes.Bytes;
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
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
	private static final Duration LOG_DECRYPT_ERROR_INTERVAL = Duration.ofSeconds(10);

	private final EventBus listenerEventBus = new EventBus();
	private final Map<String, Bytes> keyValueHashes = new ConcurrentHashMap<>();
	private final Cache<Bytes, Nada> logDecryptErrorCache = Caffeine.newBuilder()
			.expireAfterWrite(LOG_DECRYPT_ERROR_INTERVAL).build();
	private final RedissonClientLFP client;
	private final StorageCrypto storageCrypto;
	private final String storageKeyPrefix;

	public RedisService(RedissonClientLFP client, StorageCrypto storageCrypto, String storageKeyPrefix) {
		this.client = Objects.requireNonNull(client);
		this.storageCrypto = storageCrypto;
		this.storageKeyPrefix = Requires.notBlank(storageKeyPrefix);
		this.streamReaders().forEach(sr -> keyValueHashes.put(sr.getKey(), sr.hash()));
		Runnable pollTask = () -> {
			try {
				poll();
			} catch (Throwable t) {
				while (t instanceof RedisException) {
					var cause = t.getCause();
					if (cause == null)
						break;
					t = cause;
				}
				if (!Utils.Exceptions.isCancelException(t))
					logger.warn("poll error. storageKeyPrefix:{}", storageKeyPrefix, t);
			}
		};
		var pollFuture = FutureUtils.scheduleWhile(Threads.Pools.centralPool(), POLL_INTERVAL.toMillis(), true,
				pollTask, () -> !this.isScrapped());
		Threads.Futures.onScrapCancel(this, pollFuture, true);
	}

	public StreamEx<StorageReader> streamReaders() {
		return streamReaders(null);
	}

	protected StreamEx<StorageReader> streamReaders(
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

	public Scrapable addListener(Listener listener) {
		Objects.requireNonNull(listener);
		this.listenerEventBus.register(listener);
		return Scrapable.create(() -> this.listenerEventBus.unregister(listener));
	}

	protected void poll() {
		var added = new LinkedHashSet<StorageReader>();
		var updated = new LinkedHashSet<StorageReader>();
		var removedKeys = new LinkedHashSet<>(keyValueHashes.keySet());
		BiFunction<StorageReader, DecryptException, byte[]> decryptErrorHandler = (sr, t) -> {
			var hash = Utils.Crypto.hashMD5(sr.getKey(), t.getCipher());
			logDecryptErrorCache.get(hash, nil -> {
				logger.warn(StorageReader.createDecryptErrorMessage(sr, t));
				return Nada.get();
			});
			return null;
		};
		for (var sr : streamReaders(decryptErrorHandler)) {
			removedKeys.remove(sr.getKey());
			keyValueHashes.compute(sr.getKey(), (nil, current) -> {
				var hash = sr.hash();
				if (current != null && current.equals(hash))
					return current;
				if (current != null)
					updated.add(sr);
				else
					added.add(sr);
				return hash;
			});
		}
		removedKeys.forEach(keyValueHashes::remove);
		if (added.isEmpty() && updated.isEmpty() && removedKeys.isEmpty())
			return;
		ModificationEvent event = new ModificationEvent(added, updated, removedKeys);
		this.listenerEventBus.post(event);
	}

	public static interface Listener {

		@Subscribe
		public void onModification(ModificationEvent event);
	}

	public static class ModificationEvent {

		private final List<StorageReader> added;
		private final List<StorageReader> updated;
		private final List<String> removedKeys;

		public ModificationEvent(Iterable<? extends StorageReader> added, Iterable<? extends StorageReader> updated,
				Iterable<? extends String> removedKeys) {
			super();
			this.added = nonNullDistinctList(added);
			this.updated = nonNullDistinctList(updated);
			this.removedKeys = nonNullDistinctList(removedKeys);
		}

		public List<StorageReader> getAdded() {
			return added;
		}

		public List<StorageReader> getUpdated() {
			return updated;
		}

		public List<String> getRemovedKeys() {
			return removedKeys;
		}

		@Override
		public String toString() {
			return "ModificationEvent [added=" + added + ", updated=" + updated + ", removedKeys=" + removedKeys + "]";
		}

		private static <X> List<X> nonNullDistinctList(Iterable<? extends X> iterable) {
			return Utils.Lots.stream(iterable).nonNull().distinct().map(v -> (X) v).toImmutableList();
		}
	}

}
