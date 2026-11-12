package vn.edu.finalism.logistics.modules.notification.infrastructure;

import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.notification.application.NotificationService;
import vn.edu.finalism.logistics.modules.notification.application.NotificationService.IncomingEvent;

@Component
class NotificationEventListener {

	private final NotificationService service;
	private final ObjectMapper mapper;

	NotificationEventListener(NotificationService service, ObjectMapper mapper) {
		this.service = service;
		this.mapper = mapper;
	}

	@RabbitListener(queues = NotificationRabbitConfig.QUEUE)
	@SuppressWarnings("unchecked")
	void onMessage(byte[] body) {
		JsonNode root = mapper.readTree(body);
		Map<String, Object> payload = (Map<String, Object>) mapper.convertValue(root.get("payload"), Map.class);
		service.onEvent(new IncomingEvent(UUID.fromString(root.get("eventId").asString()),
				root.get("eventType").asString(), payload));
	}
}
