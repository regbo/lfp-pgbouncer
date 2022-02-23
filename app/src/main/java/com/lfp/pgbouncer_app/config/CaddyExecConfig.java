package com.lfp.pgbouncer_app.config;

import java.io.File;

import org.aeonbits.owner.Config;

import com.lfp.joe.core.properties.converter.FileConverter;

public interface CaddyExecConfig extends Config {

	@DefaultValue("/data/caddy")
	@ConverterClass(FileConverter.class)
	File storageDirectory();

	@DefaultValue("/usr/local/bin/caddy")
	@ConverterClass(FileConverter.class)
	File caddyExec();
	
	@DefaultValue("/ping")
	String pingPath();
}
