package vn.edu.finalism.logistics.modules.audit.application;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.ActorContext;

public record AuditEntry(
		AuditAction action,
		AuditActorType actorType,
		UUID actorId,
		String entityType,
		UUID entityId,
		UUID zoneId,
		Map<String, Object> before,
		Map<String, Object> after,
		String reason,
		String correlationId,
		UUID commandId) {

	public AuditEntry {
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(actorType, "actorType");
		Objects.requireNonNull(entityId, "entityId");
		if (entityType == null || entityType.isBlank()) {
			throw new IllegalArgumentException("entityType is required");
		}
		if (correlationId == null || correlationId.isBlank()) {
			throw new IllegalArgumentException("correlationId is required");
		}
		if (actorType == AuditActorType.USER && actorId == null) {
			throw new IllegalArgumentException("actorId is required for a USER actor");
		}
	}

	public static AuditEntry forUser(ActorContext actor, AuditAction action, String entityType, UUID entityId,
			UUID zoneId, Map<String, Object> before, Map<String, Object> after, String reason, UUID commandId) {
		return new AuditEntry(action, AuditActorType.USER, actor.userId(), entityType, entityId, zoneId,
				before, after, reason, actor.correlationId(), commandId);
	}
}
