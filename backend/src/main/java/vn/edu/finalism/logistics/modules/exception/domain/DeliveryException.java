package vn.edu.finalism.logistics.modules.exception.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.BusinessException;

public record DeliveryException(
		UUID id,
		String code,
		UUID deliveryId,
		UUID stopId,
		UUID orderId,
		String typeCode,
		String severity,
		String impact,
		ExceptionStatus status,
		String description,
		UUID reporterUserId,
		UUID assigneeUserId,
		Instant reportedAt,
		Instant investigatingAt,
		Instant closedAt,
		UUID closedBy,
		String resolutionOutcome,
		String resolutionNote,
		String cancellationReason,
		long version) {

	public static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
	public static final Set<String> OUTCOMES = Set.of("RESUME_DELIVERY", "REATTEMPT_LATER", "RETURN_TO_SENDER",
			"NO_ACTION_NEEDED");

	public static DeliveryException report(UUID id, String code, UUID deliveryId, UUID stopId, UUID orderId,
			String typeCode, String severity, String impact, String description, UUID reporter, Instant now) {
		if (description == null || description.isBlank()) {
			throw BusinessException.validation("description", "REQUIRED", "A description is required.");
		}
		if (!SEVERITIES.contains(severity)) {
			throw BusinessException.validation("severity", "INVALID", "severity must be LOW, MEDIUM, HIGH or CRITICAL.");
		}
		return new DeliveryException(id, code, deliveryId, stopId, orderId, typeCode, severity, impact,
				ExceptionStatus.OPEN, description.trim(), reporter, null, now, null, null, null, null, null, null, 0);
	}

	public boolean blocking() {
		return blockingScope() != null;
	}

	public String blockingScope() {
		return switch (impact) {
			case "BLOCKS_STOP" -> "STOP";
			case "BLOCKS_DELIVERY_COMPLETION" -> "DELIVERY";
			default -> null;
		};
	}

	public DeliveryException investigate(UUID assignee, Instant now) {
		if (status != ExceptionStatus.OPEN) {
			throw BusinessException.stateNotAllowed("Only an open exception can be taken for investigation.");
		}
		return new DeliveryException(id, code, deliveryId, stopId, orderId, typeCode, severity, impact,
				ExceptionStatus.INVESTIGATING, description, reporterUserId, assignee, reportedAt, now, null, null,
				null, null, null, version);
	}

	public DeliveryException resolve(String outcome, String note, UUID actor, Instant now) {
		if (status != ExceptionStatus.INVESTIGATING) {
			throw BusinessException.stateNotAllowed("Only an exception under investigation can be resolved.");
		}
		if (outcome == null || !OUTCOMES.contains(outcome)) {
			throw BusinessException.validation("outcome", "INVALID", "outcome must be one of " + OUTCOMES + ".");
		}
		if (note == null || note.isBlank()) {
			throw BusinessException.validation("note", "REQUIRED", "A resolution note is required.");
		}
		return new DeliveryException(id, code, deliveryId, stopId, orderId, typeCode, severity, impact,
				ExceptionStatus.RESOLVED, description, reporterUserId, assigneeUserId, reportedAt, investigatingAt,
				now, actor, outcome, note.trim(), null, version);
	}

	public DeliveryException cancel(String reason, UUID actor, Instant now) {
		if (status != ExceptionStatus.OPEN) {
			throw BusinessException.stateNotAllowed("Only an open exception can be cancelled.");
		}
		if (reason == null || reason.isBlank()) {
			throw BusinessException.validation("reason", "REQUIRED", "A cancellation reason is required.");
		}
		return new DeliveryException(id, code, deliveryId, stopId, orderId, typeCode, severity, impact,
				ExceptionStatus.CANCELLED, description, reporterUserId, assigneeUserId, reportedAt, null, now, actor,
				null, null, reason.trim(), version);
	}
}
