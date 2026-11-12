package vn.edu.finalism.logistics.shared.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(
		UUID eventId,
		String eventType,
		int eventVersion,
		String aggregateType,
		UUID aggregateId,
		Instant occurredAt,
		UUID actorId,
		String correlationId,
		Map<String, Object> safePayload) {

	public EventEnvelope {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(aggregateId, "aggregateId");
		Objects.requireNonNull(occurredAt, "occurredAt");
		if (eventType == null || eventType.isBlank()) {
			throw new IllegalArgumentException("eventType is required");
		}
		if (aggregateType == null || aggregateType.isBlank()) {
			throw new IllegalArgumentException("aggregateType is required");
		}
		if (correlationId == null || correlationId.isBlank()) {
			throw new IllegalArgumentException("correlationId is required");
		}
		if (eventVersion < 1) {
			throw new IllegalArgumentException("eventVersion must be at least 1");
		}
		safePayload = safePayload == null ? Map.of() : Map.copyOf(safePayload);
	}
}
