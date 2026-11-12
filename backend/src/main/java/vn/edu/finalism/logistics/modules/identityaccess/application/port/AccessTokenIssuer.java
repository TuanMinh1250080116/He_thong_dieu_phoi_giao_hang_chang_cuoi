package vn.edu.finalism.logistics.modules.identityaccess.application.port;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface AccessTokenIssuer {

	AccessToken issue(UUID userId, Set<String> roles, Instant now);

	record AccessToken(String value, long expiresInSeconds) {
	}
}
