package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.time.Instant;
import java.util.UUID;

public record DriverFacts(
		UUID driverId,
		UUID userId,
		String fullName,
		boolean active,
		String availability,
		UUID vehicleId,
		String vehicleType,
		boolean vehicleActive,
		Integer capacityWeightG,
		Long capacityVolumeCm3,
		boolean servesZone,
		Instant locationRecordedAt,
		Double distanceM,
		boolean hasConflict,
		int recentAssignments,
		double zoneCoverage) {
}
