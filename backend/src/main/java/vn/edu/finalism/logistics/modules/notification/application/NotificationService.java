package vn.edu.finalism.logistics.modules.notification.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.notification.application.port.NotificationRepository;
import vn.edu.finalism.logistics.modules.notification.application.port.NotificationRepository.Row;
import vn.edu.finalism.logistics.modules.notification.domain.NotificationTemplates;
import vn.edu.finalism.logistics.platform.event.EventHandlerRuns;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class NotificationService {

	static final String HANDLER_NAME = "notification";

	private final NotificationRepository notifications;
	private final EventHandlerRuns handlerRuns;
	private final ClockPort clock;

	NotificationService(NotificationRepository notifications, EventHandlerRuns handlerRuns, ClockPort clock) {
		this.notifications = notifications;
		this.handlerRuns = handlerRuns;
		this.clock = clock;
	}

	public record IncomingEvent(UUID eventId, String eventType, Map<String, Object> payload) {
	}

	@Transactional
	public int onEvent(IncomingEvent event) {
		if (!handlerRuns.claim(event.eventId(), HANDLER_NAME)) {
			return 0;
		}
		Object recipients = event.payload().get("recipientUserIds");
		if (!(recipients instanceof List<?> recipientIds) || recipientIds.isEmpty()) {
			return 0;
		}
		Map<String, String> data = new HashMap<>();
		event.payload().forEach((key, value) -> data.put(key, String.valueOf(value)));
		var rendered = NotificationTemplates.render(event.eventType(), data);
		if (rendered.isEmpty()) {
			return 0;
		}

		String relatedType = null;
		UUID relatedId = null;
		for (String[] candidate : new String[][] {{"orderId", "ORDER"}, {"deliveryId", "DELIVERY"},
				{"assignmentId", "ASSIGNMENT"}}) {
			if (event.payload().get(candidate[0]) != null) {
				relatedType = candidate[1];
				relatedId = UUID.fromString(String.valueOf(event.payload().get(candidate[0])));
				break;
			}
		}
		int created = 0;
		for (Object recipient : recipientIds) {
			boolean inserted = notifications.insertInApp(UUID.fromString(String.valueOf(recipient)),
					rendered.get().templateCode(), event.eventId(), relatedType, relatedId,
					rendered.get().title(), rendered.get().body(), clock.now());
			created += inserted ? 1 : 0;
			if (NotificationTemplates.sendsEmail(rendered.get().templateCode())) {
				notifications.insertEmail(UUID.fromString(String.valueOf(recipient)), rendered.get().templateCode(),
						event.eventId(), relatedType, relatedId, rendered.get().title(), rendered.get().body(),
						clock.now());
			}
		}
		return created;
	}

	@Transactional(readOnly = true)
	public PageResult<Row> inbox(UUID userId, Integer limit, String cursor) {
		int pageSize = PageCursor.normalizeLimit(limit);
		List<Row> rows = notifications.listInApp(userId, cursor == null ? null : PageCursor.decode(cursor), pageSize);
		boolean hasMore = rows.size() > pageSize;
		List<Row> page = hasMore ? rows.subList(0, pageSize) : rows;
		String next = hasMore ? new PageCursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id())
				.encode() : null;
		return new PageResult<>(page, next, clock.now());
	}

	@Transactional(readOnly = true)
	public long unreadCount(UUID userId) {
		return notifications.unreadCount(userId);
	}

	@Transactional
	public int markAllRead(UUID userId) {
		return notifications.markAllRead(userId, clock.now());
	}

	@Transactional
	public void markRead(UUID userId, UUID notificationId) {
		if (!notifications.markRead(notificationId, userId, clock.now())) {
			throw BusinessException.notFound("Notification");
		}
	}
}
