package com.lfp.pgbouncer_app.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.lfp.joe.core.cache.Instances;
import com.lfp.joe.core.function.Asserts;
import com.lfp.pgbouncer_app.ENVParser;

public class StorageCrypto {

	public static Optional<StorageCrypto> tryGet() {
		return Instances.get(StorageCrypto.class.getName() + "#" + Optional.class.getName(), () -> {
			var aesSecret = ENVParser.getStorageAesKey().orElse(null);
			if (aesSecret == null)
				return Optional.empty();
			return Optional.of(new StorageCrypto(aesSecret));
		});
	}

	private final static int GCM_IV_LENGTH = 12;
	private final static int GCM_TAG_LENGTH = 16;
	private final SecretKeySpec secretKey;

	public StorageCrypto(String aesSecret) {
		this.secretKey = new SecretKeySpec(aesSecret.getBytes(StandardCharsets.UTF_8), "AES");
	}

	public byte[] decrypt(byte[] encrypted) throws DecryptException {
		try {
			byte[] iv = Arrays.copyOfRange(encrypted, 0, GCM_IV_LENGTH);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			GCMParameterSpec ivSpec = new GCMParameterSpec(GCM_TAG_LENGTH * Byte.SIZE, iv);
			cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
			return cipher.doFinal(encrypted, GCM_IV_LENGTH, encrypted.length - GCM_IV_LENGTH);
		} catch (Throwable t) {
			throw new DecryptException(encrypted, t);
		}
	}

	public Optional<byte[]> tryDecrypt(byte[] encrypted) {
		Asserts.isTrue(encrypted != null && encrypted.length > 0, ctx -> ctx.exceptionArguments("bytes required"));
		try {
			return Optional.of(decrypt(encrypted));
		} catch (DecryptException e) {
			return Optional.empty();
		}
	}

	@SuppressWarnings("serial")
	public static class DecryptException extends GeneralSecurityException {

		private final byte[] cipher;

		public DecryptException(byte[] cipher, Throwable cause) {
			super(cause);
			this.cipher = cipher;
		}

		public byte[] getCipher() {
			return cipher;
		}

	}

}
