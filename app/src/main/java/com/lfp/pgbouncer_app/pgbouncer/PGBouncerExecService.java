package com.lfp.pgbouncer_app.pgbouncer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.lfp.joe.core.function.Muto;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.core.process.Callbacks;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.net.http.ip.IPs;
import com.lfp.joe.net.socket.socks.Sockets;
import com.lfp.joe.process.Procs;
import com.lfp.joe.process.PromiseProcess;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.Requires;
import com.lfp.pgbouncer_app.cert.CertStore;
import com.lfp.pgbouncer_app.config.PGBouncerExecConfig;

import at.favre.lib.bytes.Bytes;
import one.util.streamex.StreamEx;

public class PGBouncerExecService extends Scrapable.Impl {

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);
	private static final String PAM_MODULE_PATH = "/lib/security/mypam.so";
	private static final String PAM_COMMON_AUTH_PATH = "/etc/pam.d/common-auth";
	private static final String ADMIN_DATABASE_USER = "postgres";
	private static final String ADMIN_UNIX_USER = "postgres";
	private static final String ENV_READ_FILE_POSTFIX = "_FILE";
	private final PGBouncerIni pgBouncerIni;
	private final PromiseProcess process;
	private final InetSocketAddress pgBouncerAddress;

	public PGBouncerExecService(InetSocketAddress authenticatorAdress, String... args) throws IOException {
		this.pgBouncerIni = new PGBouncerIni(Configs.get(PGBouncerExecConfig.class).confIni());
		Objects.requireNonNull(authenticatorAdress);
		{// prepare pam
			var prepend = new ArrayList<String>();
			prepend.add(String.format("auth sufficient %s url=http://%s:%s", PAM_MODULE_PATH,
					authenticatorAdress.getHostString(), authenticatorAdress.getPort()));
			prepend.add(String.format("account sufficient %s", PAM_MODULE_PATH));
			prepend.add("");
			var pamCommonAuth = new File(PAM_COMMON_AUTH_PATH);
			var pamCommonAuthContents = Utils.Files.readFileToString(pamCommonAuth);
			pamCommonAuthContents = StreamEx.of(prepend).append(pamCommonAuthContents)
					.joining(Objects.toString(Utils.Strings.newLine()));
			try (var is = Utils.Bits.from(pamCommonAuthContents).inputStream();
					var fos = new FileOutputStream(pamCommonAuth)) {
				is.transferTo(fos);
			}
		}
		this.pgBouncerAddress = new InetSocketAddress(IPs.getLocalIPAddress(), Sockets.allocatePort());
		{// start process
			var environmentVariables = Utils.Machine.getEnvironmentVariables(ENV_READ_FILE_POSTFIX);
			environmentVariables.put("PGBOUNCER_AUTH_TYPE", "pam");
			environmentVariables.put("PGBOUNCER_BIND_ADDRESS", this.pgBouncerAddress.getHostString());
			environmentVariables.put("PGBOUNCER_PORT", Objects.toString(this.pgBouncerAddress.getPort()));
			String command = Configs.get(PGBouncerExecConfig.class).pgBouncerExec().getAbsolutePath();
			if (args != null)
				command = StreamEx.of(command).append(args).joining(" ");
			this.process = Procs.start(command, null, environmentVariables);
			this.onScrap(() -> this.process.cancel(true));
			this.process.whenComplete(Callbacks.listener(this::scrap));
			logger.info("pgbouncer started:{}", this.pgBouncerAddress);
		}
	}

	public void reload() throws IOException {
		logger.info("reload started");
		String command = String.format("su -c \"echo RELOAD | %s -p %s %s\" %s",
				Configs.get(PGBouncerExecConfig.class).psqlExec().getAbsolutePath(), this.pgBouncerAddress.getPort(),
				ADMIN_DATABASE_USER, ADMIN_UNIX_USER);
		var output = Procs.execute(command, ctx -> {
			ctx.readOutput(true);
			ctx.exitValueNormal();
		}).outputUTF8();
		output = Utils.Strings.trimToNull(output);
		Requires.isTrue(Utils.Strings.equalsIgnoreCase(output, "reload"), "reload failed:%s", output);
		logger.info("reload complete");
	}

	public boolean setClientTLS(CertStore certStore) throws IOException {
		var fileWrite = Muto.createBoo();
		var configWrite = this.pgBouncerIni.access((reader, writer) -> {
			var cfg = Configs.get(PGBouncerExecConfig.class);
			Map<File, Bytes> fileToValueMap = new LinkedHashMap<>();
			fileToValueMap.put(cfg.clientTlsKeyFile(), certStore == null ? null : certStore.getKey().getValue());
			fileToValueMap.put(cfg.clientTlsCertFile(), certStore == null ? null : certStore.getCert().getValue());
			for (var ent : fileToValueMap.entrySet()) {
				var file = ent.getKey();
				var value = ent.getValue();
				if (writeToFile(value, file))
					fileWrite.set(true);
				var iniKey = Utils.Files.getNameAndExtension(file).getKey();
				var iniValue = file.exists() ? file.getAbsolutePath() : null;
				writer.accept(iniKey, iniValue);
			}
		});
		if (fileWrite.isTrue() || configWrite) {
			reload();
			return true;
		}
		return false;
	}

	public PromiseProcess getProcess() {
		return process;
	}

	public InetSocketAddress getPgBouncerAddress() {
		return pgBouncerAddress;
	}

	public PGBouncerIni getPgBouncerIni() {
		return pgBouncerIni;
	}

	private static boolean writeToFile(Bytes content, File file) throws FileNotFoundException, IOException {
		if (content == null || content.isEmpty())
			return file.delete();
		Bytes currentHash;
		if (file.exists())
			try (var fis = new FileInputStream(file)) {
				currentHash = Utils.Crypto.hashMD5(fis);
			}
		else
			currentHash = Utils.Bits.empty();
		Bytes contentHash;
		try (var is = content.inputStream()) {
			contentHash = Utils.Crypto.hashMD5(is);
		}
		if (Objects.equals(currentHash, contentHash))
			return false;
		try (var is = content.inputStream(); var fos = new FileOutputStream(file)) {
			is.transferTo(fos);
		}
		return true;

	}

}
