package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.PasswordVerifier;

@Component
class BcryptPasswordVerifier implements PasswordVerifier {

	private final PasswordEncoder encoder;
	private final String dummyHash;

	BcryptPasswordVerifier(PasswordEncoder encoder) {
		this.encoder = encoder;
		this.dummyHash = encoder.encode("not-a-real-password");
	}

	@Override
	public boolean matches(String rawPassword, String passwordHash) {
		return encoder.matches(rawPassword, passwordHash);
	}

	@Override
	public void burnTime(String rawPassword) {
		encoder.matches(rawPassword, dummyHash);
	}
}
