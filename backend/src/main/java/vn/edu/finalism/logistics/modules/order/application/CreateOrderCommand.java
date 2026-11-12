package vn.edu.finalism.logistics.modules.order.application;

import java.time.Instant;

import vn.edu.finalism.logistics.shared.GeoPoint;

public record CreateOrderCommand(
		SaveMode saveMode,
		String pickupAddress,
		GeoPoint pickup,
		String dropoffAddress,
		GeoPoint dropoff,
		String recipientName,
		String recipientContact,
		Integer weightG,
		Integer lengthCm,
		Integer widthCm,
		Integer heightCm,
		String packageNote,
		Instant windowStart,
		Instant windowEnd) {

	public enum SaveMode {
		DRAFT, SUBMIT
	}
}
