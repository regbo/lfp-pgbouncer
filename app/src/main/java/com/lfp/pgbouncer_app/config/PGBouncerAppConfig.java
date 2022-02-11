package com.lfp.pgbouncer_app.config;

import org.aeonbits.owner.Config;

public interface PGBouncerAppConfig extends Config {

	@DefaultValue("ADDRESS")
	String addressEnvironmentVariableName();

	@DefaultValue("STORAGE_KEY_PREFIX_REFRESH_BEFORE")
	String refreshBeforeEnvironmentVariableName();

	@DefaultValue("STORAGE_")
	String storageEnvironmentVariablePrefix();

}
