package vn.edu.finalism.logistics.modules.proof.domain;

import java.time.Instant;
import java.util.UUID;

public record EvidenceObject(
		UUID id,
		String purpose,
		String kind,
		UUID ownerUserId,
		UUID deliveryId,
		UUID stopId,
		String storageKey,
		String declaredMime,
		String detectedMime,
		Long sizeBytes,
		String status,
		Instant createdAt,
		Instant expiresAt) {
}
