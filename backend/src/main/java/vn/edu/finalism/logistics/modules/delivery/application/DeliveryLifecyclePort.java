package vn.edu.finalism.logistics.modules.delivery.application;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryLifecyclePort {

	UUID markAcceptedByAssignment(UUID assignmentId);

	UUID markAwaitingReassignment(UUID assignmentId);

	UUID reassign(UUID decisionId, UUID assignmentId, UUID driverId, UUID vehicleId);

	Optional<UUID> cancelByDecision(UUID decisionId, String reason);
}
