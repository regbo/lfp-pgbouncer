package com.lfp.pgbouncer_app.config;

import java.io.File;

import org.aeonbits.owner.Config;

import com.lfp.joe.core.properties.converter.FileConverter;

public interface PGBouncerExecConfig extends Config {

	@DefaultValue("/lib/security/mypam.so")
	String pamAuthenticationModulePath();

	@DefaultValue("/opt/bitnami/pgbouncer/conf")
	@ConverterClass(FileConverter.class)
	File confDirectory();

	@DefaultValue("${pgBouncerConfDirectory}/pgbouncer.ini")
	@ConverterClass(FileConverter.class)
	File confIni();

	@DefaultValue("${pgBouncerConfDirectory}/client_tls_key_file.key")
	@ConverterClass(FileConverter.class)
	File clientTlsKeyFile();

	@DefaultValue("${pgBouncerConfDirectory}/client_tls_cert_file.cert")
	@ConverterClass(FileConverter.class)
	File clientTlsCertFile();

	@DefaultValue("/opt/bitnami/scripts/pgbouncer/entrypoint.sh")
	@ConverterClass(FileConverter.class)
	File pgBouncerExec();

	@DefaultValue("/opt/bitnami/postgresql/bin/psql")
	@ConverterClass(FileConverter.class)
	File psqlExec();
}
