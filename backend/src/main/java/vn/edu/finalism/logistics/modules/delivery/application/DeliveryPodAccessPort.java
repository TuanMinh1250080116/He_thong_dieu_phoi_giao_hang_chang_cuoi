package vn.edu.finalism.logistics.modules.delivery.application;

import java.util.Map;
import java.util.UUID;

public interface DeliveryPodAccessPort {

	PodTarget lockForPod(UUID deliveryId, UUID stopId);

	void markStopCompleted(UUID deliveryId, UUID stopId, UUID actorId);

	record PodTarget(UUID deliveryId, UUID stopId, UUID orderId, String deliveryStatus, String stopStatus,
			UUID assignedDriverId, UUID podPolicyVersionId, Map<String, Object> podPolicySnapshot) {
	}
}
