package vn.edu.finalism.logistics.modules.delivery.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStop;
import vn.edu.finalism.logistics.modules.delivery.domain.StopStatus;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
class DeliveryCreationService implements DeliveryCreationPort {

	private final DeliveryRepository deliveries;
	private final ClockPort clock;

	DeliveryCreationService(DeliveryRepository deliveries, ClockPort clock) {
		this.deliveries = deliveries;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID createAssigned(NewDelivery request) {
		if (request.stops().isEmpty()) {
			throw BusinessException.validation("stops", "REQUIRED", "A delivery needs at least one stop.");
		}
		UUID deliveryId = UUID.randomUUID();
		List<DeliveryStop> stops = new ArrayList<>();
		int sequence = 1;
		for (StopSeed seed : request.stops()) {
			stops.add(new DeliveryStop(UUID.randomUUID(), deliveryId, seed.orderId(), sequence++, seed.addressText(),
					seed.location(), seed.windowStart(), seed.windowEnd(), StopStatus.PENDING, null, null));
		}
		Delivery delivery = Delivery.assigned(deliveryId, deliveries.nextCode(), request.batchId(),
				request.decisionId(), request.assignmentId(), request.driverId(), request.vehicleId(), stops);
		deliveries.insert(delivery, clock.now());
		return deliveryId;
	}
}
