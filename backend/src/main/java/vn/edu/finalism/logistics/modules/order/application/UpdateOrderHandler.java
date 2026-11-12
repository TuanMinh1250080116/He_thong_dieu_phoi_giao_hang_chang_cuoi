package vn.edu.finalism.logistics.modules.order.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.ZoneLookupPort;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.order.application.port.GeocodingPort;
import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository;
import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Service
public class UpdateOrderHandler {

	public record Command(String pickupAddress, GeoPoint pickup, String dropoffAddress, GeoPoint dropoff,
			String recipientName, String recipientContact, Integer weightG, Integer lengthCm, Integer widthCm,
			Integer heightCm, String packageNote, Instant windowStart, Instant windowEnd) {
	}

	private final OrderRepository orders;
	private final GeocodingPort geocoder;
	private final ZoneLookupPort zones;
	private final AuditWriter audit;
	private final ClockPort clock;

	UpdateOrderHandler(OrderRepository orders, GeocodingPort geocoder, ZoneLookupPort zones, AuditWriter audit,
			ClockPort clock) {
		this.orders = orders;
		this.geocoder = geocoder;
		this.zones = zones;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public Order handle(ActorContext actor, UUID orderId, long expectedVersion, Command command) {
		actor.requirePermission("order.create");
		if (command.weightG() == null) {
			throw BusinessException.validation("package.weightG", "REQUIRED", "package.weightG is required.");
		}
		Order current = orders.findByIdForUpdate(orderId)
				.filter(o -> o.customerId().equals(actor.userId()))
				.orElseThrow(() -> BusinessException.notFound("Order"));
		if (current.version() != expectedVersion) {
			throw BusinessException.versionConflict(current.version());
		}

		Order revised = current.revise(command.pickupAddress(), command.pickup(), command.dropoffAddress(),
				command.dropoff(), command.recipientName(), command.recipientContact(), command.weightG(),
				command.lengthCm(), command.widthCm(), command.heightCm(), command.packageNote(),
				command.windowStart(), command.windowEnd());
		GeoPoint pickup = command.pickup() != null ? command.pickup()
				: geocoder.geocode(command.pickupAddress()).orElse(null);
		GeoPoint dropoff = command.dropoff() != null ? command.dropoff()
				: geocoder.geocode(command.dropoffAddress()).orElse(null);
		revised = revised.withPoints(pickup, dropoff);
		if (pickup != null) {
			var match = zones.findZone(pickup);
			if (match.isPresent()) {
				revised = revised.withZone(match.get().zoneId(), match.get().zoneVersionId());
			}
		}
		if (current.status() == OrderStatus.READY_FOR_DISPATCH) {
			revised.requireReadyData();
		}

		orders.updateState(revised, clock.now());
		audit.append(AuditEntry.forUser(actor, AuditAction.ORDER_UPDATED, "ORDER", orderId, revised.zoneId(),
				summary(current), summary(revised), null, null));
		return orders.findById(orderId).orElseThrow();
	}

	private static Map<String, Object> summary(Order o) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("status", o.status().name());
		state.put("pickupAddress", o.pickupAddress());
		state.put("dropoffAddress", o.dropoffAddress());
		state.put("weightG", o.weightG());
		state.put("windowStart", o.windowStart().toString());
		state.put("windowEnd", o.windowEnd().toString());
		return state;
	}
}
