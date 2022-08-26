package com.lfp.pgbouncer_app.cert;

import java.util.Date;
import java.util.Map;
import java.util.Optional;

import org.joda.beans.Bean;
import org.joda.beans.BeanDefinition;
import org.joda.beans.JodaBeanUtils;
import org.joda.beans.MetaProperty;
import org.joda.beans.PropertyDefinition;
import org.joda.beans.impl.direct.DirectMetaProperty;
import org.joda.beans.impl.direct.DirectMetaPropertyMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lfp.joe.beans.joda.JodaBeans;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.crypto.Hashable;
import com.lfp.pgbouncer_app.storage.StorageReader;

import at.favre.lib.bytes.Bytes;
import one.util.streamex.StreamEx;

@BeanDefinition(hierarchy = "immutable")
public class CertStore extends CertGroup implements Hashable {

	@PropertyDefinition(validate = "notNull")
	private final StorageReader properties;

	@PropertyDefinition(validate = "notNull")
	private final StorageReader key;

	@PropertyDefinition(validate = "notNull")
	private final StorageReader cert;

	@Override
	public Bytes hash() {
		return Utils.Crypto.hashMD5(getService(), getDomain(), getProperties(), getKey(), getCert());
	}

	public Optional<Date> getModified() {
		var mpStream = StreamEx.of(meta().properties, meta().key(), meta().cert());
		var dateStream = mpStream.map(v -> v.get(this)).nonNull().mapPartial(v -> v.getModified());
		dateStream = dateStream.sortedBy(v -> -1 * v.getTime());
		return dateStream.findFirst();
	}

	private transient JsonElement _propertiesJson;

	protected JsonElement getPropertiesJson() {
		if (_propertiesJson == null)
			synchronized (this) {
				if (_propertiesJson == null)
					_propertiesJson = Serials.Gsons.fromBytes(getProperties().getValue(), JsonElement.class);
			}
		return _propertiesJson;
	}

	protected StreamEx<JsonElement> streamPropertyValues(Object... keys) {
		var je = Serials.Gsons.tryGet(getPropertiesJson(), keys).orElse(null);
		if (je == null)
			return StreamEx.empty();
		if (je.isJsonArray())
			return Utils.Lots.stream(je.getAsJsonArray());
		return StreamEx.of(je);
	}

	public Optional<String> getSans() {
		return streamPropertyValues("sans").mapPartial(Serials.Gsons::tryGetAsString).filter(Utils.Strings::isNotBlank)
				.findFirst();
	}

	public Optional<JsonObject> getIssuerData() {
		return streamPropertyValues("issuer_data").mapPartial(Serials.Gsons::tryGetAsJsonObject)
				.filter(v -> v.size() > 0).findFirst();
	}

	public static void main(String[] args) {
		JodaBeans.updateCode();
	}

	// ------------------------- AUTOGENERATED START -------------------------
	/// CLOVER:OFF
	/**
	 * The meta-bean for {@code CertStore}.
	 * 
	 * @return the meta-bean, not null
	 */
	public static CertStore.Meta meta() {
		return CertStore.Meta.INSTANCE;
	}

	static {
		JodaBeanUtils.registerMetaBean(CertStore.Meta.INSTANCE);
	}

	/**
	 * Returns a builder used to create an instance of the bean.
	 * 
	 * @return the builder, not null
	 */
	public static CertStore.Builder builder() {
		return new CertStore.Builder();
	}

	/**
	 * Restricted constructor.
	 * 
	 * @param builder the builder to copy from, not null
	 */
	protected CertStore(CertStore.Builder builder) {
		super(builder);
		JodaBeanUtils.notNull(builder.properties, "properties");
		JodaBeanUtils.notNull(builder.key, "key");
		JodaBeanUtils.notNull(builder.cert, "cert");
		this.properties = builder.properties;
		this.key = builder.key;
		this.cert = builder.cert;
	}

	@Override
	public CertStore.Meta metaBean() {
		return CertStore.Meta.INSTANCE;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the properties.
	 * 
	 * @return the value of the property, not null
	 */
	public StorageReader getProperties() {
		return properties;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the key.
	 * 
	 * @return the value of the property, not null
	 */
	public StorageReader getKey() {
		return key;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the cert.
	 * 
	 * @return the value of the property, not null
	 */
	public StorageReader getCert() {
		return cert;
	}

	// -----------------------------------------------------------------------
	/**
	 * Returns a builder that allows this bean to be mutated.
	 * 
	 * @return the mutable builder, not null
	 */
	@Override
	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		if (obj != null && obj.getClass() == this.getClass()) {
			CertStore other = (CertStore) obj;
			return JodaBeanUtils.equal(properties, other.properties) && JodaBeanUtils.equal(key, other.key)
					&& JodaBeanUtils.equal(cert, other.cert) && super.equals(obj);
		}
		return false;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = hash * 31 + JodaBeanUtils.hashCode(properties);
		hash = hash * 31 + JodaBeanUtils.hashCode(key);
		hash = hash * 31 + JodaBeanUtils.hashCode(cert);
		return hash ^ super.hashCode();
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder(128);
		buf.append("CertStore{");
		int len = buf.length();
		toString(buf);
		if (buf.length() > len) {
			buf.setLength(buf.length() - 2);
		}
		buf.append('}');
		return buf.toString();
	}

	@Override
	protected void toString(StringBuilder buf) {
		super.toString(buf);
		buf.append("properties").append('=').append(JodaBeanUtils.toString(properties)).append(',').append(' ');
		buf.append("key").append('=').append(JodaBeanUtils.toString(key)).append(',').append(' ');
		buf.append("cert").append('=').append(JodaBeanUtils.toString(cert)).append(',').append(' ');
	}

	// -----------------------------------------------------------------------
	/**
	 * The meta-bean for {@code CertStore}.
	 */
	public static class Meta extends CertGroup.Meta {
		/**
		 * The singleton instance of the meta-bean.
		 */
		static final Meta INSTANCE = new Meta();

		/**
		 * The meta-property for the {@code properties} property.
		 */
		private final MetaProperty<StorageReader> properties = DirectMetaProperty.ofImmutable(this, "properties",
				CertStore.class, StorageReader.class);
		/**
		 * The meta-property for the {@code key} property.
		 */
		private final MetaProperty<StorageReader> key = DirectMetaProperty.ofImmutable(this, "key", CertStore.class,
				StorageReader.class);
		/**
		 * The meta-property for the {@code cert} property.
		 */
		private final MetaProperty<StorageReader> cert = DirectMetaProperty.ofImmutable(this, "cert", CertStore.class,
				StorageReader.class);
		/**
		 * The meta-properties.
		 */
		private final Map<String, MetaProperty<?>> metaPropertyMap$ = new DirectMetaPropertyMap(this,
				(DirectMetaPropertyMap) super.metaPropertyMap(), "properties", "key", "cert");

		/**
		 * Restricted constructor.
		 */
		protected Meta() {
		}

		@Override
		protected MetaProperty<?> metaPropertyGet(String propertyName) {
			switch (propertyName.hashCode()) {
			case -926053069: // properties
				return properties;
			case 106079: // key
				return key;
			case 3050020: // cert
				return cert;
			}
			return super.metaPropertyGet(propertyName);
		}

		@Override
		public CertStore.Builder builder() {
			return new CertStore.Builder();
		}

		@Override
		public Class<? extends CertStore> beanType() {
			return CertStore.class;
		}

		@Override
		public Map<String, MetaProperty<?>> metaPropertyMap() {
			return metaPropertyMap$;
		}

		// -----------------------------------------------------------------------
		/**
		 * The meta-property for the {@code properties} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<StorageReader> properties() {
			return properties;
		}

		/**
		 * The meta-property for the {@code key} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<StorageReader> key() {
			return key;
		}

		/**
		 * The meta-property for the {@code cert} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<StorageReader> cert() {
			return cert;
		}

		// -----------------------------------------------------------------------
		@Override
		protected Object propertyGet(Bean bean, String propertyName, boolean quiet) {
			switch (propertyName.hashCode()) {
			case -926053069: // properties
				return ((CertStore) bean).getProperties();
			case 106079: // key
				return ((CertStore) bean).getKey();
			case 3050020: // cert
				return ((CertStore) bean).getCert();
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
	 * The bean-builder for {@code CertStore}.
	 */
	public static class Builder extends CertGroup.Builder {

		private StorageReader properties;
		private StorageReader key;
		private StorageReader cert;

		/**
		 * Restricted constructor.
		 */
		protected Builder() {
		}

		/**
		 * Restricted copy constructor.
		 * 
		 * @param beanToCopy the bean to copy from, not null
		 */
		protected Builder(CertStore beanToCopy) {
			super(beanToCopy);
			this.properties = beanToCopy.getProperties();
			this.key = beanToCopy.getKey();
			this.cert = beanToCopy.getCert();
		}

		// -----------------------------------------------------------------------
		@Override
		public Object get(String propertyName) {
			switch (propertyName.hashCode()) {
			case -926053069: // properties
				return properties;
			case 106079: // key
				return key;
			case 3050020: // cert
				return cert;
			default:
				return super.get(propertyName);
			}
		}

		@Override
		public Builder set(String propertyName, Object newValue) {
			switch (propertyName.hashCode()) {
			case -926053069: // properties
				this.properties = (StorageReader) newValue;
				break;
			case 106079: // key
				this.key = (StorageReader) newValue;
				break;
			case 3050020: // cert
				this.cert = (StorageReader) newValue;
				break;
			default:
				super.set(propertyName, newValue);
				break;
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
		public CertStore build() {
			return new CertStore(this);
		}

		// -----------------------------------------------------------------------
		/**
		 * Sets the properties.
		 * 
		 * @param properties the new value, not null
		 * @return this, for chaining, not null
		 */
		public Builder properties(StorageReader properties) {
			JodaBeanUtils.notNull(properties, "properties");
			this.properties = properties;
			return this;
		}

		/**
		 * Sets the key.
		 * 
		 * @param key the new value, not null
		 * @return this, for chaining, not null
		 */
		public Builder key(StorageReader key) {
			JodaBeanUtils.notNull(key, "key");
			this.key = key;
			return this;
		}

		/**
		 * Sets the cert.
		 * 
		 * @param cert the new value, not null
		 * @return this, for chaining, not null
		 */
		public Builder cert(StorageReader cert) {
			JodaBeanUtils.notNull(cert, "cert");
			this.cert = cert;
			return this;
		}

		// -----------------------------------------------------------------------
		@Override
		public String toString() {
			StringBuilder buf = new StringBuilder(128);
			buf.append("CertStore.Builder{");
			int len = buf.length();
			toString(buf);
			if (buf.length() > len) {
				buf.setLength(buf.length() - 2);
			}
			buf.append('}');
			return buf.toString();
		}

		@Override
		protected void toString(StringBuilder buf) {
			super.toString(buf);
			buf.append("properties").append('=').append(JodaBeanUtils.toString(properties)).append(',').append(' ');
			buf.append("key").append('=').append(JodaBeanUtils.toString(key)).append(',').append(' ');
			buf.append("cert").append('=').append(JodaBeanUtils.toString(cert)).append(',').append(' ');
		}

	}

	/// CLOVER:ON
	// -------------------------- AUTOGENERATED END --------------------------
}
