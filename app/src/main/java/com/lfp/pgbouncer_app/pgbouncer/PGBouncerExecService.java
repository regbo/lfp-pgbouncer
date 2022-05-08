package com.lfp.pgbouncer_app.pgbouncer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;

import com.lfp.joe.certigo.impl.CertigoServiceImpl;
import com.lfp.joe.certigo.service.CertificateInfo;
import com.lfp.joe.core.function.Muto;
import com.lfp.joe.core.function.Scrapable;
import com.lfp.joe.core.properties.Configs;
import com.lfp.joe.net.http.ip.IPs;
import com.lfp.joe.net.socket.socks.Sockets;
import com.lfp.joe.process.ProcessLFP;
import com.lfp.joe.process.Procs;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.threads.Threads;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.Requires;
import com.lfp.pgbouncer_app.cert.CertStore;
import com.lfp.pgbouncer_app.config.PGBouncerAppConfig;
import com.lfp.pgbouncer_app.config.PGBouncerExecConfig;

import at.favre.lib.bytes.Bytes;
import one.util.streamex.StreamEx;

public class PGBouncerExecService extends Scrapable.Impl {

	private static final Class<?> THIS_CLASS = new Object() {}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);

	private static final String PAM_MODULE_PATH = "/lib/security/mypam.so";
	private static final String PAM_COMMON_AUTH_PATH = "/etc/pam.d/common-auth";
	private static final String ADMIN_DATABASE_USER = "pgbouncer";
	private static final String ADMIN_UNIX_USER = "pgbouncer";
	private static final String ENV_READ_FILE_POSTFIX = "_FILE";
	private static final String PGBOUNCER_CLIENT_TLS_SSLMODE = "require";
	private static final Entry<Bytes, Bytes> DUMMY_CERT_ENTRY = generateDummyCertEntry();
	private final Muto<ProcessLFP> processMuto = Muto.create();
	private final String[] args;
	private final InetSocketAddress pgBouncerAddress;

	public PGBouncerExecService(InetSocketAddress authenticatorAdress, String... args) throws IOException {
		this.args = args;
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
		this.pgBouncerAddress = InetSocketAddress.createUnresolved(IPs.getLocalIPAddress(), Sockets.allocatePort());
	}

	public void reload() throws IOException {
		processMuto.acceptSynchronized(process -> {
			if (process == null)
				return;
			logger.info("reload started");
			String command = String.format("su -c \"echo RELOAD | %s -p %s %s\" %s",
					Configs.get(PGBouncerExecConfig.class).psqlExec().getAbsolutePath(),
					this.pgBouncerAddress.getPort(), ADMIN_DATABASE_USER, ADMIN_UNIX_USER);
			String output;
			try {
				output = Procs.execute(command, ctx -> {
					ctx.readOutput(true);
					ctx.exitValueNormal();
				}).outputUTF8();
			} catch (IOException e) {
				throw RuntimeException.class.isInstance(e) ? RuntimeException.class.cast(e) : new RuntimeException(e);
			}
			output = Utils.Strings.trimToNull(output);
			Requires.isTrue(Utils.Strings.equalsIgnoreCase(output, "reload"), "reload failed:%s", output);
			logger.info("reload complete");
		});
	}

	public boolean setClientTLS(CertStore certStore) throws IOException {
		var cfg = Configs.get(PGBouncerExecConfig.class);
		var keyFile = cfg.clientTlsKeyFile();
		var certFile = cfg.clientTlsCertFile();
		boolean dummyCerts;
		Bytes keyValue;
		Bytes certValue;
		if (certStore == null) {
			keyValue = DUMMY_CERT_ENTRY.getKey();
			certValue = DUMMY_CERT_ENTRY.getValue();
			dummyCerts = true;
		} else {
			keyValue = certStore.getKey().getValue();
			certValue = certStore.getCert().getValue();
			dummyCerts = false;
		}
		boolean mod = false;
		if (writeToFile(keyValue, keyFile))
			mod = true;
		if (writeToFile(certValue, certFile))
			mod = true;
		if (mod) {
			if (!dummyCerts)
				logCertificateSummary(keyValue, certValue);
			reload();
		}
		return mod;
	}

	public ProcessLFP start() {
		return processMuto.updateAndGetSynchronized(nil -> {
			try {
				return createProcess();
			} catch (IOException e) {
				throw RuntimeException.class.isInstance(e) ? RuntimeException.class.cast(e) : new RuntimeException(e);
			}
		}, v -> v == null);
	}

	public InetSocketAddress getPgBouncerAddress() {
		return pgBouncerAddress;
	}

	protected ProcessLFP createProcess() throws IOException {
		var cfg = Configs.get(PGBouncerExecConfig.class);
		setClientTLS(null);
		// start process
		var environmentVariables = new LinkedHashMap<>(Utils.Machine.getEnvironmentVariables(ENV_READ_FILE_POSTFIX));
		environmentVariables.put("PGBOUNCER_AUTH_TYPE", "pam");
		environmentVariables.put("PGBOUNCER_BIND_ADDRESS", this.pgBouncerAddress.getHostString());
		environmentVariables.put("PGBOUNCER_PORT", Objects.toString(this.pgBouncerAddress.getPort()));
		environmentVariables.put("PGBOUNCER_CLIENT_TLS_SSLMODE", PGBOUNCER_CLIENT_TLS_SSLMODE);
		environmentVariables.put("PGBOUNCER_CLIENT_TLS_KEY_FILE", cfg.clientTlsKeyFile().getAbsolutePath());
		environmentVariables.put("PGBOUNCER_CLIENT_TLS_CERT_FILE", cfg.clientTlsCertFile().getAbsolutePath());
		String command = cfg.pgBouncerExec().getAbsolutePath();
		var argsStream = Utils.Lots.stream(args).filter(Utils.Strings::isNotBlank)
				.ifEmpty(Utils.Lots.defer(() -> cfg.pgBouncerArgumentsDefault()));
		command = StreamEx.of(command).append(argsStream).joining(" ");
		var process = Procs.start(command, null, environmentVariables);
		this.onScrap(() -> process.cancel(true));
		process.listener(this::scrap);
		logger.info("pgbouncer started:{}", this.pgBouncerAddress);
		var startupTimeout = cfg.startupTimeout();
		if (startupTimeout != null) {
			var startupTimeoutAt = Utils.Times.nowDate(zdt -> zdt.plus(startupTimeout));
			boolean pgBouncerServiceReady = false;
			for (int i = 0; !pgBouncerServiceReady && new Date().before(startupTimeoutAt); i++) {
				if (i > 0)
					Threads.sleepUnchecked(Duration.ofSeconds(3));
				pgBouncerServiceReady = Sockets.isServerPortOpen(this.pgBouncerAddress.getHostName(),
						this.pgBouncerAddress.getPort());
				logger.info("pgBouncer status ready:{}", pgBouncerServiceReady);
			}
			Requires.isTrue(pgBouncerServiceReady, "pgBouncer startup timed out after %sms", startupTimeout.toMillis());
		}

		return process;
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
		file.getParentFile().mkdirs();
		try (var is = content.inputStream(); var fos = new FileOutputStream(file)) {
			is.transferTo(fos);
		}
		return true;
	}

	private static Entry<Bytes, Bytes> generateDummyCertEntry() {
		var directory = Utils.Files.tempFile(THIS_CLASS, "dummy-certs", Utils.Crypto.getSecureRandomString())
				.deleteAllOnScrap(true);
		directory.mkdirs();
		try (directory) {
			var days = 365 * 25;
			var dns = "dummy-" + Utils.Crypto.getSecureRandomString() + ".com";
			var keyFile = new File(directory, "dummy.key");
			var certFile = new File(directory, "dummy.cert");
			var commandTemplate = "openssl req -x509 -newkey rsa:4096 -sha256 -days {{{DAYS}}} -nodes -keyout {{{KEY_FILE}}} -out {{{CERT_FILE}}} -subj \"/CN={{{DNS}}}\" -addext \"subjectAltName=DNS:{{{DNS}}}\"";
			var command = Utils.Strings.templateApply(commandTemplate, "DAYS", days, "DNS", dns, "KEY_FILE",
					keyFile.getName(), "CERT_FILE", certFile.getName());
			Procs.execute(command, ppe -> {
				ppe.directory(directory);
				ppe.disableOutputLog();
			});
			var keyValue = Utils.Bits.from(keyFile);
			var certValue = Utils.Bits.from(certFile);
			return Map.entry(keyValue, certValue);
		} catch (IOException e) {
			throw RuntimeException.class.isInstance(e) ? RuntimeException.class.cast(e) : new RuntimeException(e);
		}
	}

	private static void logCertificateSummary(Bytes keyValue, Bytes certValue) {
		logCertificateSummary(keyValue, certValue, false);
	}

	private static void logCertificateSummary(Bytes keyValue, Bytes certValue, boolean force) {
		if (!force && !Configs.get(PGBouncerAppConfig.class).logCertificateSummaries())
			return;
		logger.info("Key Updated:{}", getSummary(keyValue));
		logger.info("Cert Updated:{}", getSummary(certValue));
	}

	private static String getSummary(Bytes certValue) {
		CertificateInfo certificateInfo;
		try {
			certificateInfo = CertigoServiceImpl.get().dump(certValue);
		} catch (IOException e) {
			throw RuntimeException.class.isInstance(e) ? RuntimeException.class.cast(e) : new RuntimeException(e);
		}
		var size = Optional.ofNullable(certificateInfo).map(CertificateInfo::getCertificates).map(Collection::size)
				.orElse(0);
		if (size > 0)
			return Serials.Gsons.get().toJson(certificateInfo);
		return Utils.Crypto.hashMD5(certValue).encodeHex();
	}

	public static void main(String[] args) {
		var dummyCertEntry = generateDummyCertEntry();
		logCertificateSummary(dummyCertEntry.getKey(), dummyCertEntry.getValue(), true);
	}

}
