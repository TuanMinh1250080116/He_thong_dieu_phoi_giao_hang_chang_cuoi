package vn.edu.finalism.logistics.modules.dispatch.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.dispatch.domain.DriverFacts;
import vn.edu.finalism.logistics.shared.GeoPoint;

public interface DriverCandidateReadPort {

	GeoPoint pickupCentroid(UUID batchId);

	List<DriverFacts> loadCandidates(UUID batchId, UUID zoneId, GeoPoint pickup, int radiusM, Instant windowStart,
			Instant windowEnd);

	Optional<DriverFacts> loadOne(UUID batchId, UUID driverId, UUID zoneId, GeoPoint pickup, Instant windowStart,
			Instant windowEnd);
}
