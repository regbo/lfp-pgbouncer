package com.lfp.pgbouncer_app.authenticator;

import java.util.Objects;
import java.util.function.Predicate;

import com.lfp.joe.core.cache.Instances;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.jwt.resolver.ClaimsPredicates;
import com.lfp.joe.jwt.resolver.IssuerClaimSigningKeyResolver;
import com.lfp.joe.utils.Utils;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;

import io.jsonwebtoken.Claims;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;

public class AuthenticatorSigningKeyResolver extends IssuerClaimSigningKeyResolver {

	public static AuthenticatorSigningKeyResolver get() {
		return Instances.get(AuthenticatorSigningKeyResolver.class);
	}

	protected AuthenticatorSigningKeyResolver() {
		super(createClaimsPredicate());
	}

	private static Predicate<Claims> createClaimsPredicate() {
		var cfg = Configs.get(PGBouncerAppConfig.class);
		Predicate<Claims> claimsPredicate = ClaimsPredicates.denyAll();
		{
			var predicate = ClaimsPredicates.fromIssuerHosts(cfg.authenticatorJwtIssuerHosts());
			claimsPredicate = claimsPredicate.or(predicate);
		}
		{
			var predicate = ClaimsPredicates.fromIssuerURIs(cfg.authenticatorJwtIssuerURIs());
			claimsPredicate = claimsPredicate.or(predicate);
		}
		EntryStream<String, String> requiredClaims = Utils.Lots.streamMultimap(cfg.authenticatorJwtRequiredClaims());
		requiredClaims = requiredClaims.filterKeys(Utils.Strings::isNotBlank);
		requiredClaims = requiredClaims.mapValues(v -> v != null ? v : "");
		for (var ent : requiredClaims) {
			var requiredName = ent.getKey();
			var requiredValue = ent.getValue();
			claimsPredicate = claimsPredicate.and(claims -> {
				var value = claims.get(requiredName);
				StreamEx<Object> valueStream = Utils.Lots.tryStream(value).orElseGet(() -> StreamEx.of(value));
				return valueStream.anyMatch(v -> {
					return Objects.equals(requiredValue, v);
				});
			});
		}
		return claimsPredicate;
	}

}
