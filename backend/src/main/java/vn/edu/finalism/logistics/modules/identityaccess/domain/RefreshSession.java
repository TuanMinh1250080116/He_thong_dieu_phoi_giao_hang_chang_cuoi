package vn.edu.finalism.logistics.modules.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshSession(
		UUID id,
		UUID userId,
		String tokenHash,
		Instant issuedAt,
		Instant expiresAt,
		Instant revokedAt,
		String userAgentHash) {

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpiredAt(Instant now) {
		return !expiresAt.isAfter(now);
	}
}
