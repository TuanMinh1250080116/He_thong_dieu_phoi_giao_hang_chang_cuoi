package vn.edu.finalism.logistics.modules.delivery.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.GeoPoint;

public interface DeliveryCreationPort {

	UUID createAssigned(NewDelivery delivery);

	record NewDelivery(UUID batchId, UUID decisionId, UUID assignmentId, UUID driverId, UUID vehicleId,
			List<StopSeed> stops) {
	}

	record StopSeed(UUID orderId, String addressText, GeoPoint location, Instant windowStart, Instant windowEnd) {
	}
}
