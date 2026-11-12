package vn.edu.finalism.logistics.modules.notification.presentation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.finalism.logistics.modules.notification.application.NotificationService;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.PageResult;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

	private final NotificationService service;

	NotificationController(NotificationService service) {
		this.service = service;
	}

	record NotificationDto(UUID id, String channel, String status, String title, String body, String relatedEntityType,
			UUID relatedEntityId, Instant createdAt, Instant readAt) {
	}

	record NotificationPage(List<NotificationDto> items, String nextCursor, Instant asOf) {
	}

	@GetMapping
	NotificationPage list(ActorContext actor, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		PageResult<vn.edu.finalism.logistics.modules.notification.application.port.NotificationRepository.Row> page =
				service.inbox(actor.userId(), limit, cursor);
		return new NotificationPage(page.items().stream()
				.map(r -> new NotificationDto(r.id(), r.channel(), r.status(), r.title(), r.body(), r.relatedType(),
						r.relatedId(), r.createdAt(), r.readAt()))
				.toList(), page.nextCursor(), page.asOf());
	}

	@GetMapping("/unread-count")
	Map<String, Long> unreadCount(ActorContext actor) {
		return Map.of("count", service.unreadCount(actor.userId()));
	}

	@PostMapping("/read-all")
	ResponseEntity<Void> readAll(ActorContext actor) {
		service.markAllRead(actor.userId());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{notificationId}/read")
	ResponseEntity<Void> read(ActorContext actor, @PathVariable UUID notificationId) {
		service.markRead(actor.userId(), notificationId);
		return ResponseEntity.noContent().build();
	}
}
