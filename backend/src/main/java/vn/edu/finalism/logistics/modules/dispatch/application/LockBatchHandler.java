package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.BatchLockPolicy;
import vn.edu.finalism.logistics.modules.dispatch.domain.BatchLockPolicy.OrderFacts;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class LockBatchHandler {

	private final BatchRepository batches;
	private final OrderLifecycleService orderLifecycle;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	LockBatchHandler(BatchRepository batches, OrderLifecycleService orderLifecycle, AuditWriter audit,
			EventPublicationPort events, ClockPort clock) {
		this.batches = batches;
		this.orderLifecycle = orderLifecycle;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public DeliveryBatch handle(ActorContext actor, UUID batchId, long expectedVersion) {
		actor.requirePermission("dispatch.batch.manage");
		DeliveryBatch batch = batches.findByIdForUpdate(batchId)
				.orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		if (batch.version() != expectedVersion) {
			throw BusinessException.versionConflict(batch.version());
		}
		if (batch.status() != vn.edu.finalism.logistics.modules.dispatch.domain.BatchStatus.DRAFT) {
			throw BusinessException.stateNotAllowed("Only a draft batch can be locked.");
		}

		List<OrderView> orders = orderLifecycle.lockAndGet(batch.orderIds());
		BatchLockPolicy.Result check = BatchLockPolicy.check(batch.zoneId(), orders.stream()
				.map(o -> new OrderFacts(o.id(), o.status().name(), o.zoneId(), o.windowStart(), o.windowEnd()))
				.toList());
		if (!check.allowed()) {
			throw new BusinessException(ErrorCode.BATCH_LOCK_VALIDATION_FAILED,
					"The batch cannot be locked.",
					Map.of("batchId", batchId.toString(),
							"currentVersion", batch.version(),
							"invalidOrderIds", check.invalidOrderIds().stream().map(UUID::toString).toList(),
							"capacityViolations", List.of(),
							"zoneOrTimeWindowViolations", check.zoneOrWindowViolations().stream()
									.map(v -> Map.of("orderId", v.orderId().toString(), "reason", v.reason()))
									.toList()));
		}

		Instant now = clock.now();
		DeliveryBatch locked = batch.lock(check.plannedStart(), check.plannedEnd());
		batches.markLocked(locked, actor.userId(), now);
		audit.append(AuditEntry.forUser(actor, AuditAction.BATCH_LOCKED, "BATCH", batchId, batch.zoneId(),
				Map.of("status", "DRAFT"), Map.of("status", "LOCKED"), null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "DeliveryBatchLocked", 1, "DeliveryBatch", batchId, now,
				actor.userId(), actor.correlationId(), Map.of("batchId", batchId.toString(), "batchCode", batch.code())));
		return batches.findById(batchId).orElseThrow();
	}
}
