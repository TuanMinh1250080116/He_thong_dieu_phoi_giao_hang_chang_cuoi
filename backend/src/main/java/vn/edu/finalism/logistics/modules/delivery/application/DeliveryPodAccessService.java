package vn.edu.finalism.logistics.modules.delivery.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery.StopChange;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStop;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
class DeliveryPodAccessService implements DeliveryPodAccessPort {

	private final DeliveryRepository deliveries;
	private final ClockPort clock;

	DeliveryPodAccessService(DeliveryRepository deliveries, ClockPort clock) {
		this.deliveries = deliveries;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public PodTarget lockForPod(UUID deliveryId, UUID stopId) {
		Delivery delivery = deliveries.findByIdForUpdate(deliveryId)
				.orElseThrow(() -> BusinessException.notFound("Delivery"));
		DeliveryStop stop = delivery.stop(stopId);
		return new PodTarget(delivery.id(), stop.id(), stop.orderId(), delivery.status().name(),
				stop.status().name(), delivery.assignedDriverId(), delivery.podPolicyVersionId(),
				delivery.podPolicySnapshot());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void markStopCompleted(UUID deliveryId, UUID stopId, UUID actorId) {
		Instant now = clock.now();
		Delivery delivery = deliveries.findByIdForUpdate(deliveryId)
				.orElseThrow(() -> BusinessException.notFound("Delivery"));
		StopChange change = delivery.completeStop(stopId, now);
		deliveries.updateStop(change.stop());
		deliveries.insertStopTransition(stopId, deliveryId, change.from().name(), change.stop().status().name(),
				"SUBMIT_POD", actorId, now);
		if (!deliveries.update(change.delivery(), delivery.version(), now)) {
			throw BusinessException.versionConflict(delivery.version() + 1);
		}
	}
}
