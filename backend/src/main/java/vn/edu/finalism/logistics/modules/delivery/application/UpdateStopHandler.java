package vn.edu.finalism.logistics.modules.delivery.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery.StopChange;
import vn.edu.finalism.logistics.modules.delivery.domain.StopAction;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService.DriverSummary;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class UpdateStopHandler {

	private final DeliveryRepository deliveries;
	private final DeliveryAccess access;
	private final ClockPort clock;

	UpdateStopHandler(DeliveryRepository deliveries, DeliveryAccess access, ClockPort clock) {
		this.deliveries = deliveries;
		this.access = access;
		this.clock = clock;
	}

	@Transactional
	public Delivery handle(ActorContext actor, UUID deliveryId, UUID stopId, StopAction action,
			long expectedVersion) {
		DriverSummary driver = access.requireDriver(actor);
		Delivery delivery = access.requireOwnedBy(driver, deliveries.findByIdForUpdate(deliveryId)
				.orElseThrow(() -> BusinessException.notFound("Delivery")));
		DeliveryAccess.requireVersion(delivery, expectedVersion);

		if (action == StopAction.RESUME_AFTER_EXCEPTION && deliveries.hasOpenBlockingForStop(stopId)) {
			throw BusinessException.stateNotAllowed("The problem on this stop has not been resolved yet.");
		}

		Instant now = clock.now();
		StopChange change = delivery.performStopAction(stopId, action, now);
		deliveries.updateStop(change.stop());
		deliveries.insertStopTransition(stopId, deliveryId, change.from().name(), change.stop().status().name(),
				action.name(), actor.userId(), now);
		if (!deliveries.update(change.delivery(), expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		return deliveries.findById(deliveryId).orElseThrow();
	}
}
