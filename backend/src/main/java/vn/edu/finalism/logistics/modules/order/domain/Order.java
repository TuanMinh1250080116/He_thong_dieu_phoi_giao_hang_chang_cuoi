package vn.edu.finalism.logistics.modules.order.domain;

import java.time.Instant;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.GeoPoint;

public record Order(
		UUID id,
		String code,
		UUID customerId,
		OrderStatus status,
		String pickupAddress,
		GeoPoint pickup,
		String dropoffAddress,
		GeoPoint dropoff,
		String recipientName,
		String recipientContact,
		int weightG,
		Integer lengthCm,
		Integer widthCm,
		Integer heightCm,
		Long volumeCm3,
		String packageNote,
		Instant windowStart,
		Instant windowEnd,
		UUID zoneId,
		UUID zoneVersionId,
		long version,
		OrderStatus cancelledFrom,
		String cancelReason) {

	public static Order create(UUID id, String code, UUID customerId, String pickupAddress, GeoPoint pickup,
			String dropoffAddress, GeoPoint dropoff, String recipientName, String recipientContact,
			int weightG, Integer lengthCm, Integer widthCm, Integer heightCm, String packageNote,
			Instant windowStart, Instant windowEnd) {
		Long volume = validateContent(pickupAddress, dropoffAddress, recipientName, weightG, lengthCm, widthCm,
				heightCm, windowStart, windowEnd);
		return new Order(id, code, customerId, OrderStatus.DRAFT, pickupAddress.trim(), pickup,
				dropoffAddress.trim(), dropoff, recipientName.trim(), recipientContact, weightG,
				lengthCm, widthCm, heightCm, volume, packageNote, windowStart, windowEnd, null, null, 0, null, null);
	}

	public boolean isEditable() {
		return status == OrderStatus.DRAFT || status == OrderStatus.READY_FOR_DISPATCH;
	}

	public boolean isCancellableByOwner() {
		return status == OrderStatus.DRAFT || status == OrderStatus.READY_FOR_DISPATCH;
	}

	public Order revise(String pickupAddress, GeoPoint pickup, String dropoffAddress, GeoPoint dropoff,
			String recipientName, String recipientContact, int weightG, Integer lengthCm, Integer widthCm,
			Integer heightCm, String packageNote, Instant windowStart, Instant windowEnd) {
		if (!isEditable()) {
			throw BusinessException.stateNotAllowed(
					"The order can no longer be edited because it is already in a dispatch batch or finished.");
		}
		Long volume = validateContent(pickupAddress, dropoffAddress, recipientName, weightG, lengthCm, widthCm,
				heightCm, windowStart, windowEnd);
		return new Order(id, code, customerId, status, pickupAddress.trim(), pickup, dropoffAddress.trim(), dropoff,
				recipientName.trim(), recipientContact, weightG, lengthCm, widthCm, heightCm, volume, packageNote,
				windowStart, windowEnd, null, null, version, null, null);
	}

	public Order withZone(UUID zoneId, UUID zoneVersionId) {
		return new Order(id, code, customerId, status, pickupAddress, pickup, dropoffAddress, dropoff,
				recipientName, recipientContact, weightG, lengthCm, widthCm, heightCm, volumeCm3, packageNote,
				windowStart, windowEnd, zoneId, zoneVersionId, version, cancelledFrom, cancelReason);
	}

	public Order withPoints(GeoPoint pickupPoint, GeoPoint dropoffPoint) {
		return new Order(id, code, customerId, status, pickupAddress, pickupPoint, dropoffAddress, dropoffPoint,
				recipientName, recipientContact, weightG, lengthCm, widthCm, heightCm, volumeCm3, packageNote,
				windowStart, windowEnd, zoneId, zoneVersionId, version, cancelledFrom, cancelReason);
	}

	public void requireReadyData() {
		if (pickup == null) {
			throw BusinessException.validation("pickup.point", "REQUIRED", "A pickup point is required to submit.");
		}
		if (dropoff == null) {
			throw BusinessException.validation("dropoff.point", "REQUIRED", "A drop-off point is required to submit.");
		}
		if (volumeCm3 == null) {
			throw BusinessException.validation("package", "DIMENSIONS_REQUIRED",
					"Length, width and height are required to submit.");
		}
		if (zoneId == null) {
			throw new BusinessException(ErrorCode.ZONE_UNRESOLVED, "The pickup point is outside every delivery zone.");
		}
	}

	public Order markReady() {
		if (status != OrderStatus.DRAFT) {
			throw BusinessException.stateNotAllowed("Only a draft order can be submitted.");
		}
		requireReadyData();
		return move(OrderStatus.READY_FOR_DISPATCH);
	}

	public Order cancel(String reason) {
		if (!status.allowedNext().contains(OrderStatus.CANCELLED)) {
			throw BusinessException.stateNotAllowed("An order in status " + status + " cannot be cancelled.");
		}
		String text = reason == null ? null : reason.trim();
		if (status != OrderStatus.DRAFT && (text == null || text.isEmpty())) {
			throw BusinessException.validation("reason", "REQUIRED", "A reason is required to cancel this order.");
		}
		return new Order(id, code, customerId, OrderStatus.CANCELLED, pickupAddress, pickup, dropoffAddress, dropoff,
				recipientName, recipientContact, weightG, lengthCm, widthCm, heightCm, volumeCm3, packageNote,
				windowStart, windowEnd, zoneId, zoneVersionId, version, status, text == null || text.isEmpty() ? null : text);
	}

	public Order attachToBatch() {
		return move(OrderStatus.BATCHED);
	}

	public Order releaseFromBatch() {
		if (status != OrderStatus.BATCHED) {
			throw BusinessException.stateNotAllowed("Only an order in a batch can be released.");
		}
		return move(OrderStatus.READY_FOR_DISPATCH);
	}

	public Order markAssigned() {
		return move(OrderStatus.ASSIGNED);
	}

	public Order revertToBatched() {
		return move(OrderStatus.BATCHED);
	}

	public Order markInDelivery() {
		return move(OrderStatus.IN_DELIVERY);
	}

	public Order markDelivered() {
		return move(OrderStatus.DELIVERED);
	}

	private Order move(OrderStatus next) {
		if (!status.allowedNext().contains(next)) {
			throw BusinessException.stateNotAllowed("An order in status " + status + " cannot move to " + next + ".");
		}
		return new Order(id, code, customerId, next, pickupAddress, pickup, dropoffAddress, dropoff,
				recipientName, recipientContact, weightG, lengthCm, widthCm, heightCm, volumeCm3, packageNote,
				windowStart, windowEnd, zoneId, zoneVersionId, version, cancelledFrom, cancelReason);
	}

	private static Long validateContent(String pickupAddress, String dropoffAddress, String recipientName,
			int weightG, Integer lengthCm, Integer widthCm, Integer heightCm, Instant windowStart, Instant windowEnd) {
		require(pickupAddress, "pickup.address");
		require(dropoffAddress, "dropoff.address");
		require(recipientName, "recipient.name");
		if (weightG <= 0) {
			throw BusinessException.validation("package.weightG", "POSITIVE_REQUIRED", "Weight must be greater than 0.");
		}
		if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
			throw BusinessException.validation("timeWindow", "INVALID_RANGE", "timeWindow.end must be after timeWindow.start.");
		}
		if (lengthCm != null && widthCm != null && heightCm != null) {
			if (lengthCm <= 0 || widthCm <= 0 || heightCm <= 0) {
				throw BusinessException.validation("package", "POSITIVE_REQUIRED", "Dimensions must be greater than 0.");
			}
			return (long) lengthCm * widthCm * heightCm;
		}
		return null;
	}

	private static void require(String value, String field) {
		if (value == null || value.isBlank()) {
			throw BusinessException.validation(field, "REQUIRED", field + " is required.");
		}
	}
}
