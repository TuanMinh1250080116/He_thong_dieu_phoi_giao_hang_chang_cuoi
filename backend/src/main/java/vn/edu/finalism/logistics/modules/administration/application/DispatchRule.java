package vn.edu.finalism.logistics.modules.administration.application;

import java.math.BigDecimal;
import java.util.UUID;

public record DispatchRule(
		UUID id,
		int versionNo,
		BigDecimal weightDistance,
		BigDecimal weightCapacityFit,
		BigDecimal weightAvailability,
		BigDecimal weightZoneCompat,
		int ttlSeconds,
		int searchRadiusM,
		int maxLocationAgeSeconds,
		int persistTopN) {
}
