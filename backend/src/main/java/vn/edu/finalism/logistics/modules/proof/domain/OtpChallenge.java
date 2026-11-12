package vn.edu.finalism.logistics.modules.proof.domain;

import java.time.Instant;
import java.util.UUID;

public record OtpChallenge(
		UUID id,
		UUID deliveryId,
		UUID stopId,
		String status,
		int attempts,
		int maxAttempts,
		Instant expiresAt) {
}
