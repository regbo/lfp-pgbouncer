package com.lfp.pgbouncer_app.cert;

import java.io.IOException;
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
import com.lfp.joe.core.classpath.Instances;
import com.lfp.joe.core.function.Nada;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.threads.Threads;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.crypto.CertificateParser;
import com.lfp.pgbouncer_app.ENVService;
import com.lfp.pgbouncer_app.storage.RedisService;
import com.lfp.pgbouncer_app.storage.StorageCrypto.DecryptException;
import com.lfp.pgbouncer_app.storage.StorageReader;

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

	private final Map<CertGroup, CertStore> certMap = new ConcurrentHashMap<>();
	private final EventBus modificationListenerEventBus = new EventBus();
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

	public Scrapable addModificationEventListener(ModificationEventListener listener) {
		Objects.requireNonNull(listener);
		this.modificationListenerEventBus.register(listener);
		return Scrapable.create(() -> this.modificationListenerEventBus.unregister(listener));
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
			this.modificationListenerEventBus.post(event);
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

	public static void main(String[] args) throws IOException {
		ENVService.init();
		var input = "-----BEGIN CERTIFICATE-----\n"
				+ "MIIEFzCCA5ygAwIBAgIRAMCE9lhniE+xfy4bi4N+NDQwCgYIKoZIzj0EAwMwSzEL\n"
				+ "MAkGA1UEBhMCQVQxEDAOBgNVBAoTB1plcm9TU0wxKjAoBgNVBAMTIVplcm9TU0wg\n"
				+ "RUNDIERvbWFpbiBTZWN1cmUgU2l0ZSBDQTAeFw0yMjAyMTUwMDAwMDBaFw0yMjA1\n"
				+ "MTYyMzU5NTlaMCUxIzAhBgNVBAMTGnJlZ2dpZS1waWVyY2UtZGV2Lmxhc3NvLnRt\n"
				+ "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBMSlp4bnH/pVjmKUeQPoeXwUh5zE\n"
				+ "29Npj24/3T3oEkcWgSua0CPfweARDnIre7teRReuvUwZj8NWjvCJ/RvC9KOCAoUw\n"
				+ "ggKBMB8GA1UdIwQYMBaAFA9r5kvOOUeu9n6QHnnwMJGSyF+jMB0GA1UdDgQWBBTI\n"
				+ "MRoo0MJ3rb9++PwxIoUe5XXYfTAOBgNVHQ8BAf8EBAMCB4AwDAYDVR0TAQH/BAIw\n"
				+ "ADAdBgNVHSUEFjAUBggrBgEFBQcDAQYIKwYBBQUHAwIwSQYDVR0gBEIwQDA0Bgsr\n"
				+ "BgEEAbIxAQICTjAlMCMGCCsGAQUFBwIBFhdodHRwczovL3NlY3RpZ28uY29tL0NQ\n"
				+ "UzAIBgZngQwBAgEwgYgGCCsGAQUFBwEBBHwwejBLBggrBgEFBQcwAoY/aHR0cDov\n"
				+ "L3plcm9zc2wuY3J0LnNlY3RpZ28uY29tL1plcm9TU0xFQ0NEb21haW5TZWN1cmVT\n"
				+ "aXRlQ0EuY3J0MCsGCCsGAQUFBzABhh9odHRwOi8vemVyb3NzbC5vY3NwLnNlY3Rp\n"
				+ "Z28uY29tMIIBAwYKKwYBBAHWeQIEAgSB9ASB8QDvAHYARqVV63X6kSAwtaKJafTz\n"
				+ "fREsQXS+/Um4havy/HD+bUcAAAF+/g+EXAAABAMARzBFAiEA4+CtpVaxVkUv/zJc\n"
				+ "C6iF7ztyPpRZC5XCIyxtFmq2IukCIEWGYv5Sj//H7Scz3HgwCJHCdmNlVkgIVjYP\n"
				+ "us7euCHiAHUAQcjKsd8iRkoQxqE6CUKHXk4xixsD6+tLx2jwkGKWBvYAAAF+/g+E\n"
				+ "4AAABAMARjBEAiAyWeGGjyXXKYU2COMgPqjRBBxExO6yaUkxiTkgH+yfRQIgcsDV\n"
				+ "HjrDLyNQ2rGIzoiTwpk9Z6c0/ZVgIDfD4dIn3wQwJQYDVR0RBB4wHIIacmVnZ2ll\n"
				+ "LXBpZXJjZS1kZXYubGFzc28udG0wCgYIKoZIzj0EAwMDaQAwZgIxALVUKM6Px/Cc\n"
				+ "9PXtsvNPCjonOPLB33OOMlUAvZQKRdiq0b7i471YgNepq9vh02A5bAIxAMsa6IkF\n"
				+ "Igm0/REc36ieucuh+CF9EBW1nKfsZsX3DQ+/vK6yjyGnpjCxIJ1WiAdEqQ==\n"
				+ "-----END CERTIFICATE-----\n"
				+ "-----BEGIN CERTIFICATE-----\n"
				+ "MIIDhTCCAwygAwIBAgIQI7dt48G7KxpRlh4I6rdk6DAKBggqhkjOPQQDAzCBiDEL\n"
				+ "MAkGA1UEBhMCVVMxEzARBgNVBAgTCk5ldyBKZXJzZXkxFDASBgNVBAcTC0plcnNl\n"
				+ "eSBDaXR5MR4wHAYDVQQKExVUaGUgVVNFUlRSVVNUIE5ldHdvcmsxLjAsBgNVBAMT\n"
				+ "JVVTRVJUcnVzdCBFQ0MgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkwHhcNMjAwMTMw\n"
				+ "MDAwMDAwWhcNMzAwMTI5MjM1OTU5WjBLMQswCQYDVQQGEwJBVDEQMA4GA1UEChMH\n"
				+ "WmVyb1NTTDEqMCgGA1UEAxMhWmVyb1NTTCBFQ0MgRG9tYWluIFNlY3VyZSBTaXRl\n"
				+ "IENBMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAENkFhFytTJe2qypTk1tpIV+9QuoRk\n"
				+ "gte7BRvWHwYk9qUznYzn8QtVaGOCMBBfjWXsqqivl8q1hs4wAYl03uNOXgFu7iZ7\n"
				+ "zFP6I6T3RB0+TR5fZqathfby47yOCZiAJI4go4IBdTCCAXEwHwYDVR0jBBgwFoAU\n"
				+ "OuEJhtTPGcKWdnRJdtzgNcZjY5owHQYDVR0OBBYEFA9r5kvOOUeu9n6QHnnwMJGS\n"
				+ "yF+jMA4GA1UdDwEB/wQEAwIBhjASBgNVHRMBAf8ECDAGAQH/AgEAMB0GA1UdJQQW\n"
				+ "MBQGCCsGAQUFBwMBBggrBgEFBQcDAjAiBgNVHSAEGzAZMA0GCysGAQQBsjEBAgJO\n"
				+ "MAgGBmeBDAECATBQBgNVHR8ESTBHMEWgQ6BBhj9odHRwOi8vY3JsLnVzZXJ0cnVz\n"
				+ "dC5jb20vVVNFUlRydXN0RUNDQ2VydGlmaWNhdGlvbkF1dGhvcml0eS5jcmwwdgYI\n"
				+ "KwYBBQUHAQEEajBoMD8GCCsGAQUFBzAChjNodHRwOi8vY3J0LnVzZXJ0cnVzdC5j\n"
				+ "b20vVVNFUlRydXN0RUNDQWRkVHJ1c3RDQS5jcnQwJQYIKwYBBQUHMAGGGWh0dHA6\n"
				+ "Ly9vY3NwLnVzZXJ0cnVzdC5jb20wCgYIKoZIzj0EAwMDZwAwZAIwJHBUDwHJQN3I\n"
				+ "VNltVMrICMqYQ3TYP/TXqV9t8mG5cAomG2MwqIsxnL937Gewf6WIAjAlrauksO6N\n"
				+ "UuDdDXyd330druJcZJx0+H5j5cFOYBaGsKdeGW7sCMaR2PsDFKGllas=\n"
				+ "-----END CERTIFICATE-----\n"
				+ "-----BEGIN CERTIFICATE-----\n"
				+ "MIID0zCCArugAwIBAgIQVmcdBOpPmUxvEIFHWdJ1lDANBgkqhkiG9w0BAQwFADB7\n"
				+ "MQswCQYDVQQGEwJHQjEbMBkGA1UECAwSR3JlYXRlciBNYW5jaGVzdGVyMRAwDgYD\n"
				+ "VQQHDAdTYWxmb3JkMRowGAYDVQQKDBFDb21vZG8gQ0EgTGltaXRlZDEhMB8GA1UE\n"
				+ "AwwYQUFBIENlcnRpZmljYXRlIFNlcnZpY2VzMB4XDTE5MDMxMjAwMDAwMFoXDTI4\n"
				+ "MTIzMTIzNTk1OVowgYgxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpOZXcgSmVyc2V5\n"
				+ "MRQwEgYDVQQHEwtKZXJzZXkgQ2l0eTEeMBwGA1UEChMVVGhlIFVTRVJUUlVTVCBO\n"
				+ "ZXR3b3JrMS4wLAYDVQQDEyVVU0VSVHJ1c3QgRUNDIENlcnRpZmljYXRpb24gQXV0\n"
				+ "aG9yaXR5MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEGqxUWqn5aCPnetUkb1PGWthL\n"
				+ "q8bVttHmc3Gu3ZzWDGH926CJA7gFFOxXzu5dP+Ihs8731Ip54KODfi2X0GHE8Znc\n"
				+ "JZFjq38wo7Rw4sehM5zzvy5cU7Ffs30yf4o043l5o4HyMIHvMB8GA1UdIwQYMBaA\n"
				+ "FKARCiM+lvEH7OKvKe+CpX/QMKS0MB0GA1UdDgQWBBQ64QmG1M8ZwpZ2dEl23OA1\n"
				+ "xmNjmjAOBgNVHQ8BAf8EBAMCAYYwDwYDVR0TAQH/BAUwAwEB/zARBgNVHSAECjAI\n"
				+ "MAYGBFUdIAAwQwYDVR0fBDwwOjA4oDagNIYyaHR0cDovL2NybC5jb21vZG9jYS5j\n"
				+ "b20vQUFBQ2VydGlmaWNhdGVTZXJ2aWNlcy5jcmwwNAYIKwYBBQUHAQEEKDAmMCQG\n"
				+ "CCsGAQUFBzABhhhodHRwOi8vb2NzcC5jb21vZG9jYS5jb20wDQYJKoZIhvcNAQEM\n"
				+ "BQADggEBABns652JLCALBIAdGN5CmXKZFjK9Dpx1WywV4ilAbe7/ctvbq5AfjJXy\n"
				+ "ij0IckKJUAfiORVsAYfZFhr1wHUrxeZWEQff2Ji8fJ8ZOd+LygBkc7xGEJuTI42+\n"
				+ "FsMuCIKchjN0djsoTI0DQoWz4rIjQtUfenVqGtF8qmchxDM6OW1TyaLtYiKou+JV\n"
				+ "bJlsQ2uRl9EMC5MCHdK8aXdJ5htN978UeAOwproLtOGFfy/cQjutdAFI3tZs4RmY\n"
				+ "CV4Ks2dH/hzg1cEo70qLRDEmBDeNiXQ2Lu+lIg+DdEmSx/cQwgwp+7e9un/jX9Wf\n"
				+ "8qn0dNW44bOwgeThpWOjzOoEeJBuv/c=\n"
				+ "-----END CERTIFICATE-----\n"
				+ "";
		var certs = CertificateParser.parse(input);
		System.out.println(certs.size());
		for (var cert : certs) {
			System.out.println(cert.getType());
		}
		for (var certStore : CertStoreService.get().streamCertStores()) {
			System.out.println(certStore.getSans());
			System.out.println(certStore.getIssuerData());
			System.out.println(certStore.hash().encodeBase64());
			System.out.println(certStore.getKey().getValue().encodeUtf8());
			System.out.println(certStore.getCert().getValue().encodeUtf8());
		}
	}

}
