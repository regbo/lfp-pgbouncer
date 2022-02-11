package com.lfp.pgbouncer_app;

import java.util.Date;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.joda.beans.Bean;
import org.joda.beans.BeanDefinition;
import org.joda.beans.ImmutableBean;
import org.joda.beans.ImmutableDefaults;
import org.joda.beans.JodaBeanUtils;
import org.joda.beans.MetaProperty;
import org.joda.beans.Property;
import org.joda.beans.PropertyDefinition;
import org.joda.beans.impl.direct.DirectFieldsBeanBuilder;
import org.joda.beans.impl.direct.DirectMetaBean;
import org.joda.beans.impl.direct.DirectMetaProperty;
import org.joda.beans.impl.direct.DirectMetaPropertyMap;
import org.redisson.client.codec.ByteArrayCodec;

import com.github.throwable.beanref.BeanRef;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.re2j.Pattern;
import com.lfp.data.redisson.client.RedissonClientLFP;
import com.lfp.joe.beans.JodaBeans;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.crypto.Hashable;
import com.lfp.joe.utils.time.TimeParser;
import com.lfp.pgbouncer_app.StorageCrypto.DecryptException;

import at.favre.lib.bytes.Bytes;
import one.util.streamex.StreamEx;

@BeanDefinition
public class StorageReader implements ImmutableBean, Hashable {
	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	@PropertyDefinition(validate = "notNull")
	private final RedissonClientLFP client;

	@PropertyDefinition(get = "optional")
	private final StorageCrypto storageCrypto;

	@PropertyDefinition(validate = "notNull")
	private final String keyPrefix;

	@PropertyDefinition(validate = "notEmpty")
	private final String key;

	@PropertyDefinition(validate = "notNull")
	private final BiFunction<StorageReader, DecryptException, byte[]> decryptErrorHandler;

	@Override
	public Bytes hash() {
		return Utils.Crypto.hashMD5(getKey(), getValue());
	}

	private transient Bytes _redisValue;

	protected Bytes getRedisValue() {
		if (_redisValue == null)
			synchronized (this) {
				if (_redisValue == null)
					_redisValue = parseRedisValue();
			}
		return _redisValue;
	}

	private Bytes parseRedisValue() {
		byte[] redisValueBarr = (byte[]) client.getBucket(getKey(), ByteArrayCodec.INSTANCE).get();
		var crypto = getStorageCrypto().orElse(null);
		if (crypto != null)
			try {
				redisValueBarr = crypto.decrypt(redisValueBarr);
			} catch (DecryptException e) {
				redisValueBarr = Optional.ofNullable(getDecryptErrorHandler().apply(this, e))
						.orElseGet(Utils.Bits::emptyByteArray);
			}
		return Utils.Bits.from(redisValueBarr);
	}

	@SuppressWarnings("deprecation")
	protected JsonElement getRedisValueJson() {
		var json = getRedisValue().encodeUtf8();
		if (Utils.Strings.isBlank(json))
			return JsonNull.INSTANCE;
		json = "{" + Utils.Strings.substringAfter(json, "{");
		var je = Serials.Gsons.getJsonParser().parse(json);
		return je;
	}

	private transient Bytes _value;

	public Bytes getValue() {
		if (_value == null)
			synchronized (this) {
				if (_value == null)
					_value = parseValue();
			}
		return _value;
	}

	private Bytes parseValue() {
		var je = getRedisValueJson();
		return Serials.Gsons.tryGetAsString(je, "value").map(Utils.Bits::parseBase64).orElse(Utils.Bits.empty());
	}

	private transient Optional<Date> _modified;

	public Optional<Date> getModified() {
		if (_modified == null)
			synchronized (this) {
				if (_modified == null)
					_modified = parseModified();
			}
		return _modified;
	}

	private Optional<Date> parseModified() {
		var je = getRedisValueJson();
		var modifiedStr = Serials.Gsons.tryGetAsString(je, "modified").orElse(null);
		return TimeParser.tryParseDate(modifiedStr);
	}

	@Override
	public String toString() {
		var tsb = new ToStringBuilder(this);
		tsb.append(meta().key().name(), getKey());
		if (_redisValue == null) {
			var message = "[NOT LOADED]";
			tsb.append(BeanRef.$(StorageReader::getValue).getPath(), message);
			tsb.append(BeanRef.$(StorageReader::getModified).getPath(), message);
		} else {
			tsb.append(BeanRef.$(StorageReader::getValue).getPath(), "[LOADED]");
			tsb.append(BeanRef.$(StorageReader::getModified).getPath(), getModified().orElse(null));
		}
		tsb.append(meta().storageCrypto.name(), getStorageCrypto().isPresent());
		return tsb.toString();
	}

	public StreamEx<String> streamPath(String... skipDirectories) {
		var key = this.getKey();
		key = Utils.Strings.substringAfter(key, getKeyPrefix());
		key = Utils.Strings.stripStart(key, "/");
		var pathParts = key.split(Pattern.quote("/"));
		Integer skip = 0;
		for (int i = 0; skipDirectories != null && i < skipDirectories.length; i++) {
			if (i >= pathParts.length)
				return StreamEx.empty();
			if (!Utils.Strings.equals(skipDirectories[i], pathParts[i]))
				return StreamEx.empty();
			skip++;
		}
		var pathStream = Utils.Lots.stream(pathParts).skip(skip);
		return pathStream;
	}

	public Optional<String> getFileName() {
		return Utils.Lots.last(streamPath()).filter(Utils.Strings::isNotBlank);
	}

	public Optional<String> getName() {
		return getFileName().map(v -> {
			return Utils.Strings.substringBeforeLast(v, ".");
		}).filter(Utils.Strings::isNotBlank);
	}

	public Optional<String> getExtension() {
		return getFileName().map(v -> {
			return Utils.Strings.substringAfterLast(v, ".");
		}).filter(Utils.Strings::isNotBlank);
	}

	public static String createDecryptErrorMessage(StorageReader storageReader, DecryptException error) {
		return String.format("failed to decrypt key value. key:%s error:%s", storageReader.getKey(),
				error.getMessage());
	}

	@ImmutableDefaults
	private static void applyDefaults(Builder builder) {
		builder.decryptErrorHandler((sr, t) -> {
			logger.warn(createDecryptErrorMessage(sr, t));
			return null;
		});
	}

	public static void main(String[] args) {
		JodaBeans.updateCode();
	}

	// ------------------------- AUTOGENERATED START -------------------------
	/// CLOVER:OFF
	/**
	 * The meta-bean for {@code StorageReader}.
	 * 
	 * @return the meta-bean, not null
	 */
	public static StorageReader.Meta meta() {
		return StorageReader.Meta.INSTANCE;
	}

	static {
		JodaBeanUtils.registerMetaBean(StorageReader.Meta.INSTANCE);
	}

	/**
	 * Returns a builder used to create an instance of the bean.
	 * 
	 * @return the builder, not null
	 */
	public static StorageReader.Builder builder() {
		return new StorageReader.Builder();
	}

	/**
	 * Restricted constructor.
	 * 
	 * @param builder the builder to copy from, not null
	 */
	protected StorageReader(StorageReader.Builder builder) {
		JodaBeanUtils.notNull(builder.client, "client");
		JodaBeanUtils.notNull(builder.keyPrefix, "keyPrefix");
		JodaBeanUtils.notEmpty(builder.key, "key");
		JodaBeanUtils.notNull(builder.decryptErrorHandler, "decryptErrorHandler");
		this.client = builder.client;
		this.storageCrypto = builder.storageCrypto;
		this.keyPrefix = builder.keyPrefix;
		this.key = builder.key;
		this.decryptErrorHandler = builder.decryptErrorHandler;
	}

	@Override
	public StorageReader.Meta metaBean() {
		return StorageReader.Meta.INSTANCE;
	}

	@Override
	public <R> Property<R> property(String propertyName) {
		return metaBean().<R>metaProperty(propertyName).createProperty(this);
	}

	@Override
	public Set<String> propertyNames() {
		return metaBean().metaPropertyMap().keySet();
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the client.
	 * 
	 * @return the value of the property, not null
	 */
	public RedissonClientLFP getClient() {
		return client;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the storageCrypto.
	 * 
	 * @return the optional value of the property, not null
	 */
	public Optional<StorageCrypto> getStorageCrypto() {
		return Optional.ofNullable(storageCrypto);
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the keyPrefix.
	 * 
	 * @return the value of the property, not null
	 */
	public String getKeyPrefix() {
		return keyPrefix;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the key.
	 * 
	 * @return the value of the property, not empty
	 */
	public String getKey() {
		return key;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the decryptErrorHandler.
	 * 
	 * @return the value of the property, not null
	 */
	public BiFunction<StorageReader, DecryptException, byte[]> getDecryptErrorHandler() {
		return decryptErrorHandler;
	}

	// -----------------------------------------------------------------------
	/**
	 * Returns a builder that allows this bean to be mutated.
	 * 
	 * @return the mutable builder, not null
	 */
	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		if (obj != null && obj.getClass() == this.getClass()) {
			StorageReader other = (StorageReader) obj;
			return JodaBeanUtils.equal(client, other.client) && JodaBeanUtils.equal(storageCrypto, other.storageCrypto)
					&& JodaBeanUtils.equal(keyPrefix, other.keyPrefix) && JodaBeanUtils.equal(key, other.key)
					&& JodaBeanUtils.equal(decryptErrorHandler, other.decryptErrorHandler);
		}
		return false;
	}

	@Override
	public int hashCode() {
		int hash = getClass().hashCode();
		hash = hash * 31 + JodaBeanUtils.hashCode(client);
		hash = hash * 31 + JodaBeanUtils.hashCode(storageCrypto);
		hash = hash * 31 + JodaBeanUtils.hashCode(keyPrefix);
		hash = hash * 31 + JodaBeanUtils.hashCode(key);
		hash = hash * 31 + JodaBeanUtils.hashCode(decryptErrorHandler);
		return hash;
	}

	// -----------------------------------------------------------------------
	/**
	 * The meta-bean for {@code StorageReader}.
	 */
	public static class Meta extends DirectMetaBean {
		/**
		 * The singleton instance of the meta-bean.
		 */
		static final Meta INSTANCE = new Meta();

		/**
		 * The meta-property for the {@code client} property.
		 */
		private final MetaProperty<RedissonClientLFP> client = DirectMetaProperty.ofImmutable(this, "client",
				StorageReader.class, RedissonClientLFP.class);
		/**
		 * The meta-property for the {@code storageCrypto} property.
		 */
		private final MetaProperty<StorageCrypto> storageCrypto = DirectMetaProperty.ofImmutable(this, "storageCrypto",
				StorageReader.class, StorageCrypto.class);
		/**
		 * The meta-property for the {@code keyPrefix} property.
		 */
		private final MetaProperty<String> keyPrefix = DirectMetaProperty.ofImmutable(this, "keyPrefix",
				StorageReader.class, String.class);
		/**
		 * The meta-property for the {@code key} property.
		 */
		private final MetaProperty<String> key = DirectMetaProperty.ofImmutable(this, "key", StorageReader.class,
				String.class);
		/**
		 * The meta-property for the {@code decryptErrorHandler} property.
		 */
		@SuppressWarnings({ "unchecked", "rawtypes" })
		private final MetaProperty<BiFunction<StorageReader, DecryptException, byte[]>> decryptErrorHandler = DirectMetaProperty
				.ofImmutable(this, "decryptErrorHandler", StorageReader.class, (Class) BiFunction.class);
		/**
		 * The meta-properties.
		 */
		private final Map<String, MetaProperty<?>> metaPropertyMap$ = new DirectMetaPropertyMap(this, null, "client",
				"storageCrypto", "keyPrefix", "key", "decryptErrorHandler");

		/**
		 * Restricted constructor.
		 */
		protected Meta() {
		}

		@Override
		protected MetaProperty<?> metaPropertyGet(String propertyName) {
			switch (propertyName.hashCode()) {
			case -1357712437: // client
				return client;
			case 1248401180: // storageCrypto
				return storageCrypto;
			case -2076395055: // keyPrefix
				return keyPrefix;
			case 106079: // key
				return key;
			case 667027023: // decryptErrorHandler
				return decryptErrorHandler;
			}
			return super.metaPropertyGet(propertyName);
		}

		@Override
		public StorageReader.Builder builder() {
			return new StorageReader.Builder();
		}

		@Override
		public Class<? extends StorageReader> beanType() {
			return StorageReader.class;
		}

		@Override
		public Map<String, MetaProperty<?>> metaPropertyMap() {
			return metaPropertyMap$;
		}

		// -----------------------------------------------------------------------
		/**
		 * The meta-property for the {@code client} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<RedissonClientLFP> client() {
			return client;
		}

		/**
		 * The meta-property for the {@code storageCrypto} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<StorageCrypto> storageCrypto() {
			return storageCrypto;
		}

		/**
		 * The meta-property for the {@code keyPrefix} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<String> keyPrefix() {
			return keyPrefix;
		}

		/**
		 * The meta-property for the {@code key} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<String> key() {
			return key;
		}

		/**
		 * The meta-property for the {@code decryptErrorHandler} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<BiFunction<StorageReader, DecryptException, byte[]>> decryptErrorHandler() {
			return decryptErrorHandler;
		}

		// -----------------------------------------------------------------------
		@Override
		protected Object propertyGet(Bean bean, String propertyName, boolean quiet) {
			switch (propertyName.hashCode()) {
			case -1357712437: // client
				return ((StorageReader) bean).getClient();
			case 1248401180: // storageCrypto
				return ((StorageReader) bean).storageCrypto;
			case -2076395055: // keyPrefix
				return ((StorageReader) bean).getKeyPrefix();
			case 106079: // key
				return ((StorageReader) bean).getKey();
			case 667027023: // decryptErrorHandler
				return ((StorageReader) bean).getDecryptErrorHandler();
			}
			return super.propertyGet(bean, propertyName, quiet);
		}

		@Override
		protected void propertySet(Bean bean, String propertyName, Object newValue, boolean quiet) {
			metaProperty(propertyName);
			if (quiet) {
				return;
			}
			throw new UnsupportedOperationException("Property cannot be written: " + propertyName);
		}

	}

	// -----------------------------------------------------------------------
	/**
	 * The bean-builder for {@code StorageReader}.
	 */
	public static class Builder extends DirectFieldsBeanBuilder<StorageReader> {

		private RedissonClientLFP client;
		private StorageCrypto storageCrypto;
		private String keyPrefix;
		private String key;
		private BiFunction<StorageReader, DecryptException, byte[]> decryptErrorHandler;

		/**
		 * Restricted constructor.
		 */
		protected Builder() {
			applyDefaults(this);
		}

		/**
		 * Restricted copy constructor.
		 * 
		 * @param beanToCopy the bean to copy from, not null
		 */
		protected Builder(StorageReader beanToCopy) {
			this.client = beanToCopy.getClient();
			this.storageCrypto = beanToCopy.storageCrypto;
			this.keyPrefix = beanToCopy.getKeyPrefix();
			this.key = beanToCopy.getKey();
			this.decryptErrorHandler = beanToCopy.getDecryptErrorHandler();
		}

		// -----------------------------------------------------------------------
		@Override
		public Object get(String propertyName) {
			switch (propertyName.hashCode()) {
			case -1357712437: // client
				return client;
			case 1248401180: // storageCrypto
				return storageCrypto;
			case -2076395055: // keyPrefix
				return keyPrefix;
			case 106079: // key
				return key;
			case 667027023: // decryptErrorHandler
				return decryptErrorHandler;
			default:
				throw new NoSuchElementException("Unknown property: " + propertyName);
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		public Builder set(String propertyName, Object newValue) {
			switch (propertyName.hashCode()) {
			case -1357712437: // client
				this.client = (RedissonClientLFP) newValue;
				break;
			case 1248401180: // storageCrypto
				this.storageCrypto = (StorageCrypto) newValue;
				break;
			case -2076395055: // keyPrefix
				this.keyPrefix = (String) newValue;
				break;
			case 106079: // key
				this.key = (String) newValue;
				break;
			case 667027023: // decryptErrorHandler
				this.decryptErrorHandler = (BiFunction<StorageReader, DecryptException, byte[]>) newValue;
				break;
			default:
				throw new NoSuchElementException("Unknown property: " + propertyName);
			}
			return this;
		}

		@Override
		public Builder set(MetaProperty<?> property, Object value) {
			super.set(property, value);
			return this;
		}

		/**
		 * @deprecated Use Joda-Convert in application code
		 */
		@Override
		@Deprecated
		public Builder setString(String propertyName, String value) {
			setString(meta().metaProperty(propertyName), value);
			return this;
		}

		/**
		 * @deprecated Use Joda-Convert in application code
		 */
		@Override
		@Deprecated
		public Builder setString(MetaProperty<?> property, String value) {
			super.setString(property, value);
			return this;
		}

		/**
		 * @deprecated Loop in application code
		 */
		@Override
		@Deprecated
		public Builder setAll(Map<String, ? extends Object> propertyValueMap) {
			super.setAll(propertyValueMap);
			return this;
		}

		@Override
		public StorageReader build() {
			return new StorageReader(this);
		}

		// -----------------------------------------------------------------------
		/**
		 * Sets the client.
		 * 
		 * @param client the new value, not null
		 * @return this, for chaining, not null
		 */
		public Builder client(RedissonClientLFP client) {
			JodaBeanUtils.notNull(client, "client");
			this.client = client;
			return this;
		}

		/**
		 * Sets the storageCrypto.
		 * 
		 * @param storageCrypto the new value
		 * @return this, for chaining, not null
		 */
		public Builder storageCrypto(StorageCrypto storageCrypto) {
			this.storageCrypto = storageCrypto;
			return this;
		}

		/**
		 * Sets the keyPrefix.
		 * 
		 * @param keyPrefix the new value, not null
		 * @return this, for chaining, not null
		 */
		public Builder keyPrefix(String keyPrefix) {
			JodaBeanUtils.notNull(keyPrefix, "keyPrefix");
			this.keyPrefix = keyPrefix;
			return this;
		}

		/**
		 * Sets the key.
		 * 
		 * @param key the new value, not empty
		 * @return this, for chaining, not null
		 */
		public Builder key(String key) {
			JodaBeanUtils.notEmpty(key, "key");
			this.key = key;
			return this;
		}

		/**
		 * Sets the decryptErrorHandler.
		 * 
		 * @param decryptErrorHandler the new value, not null
		 * @return this, for chaining, not null
		 */
		public Builder decryptErrorHandler(BiFunction<StorageReader, DecryptException, byte[]> decryptErrorHandler) {
			JodaBeanUtils.notNull(decryptErrorHandler, "decryptErrorHandler");
			this.decryptErrorHandler = decryptErrorHandler;
			return this;
		}

		// -----------------------------------------------------------------------
		@Override
		public String toString() {
			StringBuilder buf = new StringBuilder(192);
			buf.append("StorageReader.Builder{");
			int len = buf.length();
			toString(buf);
			if (buf.length() > len) {
				buf.setLength(buf.length() - 2);
			}
			buf.append('}');
			return buf.toString();
		}

		protected void toString(StringBuilder buf) {
			buf.append("client").append('=').append(JodaBeanUtils.toString(client)).append(',').append(' ');
			buf.append("storageCrypto").append('=').append(JodaBeanUtils.toString(storageCrypto)).append(',')
					.append(' ');
			buf.append("keyPrefix").append('=').append(JodaBeanUtils.toString(keyPrefix)).append(',').append(' ');
			buf.append("key").append('=').append(JodaBeanUtils.toString(key)).append(',').append(' ');
			buf.append("decryptErrorHandler").append('=').append(JodaBeanUtils.toString(decryptErrorHandler))
					.append(',').append(' ');
		}

	}

	/// CLOVER:ON
	// -------------------------- AUTOGENERATED END --------------------------
}
