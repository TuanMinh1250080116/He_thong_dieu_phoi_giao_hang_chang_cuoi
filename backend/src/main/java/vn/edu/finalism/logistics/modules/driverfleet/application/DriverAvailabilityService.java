package vn.edu.finalism.logistics.modules.driverfleet.application;

import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.driverfleet.application.port.DriverRepository;
import vn.edu.finalism.logistics.modules.driverfleet.domain.Driver;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class DriverAvailabilityService {

	private final DriverRepository drivers;
	private final ClockPort clock;

	DriverAvailabilityService(DriverRepository drivers, ClockPort clock) {
		this.drivers = drivers;
		this.clock = clock;
	}

	public record DriverSummary(UUID id, UUID userId, String fullName, UUID vehicleId) {
	}

	@Transactional(readOnly = true)
	public Optional<DriverSummary> findByUserId(UUID userId) {
		return drivers.findByUserId(userId).map(DriverAvailabilityService::summary);
	}

	@Transactional(readOnly = true)
	public Optional<DriverSummary> findById(UUID driverId) {
		return drivers.findById(driverId).map(DriverAvailabilityService::summary);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(UUID driverId) {
		change(driverId, Driver::reserve);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void markBusy(UUID driverId) {
		change(driverId, Driver::markBusy);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID driverId) {
		change(driverId, Driver::release);
	}

	private void change(UUID driverId, UnaryOperator<Driver> transition) {
		Driver driver = drivers.findByIdForUpdate(driverId)
				.orElseThrow(() -> BusinessException.notFound("Driver"));
		drivers.saveAvailability(transition.apply(driver), clock.now());
	}

	private static DriverSummary summary(Driver driver) {
		return new DriverSummary(driver.id(), driver.userId(), driver.fullName(), driver.vehicleId());
	}
}
