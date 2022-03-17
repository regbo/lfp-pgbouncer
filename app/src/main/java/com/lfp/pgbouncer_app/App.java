package com.lfp.pgbouncer_app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import com.lfp.connect.undertow.Undertows;
import com.lfp.connect.undertow.handler.ErrorLoggingHandler;
import com.lfp.connect.undertow.handler.ThreadHttpHandler;
import com.lfp.joe.core.config.MachineConfig;
import com.lfp.joe.core.process.executor.CentralExecutor;
import com.lfp.joe.net.http.ip.IPs;
import com.lfp.joe.net.socket.socks.Sockets;
import com.lfp.joe.process.PromiseProcess;
import com.lfp.joe.utils.Utils;
import com.lfp.pgbouncer_app.authenticator.AuthenticatorHandler;
import com.lfp.pgbouncer_app.caddy.CaddyService;
import com.lfp.pgbouncer_app.cert.CertStoreService;
import com.lfp.pgbouncer_app.cert.PGBouncerServiceImpl;
import com.lfp.pgbouncer_app.pgbouncer.PGBouncerExecService;
import com.lfp.pgbouncer_app.storage.RedisService;

import io.undertow.Undertow.ListenerInfo;
import io.undertow.server.HttpHandler;
import net.tascalate.concurrent.Promises;

public class App {
	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);

	public static void main(String[] args) {
		int exitCode = 0;
		try {
			run(args);
		} catch (Throwable t) {
			if (!Utils.Exceptions.isCancelException(t)) {
				logger.error("uncaught error. exiting", t);
				exitCode = 1;
			}
		}
		System.exit(exitCode);
	}

	@SuppressWarnings("resource")
	public static void run(String[] args) throws IOException, InterruptedException {
		ENVService.init();
		InetSocketAddress authenticatorAddress = InetSocketAddress.createUnresolved(IPs.getLocalIPAddress(),
				Sockets.allocatePort());
		var serverBuilder = Undertows.serverBuilderBase();
		{
			var authHandler = initializeHandler(AuthenticatorHandler.get());
			serverBuilder.addHttpListener(authenticatorAddress.getPort(), authenticatorAddress.getHostString(),
					authHandler);
		}
		CertStoreService certStoreService = CertStoreService.get();
		InetSocketAddress serviceAddress = InetSocketAddress.createUnresolved(IPs.getLocalIPAddress(),
				Sockets.allocatePort());
		{
			var service = new PGBouncerServiceImpl();
			var serviceHandler = initializeHandler(service);
			serverBuilder.addHttpListener(serviceAddress.getPort(), serviceAddress.getHostString(), serviceHandler);
		}
		var server = serverBuilder.build();
		server.start();
		logger.info("undertow started:{}",
				Utils.Lots.stream(server.getListenerInfo()).map(ListenerInfo::getAddress).toList());
		InetSocketAddress pgBouncerServiceAddress;
		PromiseProcess pgBouncerServiceProcess;
		if (MachineConfig.isDeveloper() && Utils.Machine.isWindows()) {
			pgBouncerServiceProcess = null;
			pgBouncerServiceAddress = null;
		} else {
			var pgBouncerService = new PGBouncerExecService(authenticatorAddress, args);
			pgBouncerServiceAddress = pgBouncerService.getPgBouncerAddress();
			pgBouncerServiceProcess = pgBouncerService.start();
			Runnable clientTLSUpdateTask = () -> {
				var certStore = certStoreService.streamCertStores().findFirst().orElse(null);
				Utils.Functions.unchecked(() -> pgBouncerService.setClientTLS(certStore));
			};
			certStoreService.addModificationEventListener(evt -> {
				clientTLSUpdateTask.run();
			});
			clientTLSUpdateTask.run();
			logger.info("pgBouncer started. pid{} address:{}", pgBouncerServiceProcess.getProcess().pid(),
					pgBouncerServiceAddress);
		}
		var caddyService = new CaddyService(serviceAddress, pgBouncerServiceAddress, RedisService.get());
		var caddyProcess = caddyService.get();
		logger.info("caddy started:{}", caddyProcess.getProcess().pid());
		List<CompletionStage<?>> processList = new ArrayList<>();
		processList.add(caddyProcess.getProcess().onExit());
		if (pgBouncerServiceProcess != null)
			processList.add(pgBouncerServiceProcess);
		Promises.any(processList).join();
	}

	private static HttpHandler initializeHandler(HttpHandler httpHandler) {
		httpHandler = new ErrorLoggingHandler(httpHandler);
		httpHandler = new ThreadHttpHandler(httpHandler, CentralExecutor.INSTANCE);
		return httpHandler;

	}
}
