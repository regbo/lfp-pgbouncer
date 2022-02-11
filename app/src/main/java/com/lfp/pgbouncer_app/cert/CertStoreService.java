package com.lfp.pgbouncer_app.cert;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.redisson.client.RedisException;
import org.threadly.concurrent.future.FutureUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.lfp.joe.beans.JodaBeans;
import com.lfp.joe.core.cache.Instances;
import com.lfp.joe.core.function.Nada;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.threads.Threads;
import com.lfp.joe.utils.Utils;
import com.lfp.pgbouncer_app.storage.RedisService;
import com.lfp.pgbouncer_app.storage.StorageCrypto;
import com.lfp.pgbouncer_app.storage.StorageReader;
import com.lfp.pgbouncer_app.storage.StorageCrypto.DecryptException;

import at.favre.lib.bytes.Bytes;
import one.util.streamex.StreamEx;

public class CertStoreService extends Scrapable.Impl {

	public static CertStoreService get() {
		return Instances.get(CertStoreService.class, () -> {
			return new CertStoreService(RedisService.get());
		});
	}

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);

	private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
	private static final Duration LOG_DECRYPT_ERROR_INTERVAL = Duration.ofSeconds(10);

	private final EventBus listenerEventBus = new EventBus();
	private final Map<CertGroup, CertStore> certMap = new ConcurrentHashMap<>();
	private final Cache<Bytes, Nada> logDecryptErrorCache = Caffeine.newBuilder()
			.expireAfterWrite(LOG_DECRYPT_ERROR_INTERVAL).build();
	private final RedisService redisService;

	public CertStoreService(RedisService redisServce) {
		this.redisService = Objects.requireNonNull(redisServce);
		this.update();
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
					logger.warn("poll error", t);
			}
		};
		var pollFuture = FutureUtils.scheduleWhile(Threads.Pools.centralPool(), POLL_INTERVAL.toMillis(), true,
				pollTask, () -> !this.isScrapped());
		Threads.Futures.onScrapCancel(this, pollFuture, true);
	}

	public StreamEx<CertStore> streamCertStores() {
		return Utils.Lots.stream(certMap).values().sortedBy(v -> -1 * v.getModified().map(Date::getTime).orElse(0l));
	}

	public Scrapable addListener(ModificationEventListener listener) {
		Objects.requireNonNull(listener);
		this.listenerEventBus.register(listener);
		return Scrapable.create(() -> this.listenerEventBus.unregister(listener));
	}

	protected Optional<ModificationEvent> update() {
		var added = new LinkedHashSet<CertStore>();
		var updated = new LinkedHashSet<CertStore>();
		var removed = new LinkedHashMap<>(certMap);
		BiFunction<StorageReader, DecryptException, byte[]> decryptErrorHandler = (sr, t) -> {
			var hash = Utils.Crypto.hashMD5(sr.getKey(), t.getCipher());
			logDecryptErrorCache.get(hash, nil -> {
				logger.warn(StorageReader.createDecryptErrorMessage(sr, t));
				return Nada.get();
			});
			return null;
		};
		var groupToStoreReaderMap = this.redisService.streamReaders(decryptErrorHandler)
				.mapToEntry(v -> CertGroup.tryBuild(v).orElse(null)).invert().nonNullKeys().grouping();
		for (var ent : groupToStoreReaderMap.entrySet()) {
			var group = ent.getKey();
			var storageReaders = ent.getValue();
			var certStore = getCertStore(group, storageReaders).orElse(null);
			if (certStore == null)
				continue;
			removed.remove(group);
			certMap.compute(group, (k, current) -> {
				if (current != null && current.hash().equals(certStore.hash()))
					return current;
				if (current == null)
					added.add(certStore);
				else
					updated.add(certStore);
				return certStore;
			});
		}
		removed.forEach(certMap::remove);
		if (added.isEmpty() && updated.isEmpty() && removed.isEmpty())
			return Optional.empty();
		var event = new ModificationEvent(added, updated, removed.values());
		return Optional.of(event);
	}

	protected void poll() {
		ModificationEvent event = update().orElse(null);
		if (event != null)
			this.listenerEventBus.post(event);
	}

	private static Optional<CertStore> getCertStore(CertGroup group, List<StorageReader> storageReaders) {
		if (group == null)
			return Optional.empty();
		var propertiesOp = getStorageReaderByExtension(storageReaders, "json");
		var keyOp = getStorageReaderByExtension(storageReaders, "key");
		var certOp = getStorageReaderByExtension(storageReaders, "crt");
		if (StreamEx.of(propertiesOp, keyOp, certOp).anyMatch(Optional::isEmpty))
			return Optional.empty();
		var blder = CertStore.builder();
		JodaBeans.copyToBuilder(group, blder);
		blder.properties(propertiesOp.get());
		blder.key(keyOp.get());
		blder.cert(certOp.get());
		return Optional.of(blder.build());
	}

	private static Optional<StorageReader> getStorageReaderByExtension(Iterable<? extends StorageReader> storageReaders,
			String extension) {
		StreamEx<StorageReader> srStream = Utils.Lots.stream(storageReaders);
		srStream = srStream.nonNull();
		srStream = srStream.filter(v -> Utils.Strings.equals(v.getExtension().orElse(null), extension));
		srStream = srStream.filter(v -> v.getValue().length() > 0);
		srStream = srStream.sortedBy(v -> -1 * v.getModified().map(Date::getTime).orElse(0l));
		return srStream.findFirst();
	}

	public static interface ModificationEventListener {

		@Subscribe
		public void onModification(ModificationEvent event);
	}

	public static class ModificationEvent {

		private final List<CertStore> added;
		private final List<CertStore> updated;
		private final List<CertGroup> removed;

		public ModificationEvent(Iterable<? extends CertStore> added, Iterable<? extends CertStore> updated,
				Iterable<? extends CertStore> removed) {
			super();
			this.added = nonNullDistinctList(added);
			this.updated = nonNullDistinctList(updated);
			this.removed = nonNullDistinctList(removed);
		}

		public List<CertStore> getAdded() {
			return added;
		}

		public List<CertStore> getUpdated() {
			return updated;
		}

		public List<CertGroup> getRemovedKeys() {
			return removed;
		}

		@Override
		public String toString() {
			return "ModificationEvent [added=" + added + ", updated=" + updated + ", removed=" + removed + "]";
		}

		private static <X> List<X> nonNullDistinctList(Iterable<? extends X> iterable) {
			return Utils.Lots.stream(iterable).nonNull().distinct().map(v -> (X) v).toImmutableList();
		}
	}

	public static void main(String[] args) {
		for (var certStore : CertStoreService.get().streamCertStores()) {
			System.out.println(certStore.getSans());
			System.out.println(certStore.getIssuerData());
			System.out.println(certStore.hash().encodeBase64());
			System.out.println(certStore.getKey().getValue().encodeUtf8());
			System.out.println(certStore.getCert().getValue().encodeUtf8());
		}
	}

}
