package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record Candidate(
		int rank,
		UUID driverId,
		String driverName,
		UUID vehicleId,
		String vehicleType,
		BigDecimal totalScore,
		double distanceM,
		BigDecimal scoreDistance,
		BigDecimal scoreCapacityFit,
		BigDecimal scoreAvailability,
		BigDecimal scoreZoneCompat,
		int vehicleCapacityWeightG,
		long vehicleCapacityVolumeCm3) {
}
