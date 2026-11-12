package vn.edu.finalism.logistics.modules.order.application;

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
import vn.edu.finalism.logistics.modules.order.application.port.OrderBatchPort;
import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository;
import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class CancelOrderHandler {

	private final OrderRepository orders;
	private final OrderBatchPort batchPort;
	private final OrderTrackingService tracking;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	CancelOrderHandler(OrderRepository orders, OrderBatchPort batchPort, OrderTrackingService tracking, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, AuditWriter audit, EventPublicationPort events, ClockPort clock) {
		this.orders = orders;
		this.batchPort = batchPort;
		this.tracking = tracking;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<Order> handle(ActorContext actor, UUID orderId, IdempotencyKey key, long expectedVersion,
			String reason) {
		byte[] fingerprint = fingerprinter.fingerprint("POST /orders/" + orderId + "/cancel",
				String.valueOf(expectedVersion), Map.of("reason", reason == null ? "" : reason));
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CANCEL_ORDER", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(orders.findById(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Order")));
		}

		Order preview = orders.findById(orderId).orElseThrow(() -> BusinessException.notFound("Order"));
		boolean detached = false;
		if (preview.status() == OrderStatus.BATCHED && actor.hasPermission("dispatch.batch.manage")
				&& preview.zoneId() != null && actor.hasScope(preview.zoneId())) {
			if (preview.version() != expectedVersion) {
				throw BusinessException.versionConflict(preview.version());
			}
			batchPort.detachForCancellation(actor, orderId);
			detached = true;
		}
		Order current = orders.findByIdForUpdate(orderId).orElseThrow(() -> BusinessException.notFound("Order"));
		boolean owner = actor.hasPermission("order.create") && current.customerId().equals(actor.userId());
		boolean staff = actor.hasPermission("dispatch.batch.manage") && current.zoneId() != null
				&& actor.hasScope(current.zoneId());
		if (!owner && !staff) {
			throw BusinessException.notFound("Order");
		}
		if (!detached && current.version() != expectedVersion) {
			throw BusinessException.versionConflict(current.version());
		}
		if (owner && !staff && !current.isCancellableByOwner()) {
			throw BusinessException.stateNotAllowed(
					"The order is already in a dispatch batch. Ask dispatch to cancel it.");
		}

		Order cancelled = current.cancel(reason);
		Instant now = clock.now();
		orders.updateState(cancelled, now);
		tracking.record(orderId, OrderTrackingService.Milestone.ORDER_CANCELLED, now);

		Map<String, Object> before = new LinkedHashMap<>();
		before.put("status", current.status().name());
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("status", "CANCELLED");
		audit.append(AuditEntry.forUser(actor, AuditAction.ORDER_CANCELLED, "ORDER", orderId, current.zoneId(), before,
				after, cancelled.cancelReason(), null));
		events.append(new EventEnvelope(UUID.randomUUID(), "OrderCancelled", 1, "Order", orderId, now, actor.userId(),
				actor.correlationId(), Map.of("orderId", orderId.toString(), "orderCode", current.code(),
						"recipientUserIds", List.of(current.customerId().toString()))));
		idempotency.complete(actor.userId(), key, "ORDER", orderId, cancelled.version() + 1);
		return Outcome.fresh(orders.findById(orderId).orElseThrow());
	}
}
