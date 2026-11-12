package vn.edu.finalism.logistics.modules.delivery.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
class DeliveryLifecycleService implements DeliveryLifecyclePort {

	private final DeliveryRepository deliveries;
	private final ClockPort clock;

	DeliveryLifecycleService(DeliveryRepository deliveries, ClockPort clock) {
		this.deliveries = deliveries;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID markAcceptedByAssignment(UUID assignmentId) {
		Delivery delivery = deliveries.findByAssignmentIdForUpdate(assignmentId)
				.orElseThrow(() -> BusinessException.notFound("Delivery"));
		return save(delivery, delivery.accept());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID markAwaitingReassignment(UUID assignmentId) {
		Delivery delivery = deliveries.findByAssignmentIdForUpdate(assignmentId)
				.orElseThrow(() -> BusinessException.notFound("Delivery"));
		return save(delivery, delivery.awaitReassignment());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID reassign(UUID decisionId, UUID assignmentId, UUID driverId, UUID vehicleId) {
		Delivery delivery = deliveries.findOpenByDecisionIdForUpdate(decisionId)
				.orElseThrow(() -> BusinessException.notFound("Delivery"));
		return save(delivery, delivery.reassign(assignmentId, driverId, vehicleId));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<UUID> cancelByDecision(UUID decisionId, String reason) {
		Optional<Delivery> found = deliveries.findOpenByDecisionIdForUpdate(decisionId);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Delivery delivery = found.get();
		delivery.cancel();
		if (!deliveries.cancel(delivery.id(), delivery.version(), reason, clock.now())) {
			throw BusinessException.versionConflict(delivery.version() + 1);
		}
		return Optional.of(delivery.id());
	}

	private UUID save(Delivery before, Delivery after) {
		if (!deliveries.update(after, before.version(), clock.now())) {
			throw BusinessException.versionConflict(before.version() + 1);
		}
		return before.id();
	}
}
