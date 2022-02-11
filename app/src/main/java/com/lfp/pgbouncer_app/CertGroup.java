package com.lfp.pgbouncer_app;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.joda.beans.Bean;
import org.joda.beans.BeanDefinition;
import org.joda.beans.ImmutableBean;
import org.joda.beans.JodaBeanUtils;
import org.joda.beans.MetaProperty;
import org.joda.beans.Property;
import org.joda.beans.PropertyDefinition;
import org.joda.beans.impl.direct.DirectFieldsBeanBuilder;
import org.joda.beans.impl.direct.DirectMetaBean;
import org.joda.beans.impl.direct.DirectMetaProperty;
import org.joda.beans.impl.direct.DirectMetaPropertyMap;

import com.lfp.joe.beans.JodaBeans;

import one.util.streamex.StreamEx;

@BeanDefinition
public class CertGroup implements ImmutableBean {

	private static final String CERTIFICATES_FOLDER = "certificates";

	@PropertyDefinition(validate = "notEmpty")
	private final String service;

	@PropertyDefinition(validate = "notEmpty")
	private final String domain;

	@PropertyDefinition(validate = "notEmpty")
	private final String name;

	public static Optional<CertGroup> tryBuild(StorageReader storageReader) {
		if (storageReader == null)
			return Optional.empty();
		var serviceOp = storageReader.streamPath(CERTIFICATES_FOLDER).findFirst();
		var domainOp = storageReader.streamPath(CERTIFICATES_FOLDER, serviceOp.orElse(null)).findFirst();
		var nameOp = storageReader.getName();
		if (StreamEx.of(serviceOp, domainOp, nameOp).anyMatch(Optional::isEmpty))
			return Optional.empty();
		var certGroup = CertGroup.builder().service(serviceOp.get()).domain(domainOp.get()).name(nameOp.get()).build();
		return Optional.of(certGroup);

	}

	public static void main(String[] args) {
		JodaBeans.updateCode();
	}

	// ------------------------- AUTOGENERATED START -------------------------
	/// CLOVER:OFF
	/**
	 * The meta-bean for {@code CertGroup}.
	 * 
	 * @return the meta-bean, not null
	 */
	public static CertGroup.Meta meta() {
		return CertGroup.Meta.INSTANCE;
	}

	static {
		JodaBeanUtils.registerMetaBean(CertGroup.Meta.INSTANCE);
	}

	/**
	 * Returns a builder used to create an instance of the bean.
	 * 
	 * @return the builder, not null
	 */
	public static CertGroup.Builder builder() {
		return new CertGroup.Builder();
	}

	/**
	 * Restricted constructor.
	 * 
	 * @param builder the builder to copy from, not null
	 */
	protected CertGroup(CertGroup.Builder builder) {
		JodaBeanUtils.notEmpty(builder.service, "service");
		JodaBeanUtils.notEmpty(builder.domain, "domain");
		JodaBeanUtils.notEmpty(builder.name, "name");
		this.service = builder.service;
		this.domain = builder.domain;
		this.name = builder.name;
	}

	@Override
	public CertGroup.Meta metaBean() {
		return CertGroup.Meta.INSTANCE;
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
	 * Gets the service.
	 * 
	 * @return the value of the property, not empty
	 */
	public String getService() {
		return service;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the domain.
	 * 
	 * @return the value of the property, not empty
	 */
	public String getDomain() {
		return domain;
	}

	// -----------------------------------------------------------------------
	/**
	 * Gets the name.
	 * 
	 * @return the value of the property, not empty
	 */
	public String getName() {
		return name;
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
			CertGroup other = (CertGroup) obj;
			return JodaBeanUtils.equal(service, other.service) && JodaBeanUtils.equal(domain, other.domain)
					&& JodaBeanUtils.equal(name, other.name);
		}
		return false;
	}

	@Override
	public int hashCode() {
		int hash = getClass().hashCode();
		hash = hash * 31 + JodaBeanUtils.hashCode(service);
		hash = hash * 31 + JodaBeanUtils.hashCode(domain);
		hash = hash * 31 + JodaBeanUtils.hashCode(name);
		return hash;
	}

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder(128);
		buf.append("CertGroup{");
		int len = buf.length();
		toString(buf);
		if (buf.length() > len) {
			buf.setLength(buf.length() - 2);
		}
		buf.append('}');
		return buf.toString();
	}

	protected void toString(StringBuilder buf) {
		buf.append("service").append('=').append(JodaBeanUtils.toString(service)).append(',').append(' ');
		buf.append("domain").append('=').append(JodaBeanUtils.toString(domain)).append(',').append(' ');
		buf.append("name").append('=').append(JodaBeanUtils.toString(name)).append(',').append(' ');
	}

	// -----------------------------------------------------------------------
	/**
	 * The meta-bean for {@code CertGroup}.
	 */
	public static class Meta extends DirectMetaBean {
		/**
		 * The singleton instance of the meta-bean.
		 */
		static final Meta INSTANCE = new Meta();

		/**
		 * The meta-property for the {@code service} property.
		 */
		private final MetaProperty<String> service = DirectMetaProperty.ofImmutable(this, "service", CertGroup.class,
				String.class);
		/**
		 * The meta-property for the {@code domain} property.
		 */
		private final MetaProperty<String> domain = DirectMetaProperty.ofImmutable(this, "domain", CertGroup.class,
				String.class);
		/**
		 * The meta-property for the {@code name} property.
		 */
		private final MetaProperty<String> name = DirectMetaProperty.ofImmutable(this, "name", CertGroup.class,
				String.class);
		/**
		 * The meta-properties.
		 */
		private final Map<String, MetaProperty<?>> metaPropertyMap$ = new DirectMetaPropertyMap(this, null, "service",
				"domain", "name");

		/**
		 * Restricted constructor.
		 */
		protected Meta() {
		}

		@Override
		protected MetaProperty<?> metaPropertyGet(String propertyName) {
			switch (propertyName.hashCode()) {
			case 1984153269: // service
				return service;
			case -1326197564: // domain
				return domain;
			case 3373707: // name
				return name;
			}
			return super.metaPropertyGet(propertyName);
		}

		@Override
		public CertGroup.Builder builder() {
			return new CertGroup.Builder();
		}

		@Override
		public Class<? extends CertGroup> beanType() {
			return CertGroup.class;
		}

		@Override
		public Map<String, MetaProperty<?>> metaPropertyMap() {
			return metaPropertyMap$;
		}

		// -----------------------------------------------------------------------
		/**
		 * The meta-property for the {@code service} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<String> service() {
			return service;
		}

		/**
		 * The meta-property for the {@code domain} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<String> domain() {
			return domain;
		}

		/**
		 * The meta-property for the {@code name} property.
		 * 
		 * @return the meta-property, not null
		 */
		public final MetaProperty<String> name() {
			return name;
		}

		// -----------------------------------------------------------------------
		@Override
		protected Object propertyGet(Bean bean, String propertyName, boolean quiet) {
			switch (propertyName.hashCode()) {
			case 1984153269: // service
				return ((CertGroup) bean).getService();
			case -1326197564: // domain
				return ((CertGroup) bean).getDomain();
			case 3373707: // name
				return ((CertGroup) bean).getName();
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
	 * The bean-builder for {@code CertGroup}.
	 */
	public static class Builder extends DirectFieldsBeanBuilder<CertGroup> {

		private String service;
		private String domain;
		private String name;

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
		protected Builder(CertGroup beanToCopy) {
			this.service = beanToCopy.getService();
			this.domain = beanToCopy.getDomain();
			this.name = beanToCopy.getName();
		}

		// -----------------------------------------------------------------------
		@Override
		public Object get(String propertyName) {
			switch (propertyName.hashCode()) {
			case 1984153269: // service
				return service;
			case -1326197564: // domain
				return domain;
			case 3373707: // name
				return name;
			default:
				throw new NoSuchElementException("Unknown property: " + propertyName);
			}
		}

		@Override
		public Builder set(String propertyName, Object newValue) {
			switch (propertyName.hashCode()) {
			case 1984153269: // service
				this.service = (String) newValue;
				break;
			case -1326197564: // domain
				this.domain = (String) newValue;
				break;
			case 3373707: // name
				this.name = (String) newValue;
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
		public CertGroup build() {
			return new CertGroup(this);
		}

		// -----------------------------------------------------------------------
		/**
		 * Sets the service.
		 * 
		 * @param service the new value, not empty
		 * @return this, for chaining, not null
		 */
		public Builder service(String service) {
			JodaBeanUtils.notEmpty(service, "service");
			this.service = service;
			return this;
		}

		/**
		 * Sets the domain.
		 * 
		 * @param domain the new value, not empty
		 * @return this, for chaining, not null
		 */
		public Builder domain(String domain) {
			JodaBeanUtils.notEmpty(domain, "domain");
			this.domain = domain;
			return this;
		}

		/**
		 * Sets the name.
		 * 
		 * @param name the new value, not empty
		 * @return this, for chaining, not null
		 */
		public Builder name(String name) {
			JodaBeanUtils.notEmpty(name, "name");
			this.name = name;
			return this;
		}

		// -----------------------------------------------------------------------
		@Override
		public String toString() {
			StringBuilder buf = new StringBuilder(128);
			buf.append("CertGroup.Builder{");
			int len = buf.length();
			toString(buf);
			if (buf.length() > len) {
				buf.setLength(buf.length() - 2);
			}
			buf.append('}');
			return buf.toString();
		}

		protected void toString(StringBuilder buf) {
			buf.append("service").append('=').append(JodaBeanUtils.toString(service)).append(',').append(' ');
			buf.append("domain").append('=').append(JodaBeanUtils.toString(domain)).append(',').append(' ');
			buf.append("name").append('=').append(JodaBeanUtils.toString(name)).append(',').append(' ');
		}

	}

	/// CLOVER:ON
	// -------------------------- AUTOGENERATED END --------------------------
}
