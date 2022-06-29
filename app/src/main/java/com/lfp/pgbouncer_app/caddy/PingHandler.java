package com.lfp.pgbouncer_app.caddy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.function.Consumer;

import com.lfp.connect.undertow.UndertowUtils;
import com.lfp.connect.undertow.handler.MessageHandler;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.net.http.ServiceConfig;
import com.lfp.joe.net.http.client.HttpClients;
import com.lfp.joe.net.http.request.HttpRequests;
import com.lfp.joe.net.status.StatusCodes;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.utils.Utils;
import com.lfp.pgbouncer.service.PGBouncerService;
import com.lfp.pgbouncer_app.config.CaddyExecConfig;

import io.mikael.urlbuilder.UrlBuilder;
import io.undertow.server.handlers.PathHandler;

public class PingHandler extends MessageHandler implements Consumer<PathHandler> {

	private static final Class<?> THIS_CLASS = new Object() {}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final int STATUS_CODE = StatusCodes.OK;
	private boolean ready;

	public PingHandler() {
		super(STATUS_CODE);
	}

	public boolean isReady() {
		if (!ready)
			synchronized (this) {
				if (!ready) {
					try {
						if (checkReady()) {
							logger.info("service ready:{}", getServiceURI());
							ready = true;
						}
					} catch (Throwable t) {
						if (!Utils.Exceptions.isCancelException(t) && !(t instanceof IOException))
							logger.warn("unexpected error", t);
					}
				}
			}
		return ready;
	}

	@Override
	public void accept(PathHandler pathHandler) {
		UndertowUtils.appendPath(pathHandler, Configs.get(CaddyExecConfig.class).pingPath(), this);
	}

	protected boolean checkReady() throws IOException {
		var uri = UrlBuilder.fromUri(getServiceURI()).withPath(getPath()).toUri();
		var request = HttpRequests.request().uri(uri);
		var response = HttpClients.send(request, BodyHandlers.ofString());
		StatusCodes.validate(response.statusCode(), STATUS_CODE);
		var body = response.body();
		var messageHandlerBody = Utils.Functions.catching(body,
				v -> Serials.Gsons.get().fromJson(v, MessageHandler.Body.class), t -> null);
		if (messageHandlerBody != null)
			body = messageHandlerBody.getMessage();
		return Utils.Strings.equalsIgnoreCase(body, StatusCodes.getReason(STATUS_CODE));
	}

	private static URI getServiceURI() {
		var uri = ServiceConfig.discover(PGBouncerService.class).map(ServiceConfig::uri).nonNull().findFirst().get();
		return uri;
	}

	private static String getPath() {
		return Configs.get(CaddyExecConfig.class).pingPath();
	}

}
