package vn.edu.finalism.logistics.modules.driverfleet.application;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository.DriverRow;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository.LocationRow;
import vn.edu.finalism.logistics.modules.driverfleet.domain.Availability;
import vn.edu.finalism.logistics.modules.driverfleet.domain.Driver;
import vn.edu.finalism.logistics.platform.ratelimit.RateLimiter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Service
public class DriverSelfHandler {

	private static final Set<String> CLIENT_STATES = Set.of("AVAILABLE", "OFFLINE");

	public record Profile(DriverRow driver, LocationRow location, int pendingAssignments) {
	}

	private final FleetRepository fleet;
	private final RateLimiter rateLimiter;
	private final AuditWriter audit;
	private final ClockPort clock;

	DriverSelfHandler(FleetRepository fleet, RateLimiter rateLimiter, AuditWriter audit, ClockPort clock) {
		this.fleet = fleet;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Profile me(ActorContext actor) {
		actor.requirePermission("delivery.execute");
		DriverRow driver = own(actor);
		return new Profile(driver, fleet.findLocation(driver.id()).orElse(null),
				fleet.pendingAssignmentCount(driver.id()));
	}

	@Transactional
	public Profile changeAvailability(ActorContext actor, long expectedVersion, String target) {
		actor.requirePermission("delivery.execute");
		if (target == null || !CLIENT_STATES.contains(target)) {
			throw BusinessException.validation("availability", "INVALID",
					"availability must be AVAILABLE or OFFLINE. Reserved and busy are set by the system.");
		}
		DriverRow row = own(actor);
		DriverRow locked = fleet.findDriverForUpdate(row.id()).orElseThrow();
		if (locked.version() != expectedVersion) {
			throw BusinessException.versionConflict(locked.version());
		}
		Driver driver = new Driver(locked.id(), locked.userId(), locked.code(), locked.fullName(), locked.active(),
				Availability.valueOf(locked.availability()), locked.vehicleId(), locked.version());
		Availability before = driver.availability();
		Availability after;
		if ("AVAILABLE".equals(target)) {
			boolean openObligation = !fleet.openAssignmentIdsOfDriver(driver.id()).isEmpty();
			after = before == Availability.AVAILABLE ? before : driver.goOnline(openObligation).availability();
		}
		else {
			after = before == Availability.OFFLINE ? before : driver.goOffline().availability();
		}
		if (after != before) {
			if (!fleet.setDriverAvailability(driver.id(), after.name(), expectedVersion, clock.now())) {
				throw BusinessException.versionConflict(expectedVersion + 1);
			}
			audit.append(AuditEntry.forUser(actor, AuditAction.DRIVER_AVAILABILITY_CHANGED, "DRIVER", driver.id(), null,
					Map.of("availability", before.name()), Map.of("availability", after.name()), null, null));
		}
		return me(actor);
	}

	@Transactional
	public void updateLocation(ActorContext actor, Double lat, Double lng, Double accuracyM) {
		actor.requirePermission("delivery.execute");
		if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
			throw BusinessException.validation("point", "OUT_OF_RANGE",
					"Latitude must be -90..90 and longitude -180..180.");
		}
		if (accuracyM != null && (accuracyM < 0 || accuracyM > 100_000)) {
			throw BusinessException.validation("accuracyM", "OUT_OF_RANGE", "accuracyM must be between 0 and 100000.");
		}
		DriverRow driver = own(actor);
		if (!driver.active()) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "The driver account is inactive.");
		}
		rateLimiter.check("driver-location:" + actor.userId(), 6, Duration.ofMinutes(1));
		fleet.upsertLocation(driver.id(), lat, lng, accuracyM, clock.now().truncatedTo(ChronoUnit.MILLIS));
	}

	private DriverRow own(ActorContext actor) {
		return fleet.findDriverByUser(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
	}
}
