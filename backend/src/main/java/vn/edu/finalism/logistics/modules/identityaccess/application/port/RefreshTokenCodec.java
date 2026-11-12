package vn.edu.finalism.logistics.modules.identityaccess.application.port;

public interface RefreshTokenCodec {

	String generate();

	String hash(String rawValue);
}
