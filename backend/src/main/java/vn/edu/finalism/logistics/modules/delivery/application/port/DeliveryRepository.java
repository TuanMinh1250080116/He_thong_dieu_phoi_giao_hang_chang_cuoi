package vn.edu.finalism.logistics.modules.delivery.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStop;

public interface DeliveryRepository {

	String nextCode();

	void insert(Delivery delivery, Instant now);

	Optional<Delivery> findById(UUID id);

	Optional<Delivery> findByIdForUpdate(UUID id);

	Optional<Delivery> findByAssignmentIdForUpdate(UUID assignmentId);

	Optional<Delivery> findOpenByDecisionIdForUpdate(UUID decisionId);

	boolean cancel(UUID id, long expectedVersion, String reason, Instant now);

	boolean update(Delivery delivery, long expectedVersion, Instant now);

	List<UUID> openBlockingExceptionIds(UUID deliveryId);

	boolean hasOpenBlockingForStop(UUID stopId);

	void insertBlockingRef(UUID exceptionId, UUID deliveryId, UUID stopId, String scope, Instant now);

	Optional<UUID> clearBlockingRef(UUID exceptionId, Instant now);

	void bumpVersion(UUID deliveryId, Instant now);

	void updateStop(DeliveryStop stop);

	void insertStopTransition(UUID stopId, UUID deliveryId, String from, String to, String action, UUID actorId,
			Instant now);
}
