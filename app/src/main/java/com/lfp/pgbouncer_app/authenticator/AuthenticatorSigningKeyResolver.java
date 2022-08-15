package com.lfp.pgbouncer_app.authenticator;

import java.util.function.Predicate;

import com.lfp.joe.core.classpath.Instances;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.jwt.resolver.AbstractIssuerJwkSigningKeyResolver;
import com.lfp.joe.jwt.resolver.ImmutableAbstractSigningKeyResolver.SigningKeyRequest;
import com.lfp.joe.jwt.resolver.SigningKeyRequestFilters;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;

public class AuthenticatorSigningKeyResolver extends AbstractIssuerJwkSigningKeyResolver {

	public static AuthenticatorSigningKeyResolver get() {
		return Instances.get(AuthenticatorSigningKeyResolver.class);
	}

	private final Predicate<? super SigningKeyRequest> requestFilter;

	protected AuthenticatorSigningKeyResolver() {
		var cfg = Configs.get(PGBouncerAppConfig.class);
		Predicate<SigningKeyRequest> requestFilter = v -> false;
		{
			var predicate = SigningKeyRequestFilters.issuerHostsEquals(cfg.authenticatorJwtIssuerHosts());
			requestFilter = requestFilter.or(predicate);
		}
		{
			var predicate = SigningKeyRequestFilters.issuerURIMatches(cfg.authenticatorJwtIssuerURIs());
			requestFilter = requestFilter.or(predicate);
		}
		this.requestFilter = requestFilter;
	}

	@Override
	public Predicate<? super SigningKeyRequest> requestFilter() {
		return requestFilter;
	}

}
