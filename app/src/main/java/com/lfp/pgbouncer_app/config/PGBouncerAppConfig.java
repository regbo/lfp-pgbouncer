package com.lfp.pgbouncer_app.config;

import java.net.URI;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.aeonbits.owner.Config;

import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.core.properties.code.PrintOptions;
import com.lfp.joe.core.properties.converter.MultimapConverter;
import com.lfp.joe.core.properties.converter.URIConverter;
import com.lfp.joe.properties.converter.DateConverter;
import com.lfp.joe.properties.converter.DurationConverter;

public interface PGBouncerAppConfig extends Config {

	@ConverterClass(URIConverter.class)
	URI storageRedisURI();

	String storageKeyPrefix();

	@ConverterClass(DateConverter.class)
	Date storageKeyPrefixRefreshBefore();

	String storageKeyPassword();

	String storageAESKey();

	@DefaultValue("15s")
	@ConverterClass(DurationConverter.class)
	Duration storageTimeout();

	@DefaultValue("false")
	boolean dynamicDnsEnabled();

	@DefaultValue("60s")
	@ConverterClass(DurationConverter.class)
	Duration dynamicDnsCheckInterval();

	String dynamicDnsProvider();

	List<String> dynamicDnsDomains();

	List<String> dynamicDnsIPSources();

	@DefaultValue("zerossl")
	String tlsIssuerModule();

	String tlsIssuerContactEmail();

	String tlsIssuerAPIKey();

	String tlsIssuerDnsProvider();

	List<String> authenticatorJwtIssuerHosts();

	@ConverterClass(URIConverter.class)
	List<URI> authenticatorJwtIssuerURIs();

	@ConverterClass(MultimapConverter.class)
	Map<String, List<String>> authenticatorJwtRequiredClaims();

	public static void main(String[] args) {
		Configs.printProperties(PrintOptions.propertiesBuilder().withSkipPopulated(true).build());
		Configs.printProperties(PrintOptions.jsonBuilder().withSkipPopulated(true).build());
	}

}
