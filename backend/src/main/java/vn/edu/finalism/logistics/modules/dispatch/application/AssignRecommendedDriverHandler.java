package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.DispatchRule;
import vn.edu.finalism.logistics.modules.administration.application.EffectiveRulePort;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryCreationPort;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryCreationPort.NewDelivery;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryCreationPort.StopSeed;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DriverCandidateReadPort;
import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment;
import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment.AssignmentStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.Candidate;
import vn.edu.finalism.logistics.modules.dispatch.domain.DecisionStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService.Requirements;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverFacts;
import vn.edu.finalism.logistics.modules.dispatch.domain.Snapshot;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.Transition;
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
public class AssignRecommendedDriverHandler {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;
	private final DriverCandidateReadPort candidates;
	private final EffectiveRulePort rules;
	private final DriverAvailabilityService drivers;
	private final OrderLifecycleService orderLifecycle;
	private final DeliveryCreationPort deliveryCreation;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	AssignRecommendedDriverHandler(BatchRepository batches, DispatchRepository dispatch,
			DriverCandidateReadPort candidates, EffectiveRulePort rules, DriverAvailabilityService drivers,
			OrderLifecycleService orderLifecycle, DeliveryCreationPort deliveryCreation,
			IdempotencyService idempotency, RequestFingerprinter fingerprinter, AuditWriter audit,
			EventPublicationPort events, ClockPort clock) {
		this.batches = batches;
		this.dispatch = dispatch;
		this.candidates = candidates;
		this.rules = rules;
		this.drivers = drivers;
		this.orderLifecycle = orderLifecycle;
		this.deliveryCreation = deliveryCreation;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<AssignmentResult> handle(ActorContext actor, UUID decisionId, IdempotencyKey key,
			long expectedVersion, UUID snapshotId, UUID chosenDriverId, String overrideReason) {
		actor.requirePermission("dispatch.assign");
		if (snapshotId == null) {
			throw BusinessException.validation("snapshotId", "REQUIRED", "snapshotId is required.");
		}
		String reason = overrideReason == null ? null : overrideReason.trim();
		Map<String, Object> body = new java.util.TreeMap<>();
		body.put("snapshotId", snapshotId.toString());
		if (chosenDriverId != null) {
			body.put("driverId", chosenDriverId.toString());
			body.put("overrideReason", reason == null ? "" : reason);
		}
		byte[] fingerprint = fingerprinter.fingerprint("POST /dispatch/decisions/" + decisionId + "/assign",
				String.valueOf(expectedVersion), body);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "ASSIGN_DRIVER", fingerprint);
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
		if (!batch.isLocked()) {
			throw BusinessException.stateNotAllowed("The batch is not locked.");
		}

		Instant now = clock.now();
		Snapshot snapshot = dispatch.findSnapshot(snapshotId)
				.filter(s -> s.decisionId().equals(decisionId))
				.orElseThrow(() -> BusinessException.notFound("Recommendation"));
		switch (snapshot.effectiveStatus(now)) {
			case "ACTIVE" -> {
			}
			case "EXPIRED" -> throw new BusinessException(ErrorCode.RECOMMENDATION_EXPIRED,
					"The recommendation has expired. Generate a new one.");
			default -> throw new BusinessException(ErrorCode.RECOMMENDATION_INVALIDATED,
					"The recommendation is no longer valid. Generate a new one.");
		}
		if (snapshot.candidates().isEmpty()) {
			throw BusinessException.stateNotAllowed("The recommendation has no eligible driver.");
		}
		Candidate top = snapshot.candidates().get(0);
		DispatchDecision awaiting = decision.awaitDriverResponse();

		UUID driverId = chosenDriverId == null ? top.driverId() : chosenDriverId;
		boolean overriding = !driverId.equals(top.driverId());
		if (overriding) {
			actor.requirePermission("dispatch.recommendation.override");
			if (reason == null || reason.isEmpty()) {
				throw BusinessException.validation("overrideReason", "REQUIRED",
						"A reason is required when choosing a driver other than the top recommendation.");
			}
		}

		DispatchRule rule = rules.effectiveRule(batch.zoneId());
		GeoPoint pickup = candidates.pickupCentroid(batch.id());
		DriverFacts facts = candidates.loadOne(batch.id(), driverId, batch.zoneId(), pickup, batch.plannedStart(),
				batch.plannedEnd()).orElseThrow(() -> BusinessException.notFound("Driver"));
		List<String> reasons = DriverEligibilityService.reasonsAgainst(facts,
				new Requirements(batch.totalWeightG(), batch.totalVolumeCm3()), now, rule.maxLocationAgeSeconds());
		if (!reasons.isEmpty()) {
			throw new BusinessException(ErrorCode.DRIVER_INELIGIBLE, "The recommended driver is no longer eligible.",
					Map.of("reasons", reasons));
		}

		UUID vehicleId = facts.vehicleId();
		drivers.reserve(driverId);
		Assignment assignment = new Assignment(UUID.randomUUID(), decisionId, driverId, vehicleId,
				overriding ? "OVERRIDE" : "ACCEPT_RECOMMENDATION", AssignmentStatus.PENDING, snapshotId,
				actor.userId(), now);
		dispatch.insertAssignment(assignment, top.driverId(), 1, overriding ? reason : null, null);
		dispatch.insertCommitment(assignment.id(), driverId, batch.plannedStart(), batch.plannedEnd(), now);

		List<OrderView> orders = orderLifecycle.lockAndGet(batch.orderIds());
		List<StopSeed> stops = orders.stream()
				.sorted(Comparator.comparing(OrderView::windowStart).thenComparing(OrderView::id))
				.map(o -> new StopSeed(o.id(), o.dropoffAddress(), o.dropoff(), o.windowStart(), o.windowEnd()))
				.toList();
		UUID deliveryId = deliveryCreation.createAssigned(new NewDelivery(batch.id(), decisionId, assignment.id(),
				driverId, vehicleId, stops));
		orderLifecycle.apply(batch.orderIds(), Transition.MARK_ASSIGNED);

		dispatch.markSnapshotConsumed(snapshotId, now);
		if (!dispatch.updateDecision(decisionId, awaiting.status(), expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}

		Map<String, Object> after = new java.util.LinkedHashMap<>();
		after.put("status", awaiting.status().name());
		after.put("assignmentId", assignment.id().toString());
		after.put("driverId", driverId.toString());
		after.put("recommendedDriverId", top.driverId().toString());
		after.put("snapshotId", snapshotId.toString());
		after.put("ruleVersion", snapshot.ruleVersionNo());
		if (overriding) {
			after.put("overrideReason", reason);
		}
		audit.append(AuditEntry.forUser(actor, overriding ? AuditAction.DRIVER_ASSIGNMENT_OVERRIDDEN
				: AuditAction.DRIVER_ASSIGNED, "DECISION", decisionId, batch.zoneId(),
				Map.of("status", DecisionStatus.PENDING_DECISION.name()), after, null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "DriverAssigned", 1, "DispatchDecision", decisionId, now,
				actor.userId(), actor.correlationId(),
				Map.of("decisionId", decisionId.toString(), "assignmentId", assignment.id().toString(),
						"deliveryId", deliveryId.toString(), "batchCode", batch.code(),
						"recipientUserIds", List.of(facts.userId().toString()))));
		idempotency.complete(actor.userId(), key, "ASSIGNMENT", assignment.id(), expectedVersion + 1);
		return Outcome.fresh(new AssignmentResult(assignment, expectedVersion + 1, deliveryId));
	}
}
