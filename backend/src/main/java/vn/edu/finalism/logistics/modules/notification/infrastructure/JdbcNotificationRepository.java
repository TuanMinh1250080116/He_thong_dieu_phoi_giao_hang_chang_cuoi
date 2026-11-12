package vn.edu.finalism.logistics.modules.notification.infrastructure;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.notification.application.port.NotificationRepository;
import vn.edu.finalism.logistics.shared.PageCursor;

@Repository
class JdbcNotificationRepository implements NotificationRepository {

	private final JdbcClient jdbc;

	JdbcNotificationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean insertInApp(UUID recipientUserId, String templateCode, UUID sourceEventId, String relatedType,
			UUID relatedId, String title, String body, Instant now) {
		return jdbc.sql("""
				INSERT INTO notif.notifications
				  (id, recipient_user_id, channel, template_code, source_event_id, related_type, related_id,
				   title, body, status, created_at, sent_at)
				VALUES (:id, :recipient, 'IN_APP', :template, :event, :relatedType, :relatedId,
				        :title, :body, 'SENT', :now, :now)
				ON CONFLICT ON CONSTRAINT uq_notifications_dedup DO NOTHING
				""")
				.param("id", UUID.randomUUID()).param("recipient", recipientUserId)
				.param("template", templateCode).param("event", sourceEventId)
				.param("relatedType", relatedType, Types.VARCHAR).param("relatedId", relatedId, Types.OTHER)
				.param("title", title).param("body", body).param("now", utc(now.truncatedTo(ChronoUnit.MILLIS)))
				.update() == 1;
	}

	@Override
	public boolean insertEmail(UUID recipientUserId, String templateCode, UUID sourceEventId, String relatedType,
			UUID relatedId, String title, String body, Instant now) {
		return jdbc.sql("""
				INSERT INTO notif.notifications
				  (id, recipient_user_id, channel, template_code, source_event_id, related_type, related_id,
				   title, body, status, next_attempt_at, created_at)
				VALUES (:id, :recipient, 'EMAIL', :template, :event, :relatedType, :relatedId,
				        :title, :body, 'PENDING', :now, :now)
				ON CONFLICT ON CONSTRAINT uq_notifications_dedup DO NOTHING
				""")
				.param("id", UUID.randomUUID()).param("recipient", recipientUserId)
				.param("template", templateCode).param("event", sourceEventId)
				.param("relatedType", relatedType, Types.VARCHAR).param("relatedId", relatedId, Types.OTHER)
				.param("title", title).param("body", body).param("now", utc(now))
				.update() == 1;
	}

	@Override
	public List<DueEmail> claimDueEmails(Instant now, int limit) {
		return jdbc.sql("""
				SELECT n.id, u.email, n.title, n.body, n.attempts
				FROM notif.notifications n
				JOIN iam.users u ON u.id = n.recipient_user_id
				WHERE n.channel = 'EMAIL' AND n.status IN ('PENDING','FAILED')
				  AND n.next_attempt_at IS NOT NULL AND n.next_attempt_at <= :now
				ORDER BY n.next_attempt_at, n.id
				LIMIT :limit
				FOR UPDATE OF n SKIP LOCKED
				""")
				.param("now", utc(now)).param("limit", limit)
				.query((rs, i) -> new DueEmail(rs.getObject("id", UUID.class), rs.getString("email"),
						rs.getString("title"), rs.getString("body"), rs.getInt("attempts")))
				.list();
	}

	@Override
	public void markEmailSent(UUID id, Instant now) {
		jdbc.sql("""
				UPDATE notif.notifications
				SET status = 'SENT', sent_at = :now, attempts = attempts + 1, next_attempt_at = NULL,
				    last_error_code = NULL
				WHERE id = :id
				""")
				.param("now", utc(now)).param("id", id).update();
	}

	@Override
	public void markEmailFailed(UUID id, int attempts, Instant nextAttemptAt, String errorCode) {
		jdbc.sql("""
				UPDATE notif.notifications
				SET status = 'FAILED', attempts = :attempts, next_attempt_at = :next, last_error_code = :error
				WHERE id = :id
				""")
				.param("attempts", attempts)
				.param("next", nextAttemptAt == null ? null : utc(nextAttemptAt), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("error", errorCode).param("id", id).update();
	}

	@Override
	public int markAllRead(UUID recipientUserId, Instant now) {
		return jdbc.sql("""
				UPDATE notif.notifications SET status = 'READ', read_at = :now
				WHERE recipient_user_id = :recipient AND channel = 'IN_APP' AND status <> 'READ'
				""")
				.param("now", utc(now)).param("recipient", recipientUserId).update();
	}

	@Override
	public List<Row> listInApp(UUID recipientUserId, PageCursor after, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT id, channel, status, title, body, related_type, related_id, created_at, read_at
				FROM notif.notifications
				WHERE recipient_user_id = :recipient AND channel = 'IN_APP'
				""");
		if (after != null) {
			sql.append(" AND (created_at, id) < (:afterTs, :afterId)");
		}
		sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
		JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("recipient", recipientUserId)
				.param("limit", limit + 1);
		if (after != null) {
			spec = spec.param("afterTs", utc(after.sortKey())).param("afterId", after.id());
		}
		return spec.query((rs, i) -> {
			OffsetDateTime read = rs.getObject("read_at", OffsetDateTime.class);
			return new Row(rs.getObject("id", UUID.class), rs.getString("channel"), rs.getString("status"),
					rs.getString("title"), rs.getString("body"), rs.getString("related_type"),
					rs.getObject("related_id", UUID.class),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					read == null ? null : read.toInstant());
		}).list();
	}

	@Override
	public long unreadCount(UUID recipientUserId) {
		return jdbc.sql("""
				SELECT count(*) FROM notif.notifications
				WHERE recipient_user_id = :recipient AND channel = 'IN_APP' AND status <> 'READ'
				""")
				.param("recipient", recipientUserId).query(Long.class).single();
	}

	@Override
	public boolean markRead(UUID notificationId, UUID recipientUserId, Instant now) {
		jdbc.sql("""
				UPDATE notif.notifications SET status = 'READ', read_at = :now
				WHERE id = :id AND recipient_user_id = :recipient AND channel = 'IN_APP' AND status <> 'READ'
				""")
				.param("now", utc(now)).param("id", notificationId).param("recipient", recipientUserId).update();
		return jdbc.sql("SELECT count(*) FROM notif.notifications WHERE id = :id AND recipient_user_id = :recipient")
				.param("id", notificationId).param("recipient", recipientUserId)
				.query(Long.class).single() == 1;
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
