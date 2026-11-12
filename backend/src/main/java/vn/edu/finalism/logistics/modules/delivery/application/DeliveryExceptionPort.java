package vn.edu.finalism.logistics.modules.delivery.application;

import java.util.List;
import java.util.UUID;

public interface DeliveryExceptionPort {

	ExceptionTarget lockForException(UUID deliveryId);

	void registerBlocking(UUID exceptionId, UUID deliveryId, UUID stopId, String scope, UUID actorId);

	void clearBlocking(UUID exceptionId);

	record StopRef(UUID id, UUID orderId, String status) {
	}

	record ExceptionTarget(UUID deliveryId, String code, String status, UUID assignedDriverId, List<StopRef> stops) {
	}
}
