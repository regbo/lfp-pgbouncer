package test;

import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import com.github.throwable.beanref.BeanPath;
import com.github.throwable.beanref.BeanRef;

import one.util.streamex.StreamEx;

public class ChainGet {

	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		// System.setProperty("jsse.enableSNIExtension",
		// Boolean.FALSE.toString().toLowerCase());
		testConnectionTo("https://reggie-pierce-dev.lasso.tm:6969");
	}

	private static void testConnectionTo(String aURL) throws Exception {
		URL destinationURL = new URL(aURL);
		HttpsURLConnection conn = (HttpsURLConnection) destinationURL.openConnection();
		conn.connect();
		List<Certificate> certs = StreamEx.of(conn.getServerCertificates()).toList();
		Collections.reverse(certs);
		for (Certificate cert : certs) {
			X509Certificate x509Certificate = (X509Certificate) cert;
			System.out.println(x509Certificate.getClass().getName());
			for (var bp : BeanRef.$(cert.getClass()).all()) {
				var value = ((BeanPath) bp).get(cert);
				if (value == null)
					continue;
				System.out.println(String.format("--------------%s-----------------", bp.getPath()));
				System.out.println(value.getClass().getName());
				System.out.println(value);
			}
		}
		for (Certificate cert : certs) {
			// System.out.println(Utils.Crypto.encodePEM(cert));
		}
	}
}
