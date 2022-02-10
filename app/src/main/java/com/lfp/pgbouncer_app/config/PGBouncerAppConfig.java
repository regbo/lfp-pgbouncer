package com.lfp.pgbouncer_app.config;

import org.aeonbits.owner.Config;

public interface PGBouncerAppConfig extends Config {

	@DefaultValue("STORAGE_KEY_PREFIX")
	String STORAGE_MODULE_NAME();
	
	@DefaultValue("STORAGE_URI")
	String STORAGE_URI_NAME();
	
	@DefaultValue("STORAGE_PASSWORD")
	String STORAGE_PASSWORD();
	
	@DefaultValue("STORAGE_KEY_PREFIX")
	String STORAGE_KEY_PREFIX_NAME();

	@DefaultValue("STORAGE_DB")
	String STORAGE_DB_NAME();
}
