package vn.edu.finalism.logistics.modules.dispatch.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;

public interface BatchRepository {

	String nextCode();

	void insert(DeliveryBatch batch, Instant now);

	void addMembers(UUID batchId, Collection<UUID> orderIds, UUID addedBy, Instant now);

	Optional<DeliveryBatch> findById(UUID id);

	Optional<DeliveryBatch> findByIdForUpdate(UUID id);

	void markLocked(DeliveryBatch locked, UUID lockedBy, Instant now);

	void markCompleted(UUID batchId, Instant now);

	void applyMembership(DeliveryBatch updated, Collection<UUID> added, Collection<UUID> removed, UUID by, Instant now);

	void markReopened(UUID batchId, Instant now);

	void markCancelled(UUID batchId, String reason, Instant now);

	Optional<UUID> findActiveBatchIdOfOrder(UUID orderId);
}
