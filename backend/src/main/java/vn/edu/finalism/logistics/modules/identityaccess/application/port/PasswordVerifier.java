package vn.edu.finalism.logistics.modules.identityaccess.application.port;

public interface PasswordVerifier {

	boolean matches(String rawPassword, String passwordHash);

	void burnTime(String rawPassword);
}
