package vn.edu.finalism.logistics.platform.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.IdempotencyKey;

@Component
public class IdempotencyService {

	public static final Duration RETRY_WINDOW = Duration.ofHours(24);

	private final JdbcClient jdbc;
	private final ClockPort clock;

	IdempotencyService(JdbcClient jdbc, ClockPort clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	private record Existing(String useCase, byte[] fingerprint, String status, StoredResult result) {
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public IdempotencyClaim claim(UUID actorId, IdempotencyKey key, String useCase, byte[] fingerprint) {
		Instant now = clock.now();
		OffsetDateTime nowTs = utc(now);
		OffsetDateTime expiresTs = utc(now.plus(RETRY_WINDOW));

		int reclaimed = jdbc.sql("""
				UPDATE platform.processed_commands
				SET use_case = :useCase, request_fingerprint = :fingerprint, status = 'IN_PROGRESS',
				    result_type = NULL, result_ref = NULL, result_version = NULL,
				    created_at = :now, completed_at = NULL, expires_at = :expires
				WHERE actor_id = :actor AND idempotency_key = :key AND expires_at <= :now
				""")
				.param("useCase", useCase).param("fingerprint", fingerprint)
				.param("now", nowTs).param("expires", expiresTs)
				.param("actor", actorId).param("key", key.value())
				.update();
		if (reclaimed == 1) {
			return new IdempotencyClaim.Fresh();
		}

		int inserted = jdbc.sql("""
				INSERT INTO platform.processed_commands
				  (id, actor_id, idempotency_key, use_case, request_fingerprint, status, created_at, expires_at)
				VALUES (:id, :actor, :key, :useCase, :fingerprint, 'IN_PROGRESS', :now, :expires)
				ON CONFLICT (actor_id, idempotency_key) DO NOTHING
				""")
				.param("id", UUID.randomUUID()).param("actor", actorId).param("key", key.value())
				.param("useCase", useCase).param("fingerprint", fingerprint)
				.param("now", nowTs).param("expires", expiresTs)
				.update();
		if (inserted == 1) {
			return new IdempotencyClaim.Fresh();
		}

		Existing existing = jdbc.sql("""
				SELECT use_case, request_fingerprint, status, result_type, result_ref, result_version
				FROM platform.processed_commands WHERE actor_id = :actor AND idempotency_key = :key
				""")
				.param("actor", actorId).param("key", key.value())
				.query((rs, i) -> new Existing(
						rs.getString("use_case"),
						rs.getBytes("request_fingerprint"),
						rs.getString("status"),
						new StoredResult(rs.getString("result_type"),
								rs.getObject("result_ref", UUID.class),
								(Long) rs.getObject("result_version"))))
				.single();

		if (!existing.useCase().equals(useCase) || !Arrays.equals(existing.fingerprint(), fingerprint)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
					"This Idempotency-Key was already used with a different request.");
		}
		if ("COMPLETED".equals(existing.status())) {
			return new IdempotencyClaim.Replay(existing.result());
		}
		throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
				"The same command is still being processed.", Map.of("retryAfterSeconds", 1));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void complete(UUID actorId, IdempotencyKey key, String resultType, UUID resultRef, Long resultVersion) {
		int rows = jdbc.sql("""
				UPDATE platform.processed_commands
				SET status = 'COMPLETED', result_type = :type, result_ref = :ref, result_version = :version,
				    completed_at = :now
				WHERE actor_id = :actor AND idempotency_key = :key AND status = 'IN_PROGRESS'
				""")
				.param("type", resultType)
				.param("ref", resultRef, java.sql.Types.OTHER)
				.param("version", resultVersion, java.sql.Types.BIGINT)
				.param("now", utc(clock.now()))
				.param("actor", actorId).param("key", key.value())
				.update();
		if (rows != 1) {
			throw new IllegalStateException("complete() called without a matching claim()");
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
