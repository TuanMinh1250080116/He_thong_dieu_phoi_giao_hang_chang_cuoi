package vn.edu.finalism.logistics.modules.proof.presentation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import vn.edu.finalism.logistics.modules.proof.application.EvidenceAccessHandler;
import vn.edu.finalism.logistics.modules.proof.application.EvidenceHandler;
import vn.edu.finalism.logistics.modules.proof.application.OtpHandler;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.application.EvidenceHandler.PrepareCommand;
import vn.edu.finalism.logistics.modules.proof.application.EvidenceHandler.UploadTicket;
import vn.edu.finalism.logistics.modules.proof.application.PodQueryHandler;
import vn.edu.finalism.logistics.modules.proof.application.SubmitPodHandler;
import vn.edu.finalism.logistics.modules.proof.application.SubmitPodHandler.SubmitPodCommand;
import vn.edu.finalism.logistics.modules.proof.domain.ProofOfDelivery;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;

@RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/v1")
class ProofController {

	private final EvidenceHandler evidence;
	private final SubmitPodHandler submitPod;
	private final PodQueryHandler podQuery;
	private final OtpHandler otp;
	private final EvidenceAccessHandler access;

	ProofController(EvidenceHandler evidence, SubmitPodHandler submitPod, PodQueryHandler podQuery, OtpHandler otp,
			EvidenceAccessHandler access) {
		this.otp = otp;
		this.access = access;
		this.evidence = evidence;
		this.submitPod = submitPod;
		this.podQuery = podQuery;
	}

	record EvidenceUploadRequest(@NotNull String purpose, @NotNull String kind, UUID stopId, @NotNull String mimeType,
			@Positive long sizeBytes) {
	}

	record EvidenceUploadResponse(UUID evidenceId, String uploadUrl, String method, Map<String, String> requiredHeaders,
			Instant expiresAt) {
	}

	record EvidenceCompleteRequest(@NotNull String sha256) {
	}

	record PointDto(double lat, double lng) {
	}

	record GpsDto(@NotNull PointDto point, double accuracyM) {
	}

	record SubmitPodRequest(@NotNull @Valid GpsDto gps, Boolean isDirectHandoff, String recipientName,
			List<UUID> evidenceIds, UUID otpChallengeId, String note) {
	}

	record EvidenceRefDto(UUID id, String kind) {
	}

	record PodDto(UUID id, UUID stopId, Instant capturedAt, String recipientName, boolean gpsConfirmed,
			List<String> evidenceTypes, String otpStatus, List<EvidenceRefDto> evidence) {
	}

	record OtpIssueResponse(UUID challengeId, Instant expiresAt, int maxAttempts, String channel) {
	}

	record OtpVerifyRequest(@NotNull String code) {
	}

	record AccessResponse(String url, Instant expiresAt) {
	}

	record PodEnvelope(PodDto data, long version, List<String> capabilities) {
	}

	@PostMapping("/deliveries/{deliveryId}/evidence-uploads")
	ResponseEntity<EvidenceUploadResponse> prepare(ActorContext actor, @PathVariable UUID deliveryId,
			@Valid @RequestBody EvidenceUploadRequest request) {
		UploadTicket ticket = evidence.prepare(actor, deliveryId, new PrepareCommand(request.purpose(),
				request.kind(), request.stopId(), request.mimeType(), request.sizeBytes()));
		return ResponseEntity.status(201).body(new EvidenceUploadResponse(ticket.evidenceId(), ticket.uploadPath(),
				"PUT", Map.of("Content-Type", request.mimeType()), ticket.expiresAt()));
	}

	@PutMapping("/evidence/{evidenceId}/content")
	ResponseEntity<Void> upload(ActorContext actor, @PathVariable UUID evidenceId, @RequestBody byte[] content) {
		evidence.upload(actor, evidenceId, content);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/evidence/{evidenceId}/complete")
	Map<String, Object> complete(ActorContext actor, @PathVariable UUID evidenceId,
			@Valid @RequestBody EvidenceCompleteRequest request) {
		return evidence.complete(actor, evidenceId, request.sha256());
	}

	@PostMapping("/deliveries/{deliveryId}/stops/{stopId}/pod")
	ResponseEntity<PodEnvelope> submit(ActorContext actor, @PathVariable UUID deliveryId, @PathVariable UUID stopId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody SubmitPodRequest request) {
		Outcome<ProofOfDelivery> outcome = submitPod.handle(actor, deliveryId, stopId,
				IdempotencyKey.fromHeader(idempotencyKey),
				new SubmitPodCommand(request.gps().point().lat(), request.gps().point().lng(),
						request.gps().accuracyM(), request.isDirectHandoff(), request.recipientName(),
						request.evidenceIds(), request.otpChallengeId(), request.note()));
		ProofOfDelivery pod = outcome.value();
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(java.net.URI.create("/api/v1/pods/" + pod.id()));
		return response.body(new PodEnvelope(dto(pod, podQuery.evidenceOf(pod.id())), 0, List.of()));
	}

	@PostMapping("/deliveries/{deliveryId}/stops/{stopId}/otp-challenges")
	ResponseEntity<OtpIssueResponse> issueOtp(ActorContext actor, @PathVariable UUID deliveryId,
			@PathVariable UUID stopId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey) {
		Outcome<OtpHandler.Issued> outcome = otp.issue(actor, deliveryId, stopId,
				IdempotencyKey.fromHeader(idempotencyKey));
		OtpHandler.Issued issued = outcome.value();
		OtpIssueResponse body = new OtpIssueResponse(issued.challengeId(), issued.expiresAt(), issued.maxAttempts(),
				"EMAIL");
		return outcome.replayed() ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body)
				: ResponseEntity.status(201).body(body);
	}

	@PostMapping("/otp-challenges/{challengeId}/verify")
	Map<String, Object> verifyOtp(ActorContext actor, @PathVariable UUID challengeId,
			@Valid @RequestBody OtpVerifyRequest request) {
		otp.verify(actor, challengeId, request.code());
		return Map.of("challengeId", challengeId, "status", "VERIFIED");
	}

	@GetMapping("/pods/{podId}")
	ResponseEntity<PodDto> get(ActorContext actor, @PathVariable UUID podId) {
		ProofOfDelivery pod = podQuery.get(actor, podId);
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(dto(pod, podQuery.evidenceOf(pod.id())));
	}

	@GetMapping("/orders/{orderId}/pod")
	ResponseEntity<PodDto> getByOrder(ActorContext actor, @PathVariable UUID orderId) {
		ProofOfDelivery pod = podQuery.getByOrder(actor, orderId);
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(dto(pod, podQuery.evidenceOf(pod.id())));
	}

	@PostMapping("/pods/{podId}/evidence/{evidenceId}/access")
	ResponseEntity<AccessResponse> grantAccess(ActorContext actor, @PathVariable UUID podId,
			@PathVariable UUID evidenceId) {
		EvidenceAccessHandler.Grant grant = access.grant(actor, podId, evidenceId);
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(new AccessResponse(grant.path(), grant.expiresAt()));
	}

	@GetMapping("/evidence/view/{token}")
	ResponseEntity<byte[]> view(@PathVariable String token) {
		EvidenceAccessHandler.Content content = access.view(token);
		return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(content.mimeType()))
				.header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
				.body(content.bytes());
	}

	private static PodDto dto(ProofOfDelivery pod, List<ProofRepository.PodEvidenceRef> evidence) {
		return new PodDto(pod.id(), pod.stopId(), pod.capturedAt(), pod.recipientName(), true, pod.evidenceKinds(),
				pod.otpChallengeId() != null ? "VERIFIED" : "NOT_VERIFIED",
				evidence.stream().map(e -> new EvidenceRefDto(e.id(), e.kind())).toList());
	}
}
