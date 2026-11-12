package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.BatchStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DecisionStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.Transition;
import vn.edu.finalism.logistics.modules.order.application.port.OrderBatchPort;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
class BatchOrderDetachService implements OrderBatchPort {

	private static final String LAST_ORDER_REASON = "The last order of the batch was cancelled.";

	private final BatchRepository batches;
	private final DispatchRepository dispatch;
	private final OrderLifecycleService orderLifecycle;
	private final AuditWriter audit;
	private final ClockPort clock;

	BatchOrderDetachService(BatchRepository batches, DispatchRepository dispatch, OrderLifecycleService orderLifecycle,
			AuditWriter audit, ClockPort clock) {
		this.batches = batches;
		this.dispatch = dispatch;
		this.orderLifecycle = orderLifecycle;
		this.audit = audit;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void detachForCancellation(ActorContext actor, UUID orderId) {
		actor.requirePermission("dispatch.batch.manage");
		UUID batchId = batches.findActiveBatchIdOfOrder(orderId)
				.orElseThrow(() -> BusinessException.stateNotAllowed("The order is not in an active batch."));
		DeliveryBatch batch = batches.findByIdForUpdate(batchId).orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		if (!batch.orderIds().contains(orderId)) {
			throw BusinessException.stateNotAllowed("The order is not in an active batch.");
		}

		Instant now = clock.now();
		Optional<DispatchDecision> decision = dispatch.findOpenDecisionByBatchForUpdate(batchId);
		if (decision.isPresent()) {
			if (decision.get().status() != DecisionStatus.PENDING_DECISION) {
				throw BusinessException.stateNotAllowed(
						"A driver is already assigned to this batch. Cancel the dispatch before cancelling the order.");
			}
			dispatch.invalidateActiveSnapshots(decision.get().id(), "ORDER_CANCELLED", now);
		}

		List<UUID> remaining = new ArrayList<>(batch.orderIds());
		remaining.remove(orderId);
		if (remaining.isEmpty()) {
			decision.ifPresent(d -> dispatch.cancelDecision(d.id(), LAST_ORDER_REASON, d.version(), now));
			batches.markCancelled(batchId, LAST_ORDER_REASON, now);
			audit.append(AuditEntry.forUser(actor, AuditAction.BATCH_CANCELLED, "BATCH", batchId, batch.zoneId(),
					Map.of("status", batch.status().name()), Map.of("status", "CANCELLED"), LAST_ORDER_REASON, null));
		}
		else {
			if (batch.status() == BatchStatus.LOCKED) {
				batches.markReopened(batchId, now);
			}
			DeliveryBatch draft = batches.findByIdForUpdate(batchId).orElseThrow();
			List<OrderView> views = orderLifecycle.lockAndGet(remaining);
			long weight = views.stream().mapToLong(OrderView::weightG).sum();
			long volume = views.stream().mapToLong(OrderView::volumeCm3).sum();
			batches.applyMembership(draft.withMembers(remaining, weight, volume), List.of(), List.of(orderId),
					actor.userId(), now);
			audit.append(AuditEntry.forUser(actor, AuditAction.BATCH_MEMBERSHIP_CHANGED, "BATCH", batchId,
					batch.zoneId(), Map.of("orderCount", remaining.size() + 1),
					Map.of("orderCount", remaining.size(), "removed", List.of(orderId.toString())),
					"Order cancelled", null));
		}
		orderLifecycle.apply(List.of(orderId), Transition.RELEASE_FROM_BATCH);
	}
}
