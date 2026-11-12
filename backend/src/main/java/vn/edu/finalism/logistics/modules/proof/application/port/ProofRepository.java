package vn.edu.finalism.logistics.modules.proof.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.proof.domain.EvidenceObject;
import vn.edu.finalism.logistics.modules.proof.domain.OtpChallenge;
import vn.edu.finalism.logistics.modules.proof.domain.ProofOfDelivery;

public interface ProofRepository {

	void insertEvidence(EvidenceObject evidence);

	Optional<EvidenceObject> findEvidenceForUpdate(UUID id);

	void markUploaded(UUID id);

	void markVerified(UUID id, String detectedMime, long sizeBytes, byte[] sha256, Instant now);

	void markQuarantined(UUID id);

	void markAttached(UUID id, Instant now);

	void insertPod(ProofOfDelivery pod, UUID commandId, Instant now);

	void insertPodEvidence(UUID podId, UUID evidenceId, String kind);

	Optional<ProofOfDelivery> findPod(UUID id);

	Optional<ProofOfDelivery> findPodByStop(UUID stopId);

	Set<UUID> stopIdsWithPod(Collection<UUID> stopIds);

	Optional<ProofOfDelivery> findPodByOrder(UUID orderId);

	List<PodEvidenceRef> listPodEvidence(UUID podId);

	Optional<PodEvidenceRef> findAttachedEvidence(UUID evidenceId);

	void insertOtp(OtpChallenge challenge, byte[] codeHmac, Instant issuedAt);

	void expireIssuedOtp(UUID stopId);

	int countOtpIssuedSince(UUID stopId, Instant since);

	Optional<OtpRecord> findOtp(UUID id);

	Optional<OtpRecord> findOtpForUpdate(UUID id);

	void updateOtpAttempts(UUID id, int attempts, String status);

	void markOtpExpired(UUID id);

	void markOtpVerified(UUID id, int attempts, Instant now);

	boolean otpUsedByPod(UUID challengeId);

	record OtpRecord(OtpChallenge challenge, byte[] codeHmac) {
	}

	record PodEvidenceRef(UUID id, String kind, String status, String storageKey, String detectedMime) {
	}
}
