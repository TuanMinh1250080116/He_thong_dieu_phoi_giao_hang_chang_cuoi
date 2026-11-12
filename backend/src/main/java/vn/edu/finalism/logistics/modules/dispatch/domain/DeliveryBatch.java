package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.BusinessException;

public record DeliveryBatch(
		UUID id,
		String code,
		UUID zoneId,
		BatchStatus status,
		Instant plannedStart,
		Instant plannedEnd,
		List<UUID> orderIds,
		long totalWeightG,
		long totalVolumeCm3,
		UUID createdBy,
		long version) {

	public static final int MAX_ORDERS = 50;

	public static DeliveryBatch draft(UUID id, String code, UUID zoneId, UUID createdBy, List<UUID> orderIds,
			long totalWeightG, long totalVolumeCm3) {
		if (orderIds.isEmpty() || orderIds.size() > MAX_ORDERS) {
			throw BusinessException.validation("orderIds", "SIZE_OUT_OF_RANGE",
					"A batch needs between 1 and " + MAX_ORDERS + " orders.");
		}
		return new DeliveryBatch(id, code, zoneId, BatchStatus.DRAFT, null, null, List.copyOf(orderIds),
				totalWeightG, totalVolumeCm3, createdBy, 0);
	}

	public DeliveryBatch lock(Instant windowStart, Instant windowEnd) {
		if (status != BatchStatus.DRAFT) {
			throw BusinessException.stateNotAllowed("Only a draft batch can be locked.");
		}
		if (!windowEnd.isAfter(windowStart)) {
			throw BusinessException.stateNotAllowed("The batch time window is empty.");
		}
		return new DeliveryBatch(id, code, zoneId, BatchStatus.LOCKED, windowStart, windowEnd, orderIds,
				totalWeightG, totalVolumeCm3, createdBy, version);
	}

	public DeliveryBatch withMembers(List<UUID> newOrderIds, long weightG, long volumeCm3) {
		if (status != BatchStatus.DRAFT) {
			throw BusinessException.stateNotAllowed("Only a draft batch can change its orders. Reopen it first.");
		}
		if (newOrderIds.isEmpty() || newOrderIds.size() > MAX_ORDERS) {
			throw BusinessException.validation("orderIds", "SIZE_OUT_OF_RANGE",
					"A batch needs between 1 and " + MAX_ORDERS + " orders.");
		}
		return new DeliveryBatch(id, code, zoneId, status, null, null, List.copyOf(newOrderIds), weightG, volumeCm3,
				createdBy, version);
	}

	public void requireReopenable() {
		if (status != BatchStatus.LOCKED) {
			throw BusinessException.stateNotAllowed("Only a locked batch can be reopened.");
		}
	}

	public void requireCancellable() {
		if (status != BatchStatus.DRAFT && status != BatchStatus.LOCKED) {
			throw BusinessException.stateNotAllowed("Only a draft or locked batch can be cancelled.");
		}
	}

	public boolean isLocked() {
		return status == BatchStatus.LOCKED;
	}
}
