package vn.edu.finalism.logistics.modules.notification.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.PageCursor;

public interface NotificationRepository {

	boolean insertInApp(UUID recipientUserId, String templateCode, UUID sourceEventId, String relatedType,
			UUID relatedId, String title, String body, Instant now);

	List<Row> listInApp(UUID recipientUserId, PageCursor after, int limit);

	long unreadCount(UUID recipientUserId);

	boolean markRead(UUID notificationId, UUID recipientUserId, Instant now);

	int markAllRead(UUID recipientUserId, Instant now);

	boolean insertEmail(UUID recipientUserId, String templateCode, UUID sourceEventId, String relatedType,
			UUID relatedId, String title, String body, Instant now);

	List<DueEmail> claimDueEmails(Instant now, int limit);

	void markEmailSent(UUID id, Instant now);

	void markEmailFailed(UUID id, int attempts, Instant nextAttemptAt, String errorCode);

	record DueEmail(UUID id, String to, String title, String body, int attempts) {
	}

	record Row(UUID id, String channel, String status, String title, String body, String relatedType,
			UUID relatedId, Instant createdAt, Instant readAt) {
	}
}
