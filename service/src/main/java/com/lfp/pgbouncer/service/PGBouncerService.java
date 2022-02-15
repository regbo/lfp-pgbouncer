package com.lfp.pgbouncer.service;

import java.util.List;

import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.net.http.ServiceConfig;
import com.lfp.pgbouncer.service.config.PGBouncerServiceConfig;

import retrofit2.http.GET;

public interface PGBouncerService {

	public static final String CERTIFICATE_CHAIN_PATH = "/certificiate-chain";

	public static ServiceConfig getServiceConfig() {
		return Configs.get(PGBouncerServiceConfig.class);
	}

	@GET(CERTIFICATE_CHAIN_PATH)
	List<String> certificateChain();
}
