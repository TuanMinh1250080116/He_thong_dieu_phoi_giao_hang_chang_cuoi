package vn.edu.finalism.logistics.modules.proof.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.delivery.application.DeliveryPodAccessPort;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryPodAccessPort.PodTarget;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService.DriverSummary;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.proof.application.port.OtpSenderPort;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository.OtpRecord;
import vn.edu.finalism.logistics.modules.proof.domain.OtpChallenge;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;

@Service
public class OtpHandler {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final DeliveryPodAccessPort podAccess;
	private final DriverAvailabilityService drivers;
	private final ProofRepository proofs;
	private final OrderLifecycleService orders;
	private final OtpSenderPort sender;
	private final ProofSettings settings;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final ClockPort clock;

	OtpHandler(DeliveryPodAccessPort podAccess, DriverAvailabilityService drivers, ProofRepository proofs,
			OrderLifecycleService orders, OtpSenderPort sender, ProofSettings settings, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, ClockPort clock) {
		this.podAccess = podAccess;
		this.drivers = drivers;
		this.proofs = proofs;
		this.orders = orders;
		this.sender = sender;
		this.settings = settings;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.clock = clock;
	}

	public record Issued(UUID challengeId, Instant expiresAt, int maxAttempts) {
	}

	@Transactional
	public Outcome<Issued> issue(ActorContext actor, UUID deliveryId, UUID stopId, IdempotencyKey key) {
		actor.requirePermission("proof.submit");
		DriverSummary driver = driver(actor);
		byte[] fingerprint = fingerprinter.fingerprint(
				"POST /deliveries/" + deliveryId + "/stops/" + stopId + "/otp-challenges", null, null);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "ISSUE_OTP", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			OtpChallenge existing = proofs.findOtp(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("OTP challenge")).challenge();
			return Outcome.replay(new Issued(existing.id(), existing.expiresAt(), existing.maxAttempts()));
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
		OrderView order = orders.get(List.of(target.orderId())).get(0);
		String contact = order.recipientContact();
		if (contact == null || !contact.contains("@")) {
			throw BusinessException.validation("recipient.contact", "NO_EMAIL",
					"The recipient has no email address, so a verification code cannot be sent. Use a photo or signature.");
		}

		Instant now = clock.now();
		if (proofs.countOtpIssuedSince(stopId, now.minus(settings.otpIssueWindow())) >= settings.otpIssueLimit()) {
			throw new BusinessException(ErrorCode.RATE_LIMITED, "Too many verification codes were requested for this stop.");
		}
		proofs.expireIssuedOtp(stopId);

		UUID id = UUID.randomUUID();
		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		OtpChallenge challenge = new OtpChallenge(id, deliveryId, stopId, "ISSUED", 0, settings.otpMaxAttempts(),
				now.plus(settings.otpTtl()));
		proofs.insertOtp(challenge, hmac(id, code), now);
		sender.send(contact.trim(), order.recipientName(), code, challenge.expiresAt());

		idempotency.complete(actor.userId(), key, "OTP_CHALLENGE", id, 0L);
		return Outcome.fresh(new Issued(id, challenge.expiresAt(), challenge.maxAttempts()));
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public void verify(ActorContext actor, UUID challengeId, String code) {
		actor.requirePermission("proof.submit");
		DriverSummary driver = driver(actor);
		if (code == null || code.isBlank()) {
			throw BusinessException.validation("code", "REQUIRED", "The code is required.");
		}
		OtpRecord record = proofs.findOtpForUpdate(challengeId)
				.orElseThrow(() -> BusinessException.notFound("OTP challenge"));
		OtpChallenge challenge = record.challenge();
		PodTarget target = podAccess.lockForPod(challenge.deliveryId(), challenge.stopId());
		if (!driver.id().equals(target.assignedDriverId())) {
			throw BusinessException.notFound("OTP challenge");
		}

		if ("VERIFIED".equals(challenge.status())) {
			return;
		}
		if ("LOCKED".equals(challenge.status())) {
			throw new BusinessException(ErrorCode.OTP_LOCKED, "Too many wrong codes. Request a new code.");
		}
		Instant now = clock.now();
		if (!"ISSUED".equals(challenge.status()) || !now.isBefore(challenge.expiresAt())) {
			proofs.markOtpExpired(challengeId);
			throw new BusinessException(ErrorCode.OTP_INVALID_OR_EXPIRED, "The code is invalid or has expired.");
		}

		int attempts = challenge.attempts() + 1;
		if (MessageDigest.isEqual(hmac(challengeId, code.trim()), record.codeHmac())) {
			proofs.markOtpVerified(challengeId, attempts, now);
			return;
		}
		boolean locked = attempts >= challenge.maxAttempts();
		proofs.updateOtpAttempts(challengeId, attempts, locked ? "LOCKED" : "ISSUED");
		if (locked) {
			throw new BusinessException(ErrorCode.OTP_LOCKED, "Too many wrong codes. Request a new code.");
		}
		throw new BusinessException(ErrorCode.OTP_INVALID_OR_EXPIRED, "The code is invalid or has expired.");
	}

	private DriverSummary driver(ActorContext actor) {
		return drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
	}

	private byte[] hmac(UUID challengeId, String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(settings.otpPepper(), "HmacSHA256"));
			ByteBuffer id = ByteBuffer.allocate(16).putLong(challengeId.getMostSignificantBits())
					.putLong(challengeId.getLeastSignificantBits());
			mac.update(id.array());
			return mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is always available", e);
		}
	}
}
