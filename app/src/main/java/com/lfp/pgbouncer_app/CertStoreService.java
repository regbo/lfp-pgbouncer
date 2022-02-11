package com.lfp.pgbouncer_app;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.lfp.joe.utils.Utils;

import one.util.streamex.StreamEx;

public class CertStoreService {

	private static final String CERTIFICATES_FOLDER = "certificates";

	public static void main(String[] args) {
		try (var redisServce = RedisService.get()) {
			var grouped = redisServce.streamReaders().mapToEntry(v -> Group.tryGet(v).orElse(null)).invert()
					.nonNullKeys().grouping();
			for (var group : grouped.keySet()) {
				CertStore certStore = getCertStore(group, grouped.get(group)).orElse(null);
				if (certStore == null)
					continue;
				System.out.println(certStore.getSans());
				System.out.println(certStore.getIssuerData());
				System.out.println(certStore.hash().encodeBase64());
				System.out.println(certStore.getKey().getValue().encodeUtf8());
				System.out.println(certStore.getCert().getValue().encodeUtf8());
			}
		}
	}

	private static Optional<CertStore> getCertStore(Group group, List<StorageReader> storageReaders) {
		var propertiesOp = getStorageReaderByExtension(storageReaders, "json");
		var keyOp = getStorageReaderByExtension(storageReaders, "key");
		var certOp = getStorageReaderByExtension(storageReaders, "crt");
		if (StreamEx.of(propertiesOp, keyOp, certOp).anyMatch(Optional::isEmpty))
			return Optional.empty();
		var certStore = CertStore.builder().service(group.service).domain(group.domain).properties(propertiesOp.get())
				.key(keyOp.get()).cert(certOp.get()).build();
		return Optional.of(certStore);
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

	private static class Group {

		public static Optional<Group> tryGet(StorageReader storageReader) {
			if (storageReader == null)
				return Optional.empty();
			var serviceOp = storageReader.streamPath(CERTIFICATES_FOLDER).findFirst();
			var domainOp = storageReader.streamPath(CERTIFICATES_FOLDER, serviceOp.orElse(null)).findFirst();
			var nameOp = storageReader.getName();
			if (StreamEx.of(serviceOp, domainOp, nameOp).anyMatch(Optional::isEmpty))
				return Optional.empty();
			var grouping = new Group(serviceOp.get(), domainOp.get(), nameOp.get());
			return Optional.of(grouping);
		}

		public final String service;

		public final String domain;

		public final String name;

		private Group(String service, String domain, String name) {
			super();
			this.service = service;
			this.domain = domain;
			this.name = name;
		}

		@Override
		public int hashCode() {
			return Objects.hash(domain, name, service);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Group other = (Group) obj;
			return Objects.equals(domain, other.domain) && Objects.equals(name, other.name)
					&& Objects.equals(service, other.service);
		}

		@Override
		public String toString() {
			return "Mapping [service=" + service + ", domain=" + domain + ", name=" + name + "]";
		}
	}

}
