package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.Transition;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class CancelDispatchHandler {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;
	private final DriverAvailabilityService drivers;
	private final DeliveryLifecyclePort deliveryLifecycle;
	private final OrderLifecycleService orderLifecycle;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	CancelDispatchHandler(BatchRepository batches, DispatchRepository dispatch, DriverAvailabilityService drivers,
			DeliveryLifecyclePort deliveryLifecycle, OrderLifecycleService orderLifecycle, AuditWriter audit,
			EventPublicationPort events, ClockPort clock) {
		this.batches = batches;
		this.dispatch = dispatch;
		this.drivers = drivers;
		this.deliveryLifecycle = deliveryLifecycle;
		this.orderLifecycle = orderLifecycle;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public long handle(ActorContext actor, UUID decisionId, long expectedVersion, String reason) {
		actor.requirePermission("dispatch.assign");
		String text = reason == null ? "" : reason.trim();
		if (text.isEmpty()) {
			throw BusinessException.validation("reason", "REQUIRED", "A reason is required to cancel the dispatch.");
		}
		DispatchDecision decision = dispatch.findDecisionByIdForUpdate(decisionId)
				.orElseThrow(() -> BusinessException.notFound("Decision"));
		DeliveryBatch batch = batches.findByIdForUpdate(decision.batchId())
				.orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		if (decision.version() != expectedVersion) {
			throw BusinessException.versionConflict(decision.version());
		}
		decision.cancel();

		Instant now = clock.now();
		Optional<Assignment> current = dispatch.findCurrentAssignmentForUpdate(decisionId);
		List<String> notify = new ArrayList<>();
		if (current.isPresent()) {
			Assignment assignment = current.get();
			drivers.findById(assignment.driverId()).ifPresent(d -> notify.add(d.userId().toString()));
			dispatch.cancelCurrentAssignment(decisionId, text);
			dispatch.releaseCommitments(decisionId, now);
			drivers.release(assignment.driverId());
		}
		Optional<UUID> deliveryId = deliveryLifecycle.cancelByDecision(decisionId, text);
		if (deliveryId.isPresent()) {
			orderLifecycle.apply(batch.orderIds(), Transition.REVERT_TO_BATCHED);
		}
		dispatch.invalidateActiveSnapshots(decisionId, "DISPATCH_CANCELLED", now);
		dispatch.cancelDecision(decisionId, text, expectedVersion, now);

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("status", "CANCELLED");
		after.put("reason", text);
		audit.append(AuditEntry.forUser(actor, AuditAction.DISPATCH_CANCELLED, "DECISION", decisionId, batch.zoneId(),
				Map.of("status", decision.status().name()), after, null, null));
		if (!notify.isEmpty()) {
			events.append(new EventEnvelope(UUID.randomUUID(), "DispatchCancelled", 1, "DispatchDecision", decisionId,
					now, actor.userId(), actor.correlationId(),
					Map.of("decisionId", decisionId.toString(), "batchCode", batch.code(),
							"recipientUserIds", notify)));
		}
		return expectedVersion + 1;
	}
}
