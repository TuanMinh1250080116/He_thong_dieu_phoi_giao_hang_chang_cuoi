package vn.edu.finalism.logistics.modules.exception.application;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionView;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;

@Component
class ExceptionAccess {

	private final ExceptionRepository exceptions;
	private final DriverAvailabilityService drivers;

	ExceptionAccess(ExceptionRepository exceptions, DriverAvailabilityService drivers) {
		this.exceptions = exceptions;
		this.drivers = drivers;
	}

	ExceptionView requireVisible(ActorContext actor, UUID exceptionId) {
		ExceptionView view = exceptions.findView(exceptionId).orElseThrow(() -> BusinessException.notFound("Exception"));
		if (!canSee(actor, view)) {
			throw BusinessException.notFound("Exception");
		}
		return view;
	}

	boolean canSee(ActorContext actor, ExceptionView view) {
		return actor.hasScope(view.zoneId()) || isAssignedDriver(actor, view);
	}

	boolean isAssignedDriver(ActorContext actor, ExceptionView view) {
		return view.assignedDriverId() != null && drivers.findByUserId(actor.userId())
				.map(d -> d.id().equals(view.assignedDriverId())).orElse(false);
	}

	UUID driverIdOf(ActorContext actor) {
		return drivers.findByUserId(actor.userId()).map(d -> d.id()).orElse(null);
	}
}
