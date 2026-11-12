package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.Transition;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
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
public class CreateBatchHandler {

	private final BatchRepository batches;
	private final OrderLifecycleService orderLifecycle;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	CreateBatchHandler(BatchRepository batches, OrderLifecycleService orderLifecycle, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, AuditWriter audit, EventPublicationPort events, ClockPort clock) {
		this.batches = batches;
		this.orderLifecycle = orderLifecycle;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<DeliveryBatch> handle(ActorContext actor, IdempotencyKey key, UUID zoneId, List<UUID> orderIds) {
		actor.requirePermission("dispatch.batch.manage");
		if (zoneId == null) {
			throw BusinessException.validation("zoneId", "REQUIRED", "zoneId is required.");
		}
		actor.requireScope(zoneId);
		if (orderIds == null || orderIds.isEmpty() || orderIds.size() > DeliveryBatch.MAX_ORDERS
				|| new HashSet<>(orderIds).size() != orderIds.size()) {
			throw BusinessException.validation("orderIds", "SIZE_OUT_OF_RANGE",
					"orderIds must hold between 1 and " + DeliveryBatch.MAX_ORDERS + " distinct orders.");
		}

		byte[] fingerprint = fingerprinter.fingerprint("POST /dispatch/batches", null,
				Map.of("zoneId", zoneId.toString(), "orderIds", orderIds.stream().map(UUID::toString).sorted().toList()));
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_BATCH", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(batches.findById(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Batch")));
		}

		Instant now = clock.now();
		List<OrderView> orders = orderLifecycle.lockAndGet(orderIds);
		List<UUID> unusable = orders.stream()
				.filter(o -> o.status() != OrderStatus.READY_FOR_DISPATCH || !zoneId.equals(o.zoneId()))
				.map(OrderView::id).toList();
		if (!unusable.isEmpty()) {
			throw new BusinessException(ErrorCode.STATE_TRANSITION_NOT_ALLOWED,
					"Some orders are not ready for dispatch in this zone.",
					Map.of("invalidOrderIds", unusable.stream().map(UUID::toString).toList()));
		}

		long weight = orders.stream().mapToLong(OrderView::weightG).sum();
		long volume = orders.stream().mapToLong(o -> o.volumeCm3()).sum();
		DeliveryBatch batch = DeliveryBatch.draft(UUID.randomUUID(), batches.nextCode(), zoneId, actor.userId(),
				orderIds, weight, volume);
		batches.insert(batch, now);
		batches.addMembers(batch.id(), orderIds, actor.userId(), now);
		orderLifecycle.apply(orderIds, Transition.ATTACH_TO_BATCH);

		audit.append(AuditEntry.forUser(actor, AuditAction.BATCH_CREATED, "BATCH", batch.id(), zoneId, null,
				Map.of("code", batch.code(), "orderCount", orderIds.size()), null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "DeliveryBatchCreated", 1, "DeliveryBatch", batch.id(),
				now, actor.userId(), actor.correlationId(),
				Map.of("batchId", batch.id().toString(), "batchCode", batch.code())));
		idempotency.complete(actor.userId(), key, "BATCH", batch.id(), 0L);
		return Outcome.fresh(batches.findById(batch.id()).orElseThrow());
	}
}
