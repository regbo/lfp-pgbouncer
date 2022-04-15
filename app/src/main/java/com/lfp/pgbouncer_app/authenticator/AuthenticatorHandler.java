package com.lfp.pgbouncer_app.authenticator;

import java.util.Iterator;
import java.util.Objects;

import com.lfp.connect.undertow.UndertowUtils;
import com.lfp.connect.undertow.handler.MessageHandler;
import com.lfp.joe.core.classpath.Instances;
import com.lfp.joe.core.config.MachineConfig;
import com.lfp.joe.jwt.Jwts;
import com.lfp.joe.jwt.token.JwsLFP;
import com.lfp.joe.net.status.StatusCodes;
import com.lfp.joe.utils.Utils;

import io.jsonwebtoken.SigningKeyResolver;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import one.util.streamex.StreamEx;

public class AuthenticatorHandler implements HttpHandler {

	public static AuthenticatorHandler get() {
		return Instances.get(AuthenticatorHandler.class,
				() -> new AuthenticatorHandler(AuthenticatorSigningKeyResolver.get()));
	}

	private static final boolean SEND_RESPONSE_BODY_ON_SUCCESS = MachineConfig.isDeveloper() && false;
	private static final HttpHandler UNAUTHORIZED_JWT_HANDLER = new MessageHandler(StatusCodes.UNAUTHORIZED,
			"invalid jwt");
	private final SigningKeyResolver signingKeyResolver;

	public AuthenticatorHandler(SigningKeyResolver signingKeyResolver) {
		super();
		this.signingKeyResolver = Objects.requireNonNull(signingKeyResolver);
	}

	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		var authValues = UndertowUtils.streamHeaders(exchange.getRequestHeaders())
				.filterKeys(com.lfp.joe.net.http.headers.Headers.AUTHORIZATION::equalsIgnoreCase).values();
		authValues = authValues.map(v -> Utils.Strings.removeStartIgnoreCase(v, "Basic "));
		authValues = authValues.mapPartial(Utils.Strings::trimToNullOptional);
		var candidateStream = authValues.map(v -> {
			if (Utils.Bits.isBase64(v))
				try {
					return Utils.Bits.parseBase64(v).encodeUtf8();
				} catch (Exception e) {
					// suppress
				}
			return v;
		}).mapPartial(Utils.Strings::trimToNullOptional);
		var candidateIterator = candidateStream.iterator();
		if (!candidateIterator.hasNext()) {
			UNAUTHORIZED_JWT_HANDLER.handleRequest(exchange);
			return;
		}
		handleRequest(exchange, candidateIterator);
	}

	protected void handleRequest(HttpServerExchange exchange, Iterator<String> candidateIterator) throws Exception {
		if (exchange.isInIoThread()) {
			exchange.dispatch(hse -> handleRequest(exchange, candidateIterator));
			return;
		}
		var jws = getJws(candidateIterator);
		if (jws == null) {
			UNAUTHORIZED_JWT_HANDLER.handleRequest(exchange);
			return;
		}
		var sc = StatusCodes.OK;
		exchange.setStatusCode(sc);
		if (SEND_RESPONSE_BODY_ON_SUCCESS)
			exchange.getResponseSender().send(StatusCodes.getReason(sc));
		exchange.endExchange();
	}

	protected JwsLFP getJws(Iterator<String> candidateIterator) {
		while (candidateIterator.hasNext()) {
			var candidate = candidateIterator.next();
			var splitAt = Utils.Strings.indexOf(candidate, ":");
			String jwt;
			if (splitAt < 0)
				jwt = candidate;
			else
				jwt = StreamEx.of(candidate.substring(0, splitAt), candidate.substring(splitAt + 1))
						.mapPartial(Utils.Strings::trimToNullOptional).joining();
			var jwsOp = Jwts.tryParse(jwt, signingKeyResolver);
			if (jwsOp.isPresent())
				return jwsOp.get();
		}
		return null;
	}

}
