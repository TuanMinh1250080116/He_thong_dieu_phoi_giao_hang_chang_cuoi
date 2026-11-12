package vn.edu.finalism.logistics.modules.delivery.domain;

import java.time.Instant;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.GeoPoint;

public record DeliveryStop(
		UUID id,
		UUID deliveryId,
		UUID orderId,
		int sequenceNo,
		String addressText,
		GeoPoint location,
		Instant windowStart,
		Instant windowEnd,
		StopStatus status,
		Instant arrivedAt,
		Instant completedAt) {

	public boolean isDone() {
		return status == StopStatus.COMPLETED || status == StopStatus.SKIPPED;
	}

	public DeliveryStop withStatus(StopStatus next) {
		return new DeliveryStop(id, deliveryId, orderId, sequenceNo, addressText, location, windowStart, windowEnd,
				next, arrivedAt, completedAt);
	}

	public DeliveryStop arrivedAt(Instant when) {
		return new DeliveryStop(id, deliveryId, orderId, sequenceNo, addressText, location, windowStart, windowEnd,
				status, when, completedAt);
	}

	public DeliveryStop completedAt(Instant when) {
		return new DeliveryStop(id, deliveryId, orderId, sequenceNo, addressText, location, windowStart, windowEnd,
				StopStatus.COMPLETED, arrivedAt, when);
	}
}
