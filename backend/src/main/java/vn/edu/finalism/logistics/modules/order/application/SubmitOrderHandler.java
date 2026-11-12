package vn.edu.finalism.logistics.modules.order.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.ZoneLookupPort;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository;
import vn.edu.finalism.logistics.modules.order.domain.Order;
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
public class SubmitOrderHandler {

	private final OrderRepository orders;
	private final OrderTrackingService tracking;
	private final ZoneLookupPort zones;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	SubmitOrderHandler(OrderRepository orders, OrderTrackingService tracking, ZoneLookupPort zones,
			IdempotencyService idempotency, RequestFingerprinter fingerprinter, AuditWriter audit,
			EventPublicationPort events, ClockPort clock) {
		this.orders = orders;
		this.tracking = tracking;
		this.zones = zones;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<Order> handle(ActorContext actor, UUID orderId, IdempotencyKey key, long expectedVersion) {
		actor.requirePermission("order.create");
		byte[] fingerprint = fingerprinter.fingerprint("POST /orders/" + orderId + "/submit",
				String.valueOf(expectedVersion), null);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "SUBMIT_ORDER", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(orders.findById(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Order")));
		}

		Order order = orders.findByIdForUpdate(orderId)
				.filter(o -> o.customerId().equals(actor.userId()))
				.orElseThrow(() -> BusinessException.notFound("Order"));
		if (order.version() != expectedVersion) {
			throw BusinessException.versionConflict(order.version());
		}
		if (order.zoneId() == null && order.pickup() != null) {
			var match = zones.findZone(order.pickup());
			if (match.isPresent()) {
				order = order.withZone(match.get().zoneId(), match.get().zoneVersionId());
			}
		}
		Order ready = order.markReady();

		Instant now = clock.now();
		orders.updateState(ready, now);
		tracking.record(ready.id(), OrderTrackingService.Milestone.ORDER_RECEIVED, now);
		audit.append(AuditEntry.forUser(actor, AuditAction.ORDER_SUBMITTED, "ORDER", ready.id(), ready.zoneId(),
				Map.of("status", order.status().name()), Map.of("status", ready.status().name()), null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "OrderReadyForDispatch", 1, "Order", ready.id(), now,
				actor.userId(), actor.correlationId(),
				Map.of("orderId", ready.id().toString(), "orderCode", ready.code())));
		idempotency.complete(actor.userId(), key, "ORDER", ready.id(), expectedVersion + 1);
		return Outcome.fresh(orders.findById(ready.id()).orElseThrow());
	}
}
