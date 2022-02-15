package com.lfp.pgbouncer.service.config;

import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.core.properties.code.PrintOptions;
import com.lfp.joe.net.http.ServiceConfig;

public interface PGBouncerServiceConfig extends ServiceConfig {

	public static void main(String[] args) {
		Configs.printProperties(PrintOptions.propertiesBuilder().withSkipPopulated(true).build());
		Configs.printProperties(PrintOptions.jsonBuilder().withSkipPopulated(true).build());
	}
}
