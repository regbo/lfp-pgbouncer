package com.lfp.pgbouncer_app.cert;

import java.security.Key;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import com.github.throwable.beanref.BeanPath;
import com.github.throwable.beanref.BeanRef;
import com.lfp.joe.core.config.MachineConfig;
import com.lfp.joe.utils.Utils;

import at.favre.lib.bytes.Bytes;

public class CertSummary {

	protected CertSummary() {

	}

	private static final List<String> SKIP_PACKAGES = List.of(Key.class.getPackageName(), "sun.security",
			"org.bouncycastle");

	public static String get(String... input) {
		return get(Utils.Crypto.parseCertificates(input).toArray(Certificate[]::new));
	}

	public static String get(Bytes... input) {
		return get(Utils.Crypto.parseCertificates(input).toArray(Certificate[]::new));
	}

	public static String get(Certificate... input) {
		var indexToCert = Utils.Lots.stream(input).nonNull().chain(Utils.Lots::indexed);
		List<String> lines = new ArrayList<>();
		for (var ent : indexToCert) {
			var summary = get(ent.getKey(), ent.getValue());
			if (Utils.Strings.isBlank(summary))
				continue;
			lines.add(summary);
		}
		return Utils.Strings.joinNewLine(lines);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static String get(long index, Certificate certificate) {
		List<String> lines = new ArrayList<>();
		lines.add(String.format("Certificate %s [%s]:", index, certificate.getClass().getName()));
		lines.add(Utils.Crypto.encodePEM(certificate));
		lines.add("Properties:");
		var bps = BeanRef.$(certificate.getClass()).all();
		for (var bp : Utils.Lots.stream(bps).sortedBy(v -> v.getPath().toLowerCase())) {
			var propertyType = bp.getType();
			if (propertyType.isArray())
				continue;
			var packageName = propertyType.getPackageName();
			var skip = SKIP_PACKAGES.stream().anyMatch(v -> {
				if (Utils.Strings.equals(packageName, v))
					return true;
				if (Utils.Strings.startsWith(packageName, v + "."))
					return true;
				return false;
			});
			if (skip)
				continue;
			var value = ((BeanPath) bp).get(certificate);
			var valueStr = value == null ? null : Utils.Strings.trimToNull(value.toString());
			var typeAppend = MachineConfig.isDeveloper() ? String.format(" [%s]", bp.getType().getName()) : "";
			lines.add(String.format("%s%s:%s", bp.getPath(), typeAppend, valueStr));
		}
		return Utils.Strings.joinNewLine(lines);
	}

	public static void main(String[] args) {

	}

}
