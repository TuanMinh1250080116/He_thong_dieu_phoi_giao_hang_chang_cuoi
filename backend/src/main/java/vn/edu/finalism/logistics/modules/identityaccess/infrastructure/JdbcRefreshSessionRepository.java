package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshSessionRepository;
import vn.edu.finalism.logistics.modules.identityaccess.domain.RefreshSession;

@Repository
class JdbcRefreshSessionRepository implements RefreshSessionRepository {

	private final JdbcClient jdbc;

	JdbcRefreshSessionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(RefreshSession session) {
		jdbc.sql("""
				INSERT INTO iam.refresh_sessions
				  (id, user_id, token_hash, issued_at, expires_at, revoked_at, user_agent_hash)
				VALUES (:id, :userId, :tokenHash, :issuedAt, :expiresAt, NULL, :userAgentHash)
				""")
				.param("id", session.id())
				.param("userId", session.userId())
				.param("tokenHash", session.tokenHash())
				.param("issuedAt", utc(session.issuedAt()))
				.param("expiresAt", utc(session.expiresAt()))
				.param("userAgentHash", session.userAgentHash())
				.update();
	}

	@Override
	public Optional<RefreshSession> findByTokenHash(String tokenHash) {
		return jdbc.sql("""
				SELECT id, user_id, token_hash, issued_at, expires_at, revoked_at, user_agent_hash
				FROM iam.refresh_sessions WHERE token_hash = :hash
				""")
				.param("hash", tokenHash)
				.query((rs, i) -> {
					OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
					return new RefreshSession(
							rs.getObject("id", UUID.class),
							rs.getObject("user_id", UUID.class),
							rs.getString("token_hash"),
							rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
							rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
							revoked == null ? null : revoked.toInstant(),
							rs.getString("user_agent_hash"));
				})
				.optional();
	}

	@Override
	public boolean revoke(UUID sessionId, Instant now) {
		int rows = jdbc.sql("UPDATE iam.refresh_sessions SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
				.param("now", utc(now))
				.param("id", sessionId)
				.update();
		return rows == 1;
	}

	@Override
	public void revokeAllForUser(UUID userId, Instant now) {
		jdbc.sql("UPDATE iam.refresh_sessions SET revoked_at = :now WHERE user_id = :id AND revoked_at IS NULL")
				.param("now", utc(now))
				.param("id", userId)
				.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
