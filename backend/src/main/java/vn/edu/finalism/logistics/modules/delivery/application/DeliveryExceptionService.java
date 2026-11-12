package vn.edu.finalism.logistics.modules.delivery.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery.StopChange;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
class DeliveryExceptionService implements DeliveryExceptionPort {

	private final DeliveryRepository deliveries;
	private final ClockPort clock;

	DeliveryExceptionService(DeliveryRepository deliveries, ClockPort clock) {
		this.deliveries = deliveries;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public ExceptionTarget lockForException(UUID deliveryId) {
		Delivery delivery = deliveries.findByIdForUpdate(deliveryId)
				.orElseThrow(() -> BusinessException.notFound("Delivery"));
		return new ExceptionTarget(delivery.id(), delivery.code(), delivery.status().name(), delivery.assignedDriverId(),
				delivery.stops().stream().map(s -> new StopRef(s.id(), s.orderId(), s.status().name())).toList());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void registerBlocking(UUID exceptionId, UUID deliveryId, UUID stopId, String scope, UUID actorId) {
		Instant now = clock.now();
		Delivery delivery = deliveries.findByIdForUpdate(deliveryId)
				.orElseThrow(() -> BusinessException.notFound("Delivery"));
		if ("STOP".equals(scope)) {
			StopChange change = delivery.blockStop(stopId);
			deliveries.updateStop(change.stop());
			deliveries.insertStopTransition(stopId, deliveryId, change.from().name(), change.stop().status().name(),
					"REPORT_EXCEPTION", actorId, now);
		}
		deliveries.insertBlockingRef(exceptionId, deliveryId, "STOP".equals(scope) ? stopId : null, scope, now);
		deliveries.bumpVersion(deliveryId, now);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void clearBlocking(UUID exceptionId) {
		Instant now = clock.now();
		deliveries.clearBlockingRef(exceptionId, now).ifPresent(deliveryId -> deliveries.bumpVersion(deliveryId, now));
	}
}
