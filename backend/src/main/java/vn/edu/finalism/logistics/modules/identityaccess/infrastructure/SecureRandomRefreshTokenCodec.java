package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshTokenCodec;

@Component
class SecureRandomRefreshTokenCodec implements RefreshTokenCodec {

	private final SecureRandom random = new SecureRandom();

	@Override
	public String generate() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@Override
	public String hash(String rawValue) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawValue.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}
}
