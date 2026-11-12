package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryLifecyclePort;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService.DriverSummary;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class RejectAssignmentHandler {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;
	private final DriverAvailabilityService drivers;
	private final DeliveryLifecyclePort deliveryLifecycle;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	RejectAssignmentHandler(BatchRepository batches, DispatchRepository dispatch, DriverAvailabilityService drivers,
			DeliveryLifecyclePort deliveryLifecycle, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, AuditWriter audit, EventPublicationPort events, ClockPort clock) {
		this.batches = batches;
		this.dispatch = dispatch;
		this.drivers = drivers;
		this.deliveryLifecycle = deliveryLifecycle;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<AssignmentResult> handle(ActorContext actor, UUID assignmentId, IdempotencyKey key,
			long expectedVersion, String reasonCode, String description) {
		actor.requirePermission("delivery.execute");
		DriverSummary driver = drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
		String code = reasonCode == null ? "" : reasonCode.trim();
		String text = description == null ? null : description.trim();
		if (code.isEmpty()) {
			throw BusinessException.validation("reasonCode", "REQUIRED", "A rejection reason is required.");
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("reasonCode", code);
		body.put("description", text == null ? "" : text);
		byte[] fingerprint = fingerprinter.fingerprint("POST /assignments/" + assignmentId + "/reject",
				String.valueOf(expectedVersion), body);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "REJECT_ASSIGNMENT", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			DispatchRepository.AssignmentRow row = dispatch.findAssignmentRow(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Assignment"));
			return Outcome.replay(new AssignmentResult(row.assignment(), replay.result().resultVersion(),
					row.deliveryId()));
		}

		boolean requiresDescription = dispatch.rejectionReasonRequiresDescription(code)
				.orElseThrow(() -> BusinessException.validation("reasonCode", "UNKNOWN", "Unknown rejection reason."));
		if (requiresDescription && (text == null || text.isEmpty())) {
			throw BusinessException.validation("description", "REQUIRED", "Please describe the reason.");
		}

		Assignment assignment = dispatch.findAssignment(assignmentId)
				.filter(a -> a.driverId().equals(driver.id()))
				.orElseThrow(() -> BusinessException.notFound("Assignment"));
		DispatchDecision decision = dispatch.findDecisionByIdForUpdate(assignment.decisionId())
				.orElseThrow(() -> BusinessException.notFound("Decision"));
		if (decision.version() != expectedVersion) {
			throw BusinessException.versionConflict(decision.version());
		}
		DispatchDecision awaiting = decision.rejectAssignment();
		Assignment rejected = assignment.reject();

		Instant now = clock.now();
		if (!dispatch.markAssignmentRejected(assignmentId, code, text, now)) {
			throw BusinessException.stateNotAllowed("The assignment is no longer waiting for a response.");
		}
		UUID deliveryId = deliveryLifecycle.markAwaitingReassignment(assignmentId);
		dispatch.releaseCommitments(decision.id(), now);
		drivers.release(driver.id());
		if (!dispatch.updateDecision(decision.id(), awaiting.status(), expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}

		DeliveryBatch batch = batches.findById(decision.batchId()).orElseThrow();
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("status", "REJECTED");
		after.put("reasonCode", code);
		if (text != null) {
			after.put("description", text);
		}
		audit.append(AuditEntry.forUser(actor, AuditAction.DRIVER_ASSIGNMENT_REJECTED, "ASSIGNMENT", assignmentId,
				batch.zoneId(), Map.of("status", "PENDING"), after, null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "DriverAssignmentRejected", 1, "DriverAssignment",
				assignmentId, now, actor.userId(), actor.correlationId(),
				Map.of("assignmentId", assignmentId.toString(), "decisionId", decision.id().toString(),
						"deliveryId", deliveryId.toString(), "batchCode", batch.code(),
						"driverName", driver.fullName(), "reasonCode", code,
						"recipientUserIds", List.of(assignment.decidedBy().toString()))));
		idempotency.complete(actor.userId(), key, "ASSIGNMENT", assignmentId, expectedVersion + 1);
		return Outcome.fresh(new AssignmentResult(rejected, expectedVersion + 1, deliveryId));
	}
}
