package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.DispatchRule;
import vn.edu.finalism.logistics.modules.administration.application.EffectiveRulePort;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryLifecyclePort;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DriverCandidateReadPort;
import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment;
import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment.AssignmentStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService.Requirements;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverFacts;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.GeoPoint;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class ReassignDriverHandler {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;
	private final DriverCandidateReadPort candidates;
	private final EffectiveRulePort rules;
	private final DriverAvailabilityService drivers;
	private final DeliveryLifecyclePort deliveryLifecycle;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	ReassignDriverHandler(BatchRepository batches, DispatchRepository dispatch, DriverCandidateReadPort candidates,
			EffectiveRulePort rules, DriverAvailabilityService drivers, DeliveryLifecyclePort deliveryLifecycle,
			IdempotencyService idempotency, RequestFingerprinter fingerprinter, AuditWriter audit,
			EventPublicationPort events, ClockPort clock) {
		this.batches = batches;
		this.dispatch = dispatch;
		this.candidates = candidates;
		this.rules = rules;
		this.drivers = drivers;
		this.deliveryLifecycle = deliveryLifecycle;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<AssignmentResult> handle(ActorContext actor, UUID decisionId, IdempotencyKey key,
			long expectedVersion, UUID driverId, String reason) {
		actor.requirePermission("dispatch.assign");
		if (driverId == null) {
			throw BusinessException.validation("driverId", "REQUIRED", "driverId is required.");
		}
		String text = reason == null ? "" : reason.trim();
		if (text.isEmpty()) {
			throw BusinessException.validation("reason", "REQUIRED", "A reason is required to change the driver.");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("driverId", driverId.toString());
		body.put("reason", text);
		byte[] fingerprint = fingerprinter.fingerprint("POST /dispatch/decisions/" + decisionId + "/reassign",
				String.valueOf(expectedVersion), body);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "REASSIGN_DRIVER", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			DispatchRepository.AssignmentRow row = dispatch.findAssignmentRow(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Assignment"));
			return Outcome.replay(new AssignmentResult(row.assignment(), replay.result().resultVersion(),
					row.deliveryId()));
		}

		DispatchDecision decision = dispatch.findDecisionByIdForUpdate(decisionId)
				.orElseThrow(() -> BusinessException.notFound("Decision"));
		DeliveryBatch batch = batches.findByIdForUpdate(decision.batchId())
				.orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		if (decision.version() != expectedVersion) {
			throw BusinessException.versionConflict(decision.version());
		}
		DispatchDecision awaiting = decision.reassign();

		Optional<Assignment> current = dispatch.findCurrentAssignmentForUpdate(decisionId);
		if (current.isPresent() && current.get().driverId().equals(driverId)) {
			throw BusinessException.validation("driverId", "SAME_DRIVER", "Choose a different driver.");
		}

		Instant now = clock.now();
		DispatchRule rule = rules.effectiveRule(batch.zoneId());
		GeoPoint pickup = candidates.pickupCentroid(batch.id());
		DriverFacts facts = candidates.loadOne(batch.id(), driverId, batch.zoneId(), pickup, batch.plannedStart(),
				batch.plannedEnd()).orElseThrow(() -> BusinessException.notFound("Driver"));
		List<String> reasons = DriverEligibilityService.reasonsAgainst(facts,
				new Requirements(batch.totalWeightG(), batch.totalVolumeCm3()), now, rule.maxLocationAgeSeconds());
		if (!reasons.isEmpty()) {
			throw new BusinessException(ErrorCode.DRIVER_INELIGIBLE, "The chosen driver is not eligible.",
					Map.of("reasons", reasons));
		}

		UUID newId = UUID.randomUUID();
		UUID previousDriverId = null;
		UUID previousUserId = null;
		if (current.isPresent()) {
			Assignment old = current.get();
			previousDriverId = old.driverId();
			previousUserId = drivers.findById(old.driverId()).map(d -> d.userId()).orElse(null);
			dispatch.supersedeAssignment(old.id(), newId);
			dispatch.releaseCommitments(decisionId, now);
			drivers.release(old.driverId());
		}

		drivers.reserve(driverId);
		Assignment assignment = new Assignment(newId, decisionId, driverId, facts.vehicleId(), "REASSIGN",
				AssignmentStatus.PENDING, null, actor.userId(), now);
		dispatch.insertAssignment(assignment, null, dispatch.nextAssignmentSequence(decisionId), null, text);
		dispatch.insertCommitment(newId, driverId, batch.plannedStart(), batch.plannedEnd(), now);
		UUID deliveryId = deliveryLifecycle.reassign(decisionId, newId, driverId, facts.vehicleId());
		if (!dispatch.updateDecision(decisionId, awaiting.status(), expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}

		Map<String, Object> before = new LinkedHashMap<>();
		before.put("status", decision.status().name());
		if (previousDriverId != null) {
			before.put("driverId", previousDriverId.toString());
		}
		audit.append(AuditEntry.forUser(actor, AuditAction.DRIVER_REASSIGNED, "DECISION", decisionId, batch.zoneId(),
				before, Map.of("status", awaiting.status().name(), "assignmentId", newId.toString(),
						"driverId", driverId.toString(), "reason", text),
				null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "DriverAssigned", 1, "DispatchDecision", decisionId, now,
				actor.userId(), actor.correlationId(),
				Map.of("decisionId", decisionId.toString(), "assignmentId", newId.toString(),
						"deliveryId", deliveryId.toString(), "batchCode", batch.code(),
						"recipientUserIds", List.of(facts.userId().toString()))));
		if (previousUserId != null) {
			events.append(new EventEnvelope(UUID.randomUUID(), "DriverUnassigned", 1, "DispatchDecision", decisionId,
					now, actor.userId(), actor.correlationId(),
					Map.of("decisionId", decisionId.toString(), "batchCode", batch.code(),
							"recipientUserIds", List.of(previousUserId.toString()))));
		}
		idempotency.complete(actor.userId(), key, "ASSIGNMENT", newId, expectedVersion + 1);
		return Outcome.fresh(new AssignmentResult(assignment, expectedVersion + 1, deliveryId));
	}
}
