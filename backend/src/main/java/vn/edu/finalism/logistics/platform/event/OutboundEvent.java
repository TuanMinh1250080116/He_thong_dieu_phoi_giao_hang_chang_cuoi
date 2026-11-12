package vn.edu.finalism.logistics.platform.event;

import java.util.UUID;

public record OutboundEvent(UUID eventId, String eventType, String correlationId, byte[] body) {
}
