package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.util.Set;
import java.util.UUID;

public record AuthResult(IssuedTokens tokens, UserSummary user) {

	public record IssuedTokens(String accessToken, long expiresInSeconds,
			String refreshToken, long refreshExpiresInSeconds) {
	}

	public record UserSummary(UUID id, String email, String displayName, Set<String> roles) {
	}
}
