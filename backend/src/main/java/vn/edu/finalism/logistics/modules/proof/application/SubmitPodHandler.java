package vn.edu.finalism.logistics.modules.proof.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryPodAccessPort;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryPodAccessPort.PodTarget;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService.DriverSummary;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.domain.EvidenceObject;
import vn.edu.finalism.logistics.modules.proof.domain.PodRequirementPolicy;
import vn.edu.finalism.logistics.modules.proof.domain.ProofOfDelivery;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.GeoPoint;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class SubmitPodHandler {

	private final DeliveryPodAccessPort podAccess;
	private final DriverAvailabilityService drivers;
	private final ProofRepository proofs;
	private final OrderLifecycleService orders;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	SubmitPodHandler(DeliveryPodAccessPort podAccess, DriverAvailabilityService drivers, ProofRepository proofs,
			OrderLifecycleService orders, IdempotencyService idempotency, RequestFingerprinter fingerprinter,
			AuditWriter audit, EventPublicationPort events, ClockPort clock) {
		this.podAccess = podAccess;
		this.drivers = drivers;
		this.proofs = proofs;
		this.orders = orders;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public record SubmitPodCommand(double lat, double lng, double accuracyM, Boolean directHandoff,
			String recipientName, List<UUID> evidenceIds, UUID otpChallengeId, String note) {
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Outcome<ProofOfDelivery> handle(ActorContext actor, UUID deliveryId, UUID stopId, IdempotencyKey key,
			SubmitPodCommand command) {
		actor.requirePermission("proof.submit");
		DriverSummary driver = drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
		GeoPoint gps = new GeoPoint(command.lat(), command.lng());
		if (command.accuracyM() < 0) {
			throw BusinessException.validation("gps.accuracyM", "NEGATIVE", "accuracyM must not be negative.");
		}

		byte[] fingerprint = fingerprinter.fingerprint("POST /deliveries/" + deliveryId + "/stops/" + stopId + "/pod",
				null, command);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "SUBMIT_POD", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(proofs.findPod(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Proof of delivery")));
		}

		PodTarget target = podAccess.lockForPod(deliveryId, stopId);
		if (!driver.id().equals(target.assignedDriverId())) {
			throw BusinessException.notFound("Delivery");
		}
		if (!"IN_PROGRESS".equals(target.deliveryStatus()) || !"IN_PROGRESS".equals(target.stopStatus())) {
			throw BusinessException.stateNotAllowed("The delivery and the stop must both be in progress.");
		}
		if (proofs.findPodByStop(stopId).isPresent()) {
			throw BusinessException.stateNotAllowed("This stop already has a proof of delivery.");
		}

		Map<String, Object> policy = target.podPolicySnapshot();
		Number maxAccuracy = (Number) policy.get("maxGpsAccuracyM");
		if (maxAccuracy != null && command.accuracyM() > maxAccuracy.doubleValue()) {
			throw new BusinessException(ErrorCode.POD_POLICY_NOT_SATISFIED, "The GPS accuracy is too low.",
					Map.of("missing", List.of("GPS_ACCURACY")));
		}

		List<EvidenceObject> evidence = new ArrayList<>();
		Set<String> provided = new HashSet<>();
		if (command.otpChallengeId() != null) {
			boolean usable = proofs.findOtpForUpdate(command.otpChallengeId())
					.map(r -> r.challenge())
					.filter(c -> "VERIFIED".equals(c.status()) && deliveryId.equals(c.deliveryId())
							&& stopId.equals(c.stopId()))
					.isPresent() && !proofs.otpUsedByPod(command.otpChallengeId());
			if (!usable) {
				throw new BusinessException(ErrorCode.OTP_INVALID_OR_EXPIRED,
						"The verification code was not verified for this stop.");
			}
			provided.add("OTP");
		}
		for (UUID evidenceId : command.evidenceIds() == null ? List.<UUID>of() : command.evidenceIds()) {
			EvidenceObject item = proofs.findEvidenceForUpdate(evidenceId)
					.filter(e -> e.ownerUserId().equals(actor.userId()) && deliveryId.equals(e.deliveryId())
							&& stopId.equals(e.stopId()) && "POD".equals(e.purpose()) && "VERIFIED".equals(e.status()))
					.orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_INVALID,
							"An evidence file is missing, not verified, or belongs to someone else."));
			evidence.add(item);
			provided.add(item.kind());
		}
		List<String> missing = new ArrayList<>(PodRequirementPolicy.missingEvidence(
				(Map<String, Object>) policy.get("evidenceExpression"), provided));
		boolean direct = command.directHandoff() == null || command.directHandoff();
		boolean recipientBlank = command.recipientName() == null || command.recipientName().isBlank();
		if (PodRequirementPolicy.recipientNameRequired((String) policy.get("recipientNameRule"), direct) && recipientBlank) {
			missing.add("RECIPIENT_NAME");
		}
		if (!missing.isEmpty()) {
			throw new BusinessException(ErrorCode.POD_POLICY_NOT_SATISFIED,
					"The proof of delivery does not meet the policy.", Map.of("missing", missing));
		}

		Instant now = clock.now();
		ProofOfDelivery pod = new ProofOfDelivery(UUID.randomUUID(), deliveryId, stopId, target.orderId(), now, gps,
				command.accuracyM(), recipientBlank ? null : command.recipientName().trim(), direct, command.note(),
				target.podPolicyVersionId(), actor.userId(), evidence.stream().map(EvidenceObject::kind).sorted().toList(),
				command.otpChallengeId());
		proofs.insertPod(pod, UUID.randomUUID(), now);
		for (EvidenceObject item : evidence) {
			proofs.insertPodEvidence(pod.id(), item.id(), item.kind());
			proofs.markAttached(item.id(), now);
		}
		podAccess.markStopCompleted(deliveryId, stopId, actor.userId());

		UUID zoneId = orders.get(List.of(target.orderId())).get(0).zoneId();
		audit.append(AuditEntry.forUser(actor, AuditAction.POD_SUBMITTED, "POD", pod.id(), zoneId, null,
				Map.of("stopId", stopId.toString(), "orderId", target.orderId().toString(),
						"evidence", pod.evidenceKinds()),
				null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "ProofOfDeliverySubmitted", 1, "ProofOfDelivery",
				pod.id(), now, actor.userId(), actor.correlationId(),
				Map.of("podId", pod.id().toString(), "stopId", stopId.toString())));
		idempotency.complete(actor.userId(), key, "POD", pod.id(), 0L);
		return Outcome.fresh(pod);
	}
}
