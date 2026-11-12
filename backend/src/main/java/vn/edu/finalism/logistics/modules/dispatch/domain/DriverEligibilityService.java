package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class DriverEligibilityService {

	public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
	public static final String VEHICLE_INACTIVE = "VEHICLE_INACTIVE";
	public static final String VEHICLE_CAPACITY = "VEHICLE_CAPACITY";
	public static final String ASSIGNMENT_CONFLICT = "ASSIGNMENT_CONFLICT";
	public static final String ZONE_INCOMPATIBLE = "ZONE_INCOMPATIBLE";
	public static final String LOCATION_STALE = "LOCATION_STALE";

	private DriverEligibilityService() {
	}

	public record Requirements(long weightG, long volumeCm3) {
	}

	public static List<String> reasonsAgainst(DriverFacts driver, Requirements need, Instant now,
			int maxLocationAgeSeconds) {
		List<String> reasons = new ArrayList<>();
		if (!driver.active() || !"AVAILABLE".equals(driver.availability())) {
			reasons.add(NOT_AVAILABLE);
		}
		if (driver.vehicleId() == null || !driver.vehicleActive()) {
			reasons.add(VEHICLE_INACTIVE);
		}
		else if (driver.capacityWeightG() < need.weightG() || driver.capacityVolumeCm3() < need.volumeCm3()) {
			reasons.add(VEHICLE_CAPACITY);
		}
		if (driver.hasConflict()) {
			reasons.add(ASSIGNMENT_CONFLICT);
		}
		if (!driver.servesZone()) {
			reasons.add(ZONE_INCOMPATIBLE);
		}
		if (driver.locationRecordedAt() == null
				|| Duration.between(driver.locationRecordedAt(), now).getSeconds() > maxLocationAgeSeconds) {
			reasons.add(LOCATION_STALE);
		}
		return reasons;
	}
}
