package vn.edu.finalism.logistics.modules.identityaccess.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.identityaccess.domain.RefreshSession;

public interface RefreshSessionRepository {

	void insert(RefreshSession session);

	Optional<RefreshSession> findByTokenHash(String tokenHash);

	boolean revoke(UUID sessionId, Instant now);

	void revokeAllForUser(UUID userId, Instant now);
}
