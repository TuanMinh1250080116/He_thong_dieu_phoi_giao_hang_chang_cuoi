package vn.edu.finalism.logistics.modules.order.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.ZoneLookupPort;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.order.application.CreateOrderCommand.SaveMode;
import vn.edu.finalism.logistics.modules.order.application.port.GeocodingPort;
import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.GeoPoint;
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
public class CreateOrderHandler {

	private final OrderRepository orders;
	private final GeocodingPort geocoder;
	private final OrderTrackingService tracking;
	private final ZoneLookupPort zones;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	CreateOrderHandler(OrderRepository orders, GeocodingPort geocoder, OrderTrackingService tracking,
			ZoneLookupPort zones, IdempotencyService idempotency, RequestFingerprinter fingerprinter,
			AuditWriter audit, EventPublicationPort events, ClockPort clock) {
		this.orders = orders;
		this.geocoder = geocoder;
		this.tracking = tracking;
		this.zones = zones;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<Order> handle(ActorContext actor, IdempotencyKey key, CreateOrderCommand command) {
		actor.requirePermission("order.create");
		if (command.weightG() == null) {
			throw BusinessException.validation("package.weightG", "REQUIRED", "package.weightG is required.");
		}
		byte[] fingerprint = fingerprinter.fingerprint("POST /orders", null, command);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_ORDER", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(orders.findById(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Order")));
		}

		Instant now = clock.now();
		GeoPoint pickupPoint = command.pickup() != null ? command.pickup()
				: geocoder.geocode(command.pickupAddress()).orElse(null);
		GeoPoint dropoffPoint = command.dropoff() != null ? command.dropoff()
				: geocoder.geocode(command.dropoffAddress()).orElse(null);
		Order order = Order.create(UUID.randomUUID(), orders.nextCode(), actor.userId(),
				command.pickupAddress(), pickupPoint, command.dropoffAddress(), dropoffPoint,
				command.recipientName(), command.recipientContact(), command.weightG(),
				command.lengthCm(), command.widthCm(), command.heightCm(), command.packageNote(),
				command.windowStart(), command.windowEnd());
		if (pickupPoint != null) {
			var match = zones.findZone(pickupPoint);
			if (match.isPresent()) {
				order = order.withZone(match.get().zoneId(), match.get().zoneVersionId());
			}
		}
		if (command.saveMode() == SaveMode.SUBMIT) {
			order = order.markReady();
		}

		orders.insert(order, now);
		if (order.status() != OrderStatus.DRAFT) {
			tracking.record(order.id(), OrderTrackingService.Milestone.ORDER_RECEIVED, now);
		}
		audit.append(AuditEntry.forUser(actor, AuditAction.ORDER_CREATED, "ORDER", order.id(), order.zoneId(),
				null, Map.of("code", order.code(), "status", order.status().name()), null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "OrderCreated", 1, "Order", order.id(), now,
				actor.userId(), actor.correlationId(),
				Map.of("orderId", order.id().toString(), "orderCode", order.code(),
						"status", order.status().name(),
						"recipientUserIds", List.of(actor.userId().toString()))));
		idempotency.complete(actor.userId(), key, "ORDER", order.id(), 0L);
		return Outcome.fresh(order);
	}
}
