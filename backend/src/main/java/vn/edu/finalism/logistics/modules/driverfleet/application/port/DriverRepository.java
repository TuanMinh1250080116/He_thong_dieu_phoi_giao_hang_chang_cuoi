package vn.edu.finalism.logistics.modules.driverfleet.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.driverfleet.domain.Driver;

public interface DriverRepository {

	Optional<Driver> findByUserId(UUID userId);

	Optional<Driver> findById(UUID driverId);

	Optional<Driver> findByIdForUpdate(UUID driverId);

	void saveAvailability(Driver driver, Instant now);
}
