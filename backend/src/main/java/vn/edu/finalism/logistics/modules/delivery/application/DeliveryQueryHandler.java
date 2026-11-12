package vn.edu.finalism.logistics.modules.delivery.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStop;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;

@Service
public class DeliveryQueryHandler {

	private final DeliveryRepository deliveries;
	private final DriverAvailabilityService drivers;
	private final OrderLifecycleService orders;

	DeliveryQueryHandler(DeliveryRepository deliveries, DriverAvailabilityService drivers,
			OrderLifecycleService orders) {
		this.deliveries = deliveries;
		this.drivers = drivers;
		this.orders = orders;
	}

	@Transactional(readOnly = true)
	public Delivery get(ActorContext actor, UUID deliveryId) {
		Delivery delivery = deliveries.findById(deliveryId).orElseThrow(() -> BusinessException.notFound("Delivery"));
		boolean ownDriver = actor.hasPermission("delivery.execute")
				&& drivers.findByUserId(actor.userId()).map(d -> d.id().equals(delivery.assignedDriverId())).orElse(false);
		if (ownDriver) {
			return delivery;
		}
		if (actor.hasPermission("delivery.monitor")) {
			List<UUID> orderIds = delivery.stops().stream().map(DeliveryStop::orderId).distinct().toList();
			UUID zoneId = orders.get(orderIds).get(0).zoneId();
			if (zoneId != null && actor.hasScope(zoneId)) {
				return delivery;
			}
		}
		throw BusinessException.notFound("Delivery");
	}
}
