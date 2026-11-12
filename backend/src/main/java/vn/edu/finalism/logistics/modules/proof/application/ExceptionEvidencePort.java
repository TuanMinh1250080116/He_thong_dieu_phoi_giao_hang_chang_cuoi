package vn.edu.finalism.logistics.modules.proof.application;

import java.util.List;
import java.util.UUID;

public interface ExceptionEvidencePort {

	List<UUID> attachForException(List<UUID> evidenceIds, UUID ownerUserId, UUID deliveryId);
}
