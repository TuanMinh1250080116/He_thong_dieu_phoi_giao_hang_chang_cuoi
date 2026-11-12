package vn.edu.finalism.logistics.modules.proof.application;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.DeliveryExceptionPort;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryPodAccessPort;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryPodAccessPort.PodTarget;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService.DriverSummary;
import vn.edu.finalism.logistics.modules.proof.application.port.ObjectStoragePort;
import vn.edu.finalism.logistics.modules.proof.application.port.ObjectStoragePort.ObjectInfo;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.domain.EvidenceObject;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Service
public class EvidenceHandler {

	public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
	private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png");
	private static final Duration UPLOAD_WINDOW = Duration.ofMinutes(15);

	private final DeliveryPodAccessPort podAccess;
	private final DeliveryExceptionPort exceptionAccess;
	private final DriverAvailabilityService drivers;
	private final ProofRepository proofs;
	private final ObjectStoragePort storage;
	private final ClockPort clock;

	EvidenceHandler(DeliveryPodAccessPort podAccess, DeliveryExceptionPort exceptionAccess,
			DriverAvailabilityService drivers, ProofRepository proofs, ObjectStoragePort storage, ClockPort clock) {
		this.podAccess = podAccess;
		this.exceptionAccess = exceptionAccess;
		this.drivers = drivers;
		this.proofs = proofs;
		this.storage = storage;
		this.clock = clock;
	}

	public record UploadTicket(UUID evidenceId, String uploadPath, Instant expiresAt) {
	}

	public record PrepareCommand(String purpose, String kind, UUID stopId, String mimeType, long sizeBytes) {
	}

	@Transactional
	public UploadTicket prepare(ActorContext actor, UUID deliveryId, PrepareCommand command) {
		DriverSummary driver = requireDriver(actor);
		if (!Set.of("POD", "EXCEPTION").contains(command.purpose())) {
			throw BusinessException.validation("purpose", "INVALID", "purpose must be POD or EXCEPTION.");
		}
		boolean forPod = "POD".equals(command.purpose());
		if (!Set.of("PHOTO", "SIGNATURE").contains(command.kind())) {
			throw BusinessException.validation("kind", "INVALID", "kind must be PHOTO or SIGNATURE.");
		}
		if (forPod && command.stopId() == null) {
			throw BusinessException.validation("stopId", "REQUIRED", "stopId is required for POD evidence.");
		}
		if (!ALLOWED_MIME.contains(command.mimeType())) {
			throw BusinessException.validation("mimeType", "NOT_ALLOWED", "Only JPEG and PNG images are accepted.");
		}
		if (command.sizeBytes() < 1 || command.sizeBytes() > MAX_SIZE_BYTES) {
			throw BusinessException.validation("sizeBytes", "OUT_OF_RANGE", "The file must be between 1 byte and 10 MB.");
		}

		if (forPod) {
			PodTarget target = podAccess.lockForPod(deliveryId, command.stopId());
			if (!driver.id().equals(target.assignedDriverId())) {
				throw BusinessException.notFound("Delivery");
			}
			if (!"IN_PROGRESS".equals(target.deliveryStatus())) {
				throw BusinessException.stateNotAllowed("The delivery is not in progress.");
			}
		}
		else {
			DeliveryExceptionPort.ExceptionTarget target = exceptionAccess.lockForException(deliveryId);
			if (!driver.id().equals(target.assignedDriverId())) {
				throw BusinessException.notFound("Delivery");
			}
			if (command.stopId() != null && target.stops().stream().noneMatch(s -> s.id().equals(command.stopId()))) {
				throw BusinessException.notFound("Stop");
			}
		}

		Instant now = clock.now();
		UUID evidenceId = UUID.randomUUID();
		Instant expiresAt = now.plus(UPLOAD_WINDOW);
		proofs.insertEvidence(new EvidenceObject(evidenceId, command.purpose(), command.kind(), actor.userId(),
				deliveryId, command.stopId(), storage.keyFor(evidenceId), command.mimeType(), null,
				command.sizeBytes(), "PREPARED", now, expiresAt));
		return new UploadTicket(evidenceId, "/api/v1/evidence/" + evidenceId + "/content", expiresAt);
	}

	@Transactional
	public void upload(ActorContext actor, UUID evidenceId, byte[] content) {
		requireDriver(actor);
		EvidenceObject evidence = owned(actor, evidenceId);
		if (!"PREPARED".equals(evidence.status())) {
			throw BusinessException.stateNotAllowed("This upload slot has already been used.");
		}
		if (!evidence.expiresAt().isAfter(clock.now())) {
			throw new BusinessException(ErrorCode.EVIDENCE_INVALID, "The upload slot has expired.");
		}
		if (content == null || content.length == 0 || content.length > evidence.sizeBytes()) {
			throw new BusinessException(ErrorCode.EVIDENCE_INVALID, "The file does not match the declared size.");
		}
		storage.put(evidence.storageKey(), content);
		proofs.markUploaded(evidenceId);
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public Map<String, Object> complete(ActorContext actor, UUID evidenceId, String sha256Hex) {
		requireDriver(actor);
		EvidenceObject evidence = owned(actor, evidenceId);
		if (!"UPLOADED".equals(evidence.status())) {
			throw BusinessException.stateNotAllowed("Upload the file before completing it.");
		}
		ObjectInfo info = storage.inspect(evidence.storageKey()).orElse(null);
		boolean valid = info != null
				&& info.sizeBytes() == evidence.sizeBytes()
				&& ALLOWED_MIME.contains(info.detectedMime())
				&& HexFormat.of().formatHex(info.sha256()).equalsIgnoreCase(sha256Hex == null ? "" : sha256Hex);
		if (!valid) {
			proofs.markQuarantined(evidenceId);
			throw new BusinessException(ErrorCode.EVIDENCE_INVALID, "The uploaded file failed verification.");
		}
		proofs.markVerified(evidenceId, info.detectedMime(), info.sizeBytes(), info.sha256(), clock.now());
		return Map.of("evidenceId", evidenceId, "status", "VERIFIED");
	}

	private EvidenceObject owned(ActorContext actor, UUID evidenceId) {
		return proofs.findEvidenceForUpdate(evidenceId)
				.filter(e -> e.ownerUserId().equals(actor.userId()))
				.orElseThrow(() -> BusinessException.notFound("Evidence"));
	}

	private DriverSummary requireDriver(ActorContext actor) {
		actor.requirePermission("proof.submit");
		return drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
	}
}
