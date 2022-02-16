package com.lfp.pgbouncer_app.config;

import java.io.File;
import java.time.Duration;
import java.util.List;

import org.aeonbits.owner.Config;

import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.core.properties.code.PrintOptions;
import com.lfp.joe.core.properties.converter.FileConverter;
import com.lfp.joe.properties.converter.DurationConverter;

public interface PGBouncerExecConfig extends Config {

	@DefaultValue("/lib/security/mypam.so")
	String pamAuthenticationModulePath();

	@DefaultValue("/opt/bitnami/pgbouncer/conf")
	@ConverterClass(FileConverter.class)
	File confDirectory();

	@DefaultValue("${confDirectory}/pgbouncer.ini")
	@ConverterClass(FileConverter.class)
	File confIni();

	@DefaultValue("${confDirectory}/client_tls_key_file.key")
	@ConverterClass(FileConverter.class)
	File clientTlsKeyFile();

	@DefaultValue("${confDirectory}/client_tls_cert_file.cert")
	@ConverterClass(FileConverter.class)
	File clientTlsCertFile();

	@DefaultValue("60 seconds")
	@ConverterClass(DurationConverter.class)
	Duration startupTimeout();

	@DefaultValue("/opt/bitnami/scripts/pgbouncer/entrypoint.sh")
	@ConverterClass(FileConverter.class)
	File pgBouncerExec();

	@DefaultValue("/opt/bitnami/scripts/pgbouncer/run.sh")
	List<String> pgBouncerArgumentsDefault();

	@DefaultValue("/opt/bitnami/postgresql/bin/psql")
	@ConverterClass(FileConverter.class)
	File psqlExec();

	public static void main(String[] args) {
		Configs.printProperties(PrintOptions.properties());
		Configs.printProperties(PrintOptions.json());
	}
}
