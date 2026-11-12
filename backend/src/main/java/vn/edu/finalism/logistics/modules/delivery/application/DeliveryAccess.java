package vn.edu.finalism.logistics.modules.delivery.application;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService.DriverSummary;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Component
class DeliveryAccess {

	private final DriverAvailabilityService drivers;

	DeliveryAccess(DriverAvailabilityService drivers) {
		this.drivers = drivers;
	}

	DriverSummary requireDriver(ActorContext actor) {
		actor.requirePermission("delivery.execute");
		return drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
	}

	Delivery requireOwnedBy(DriverSummary driver, Delivery delivery) {
		if (!driver.id().equals(delivery.assignedDriverId())) {
			throw BusinessException.notFound("Delivery");
		}
		return delivery;
	}

	static void requireVersion(Delivery delivery, long expected) {
		if (delivery.version() != expected) {
			throw BusinessException.versionConflict(delivery.version());
		}
	}

	UUID driverUserId(UUID driverId) {
		return drivers.findById(driverId).orElseThrow(() -> BusinessException.notFound("Driver")).userId();
	}
}
