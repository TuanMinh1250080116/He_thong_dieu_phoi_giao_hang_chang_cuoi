package vn.edu.finalism.logistics.modules.proof.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.GeoPoint;

public record ProofOfDelivery(
		UUID id,
		UUID deliveryId,
		UUID stopId,
		UUID orderId,
		Instant capturedAt,
		GeoPoint gps,
		double gpsAccuracyM,
		String recipientName,
		boolean directHandoff,
		String note,
		UUID policyVersionId,
		UUID submittedBy,
		List<String> evidenceKinds,
			UUID otpChallengeId) {
}
