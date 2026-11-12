package vn.edu.finalism.logistics.modules.proof.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.proof.application.port.ObjectStoragePort;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository.PodEvidenceRef;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class EvidenceAccessHandler {

	private static final String PATH = "/api/v1/evidence/view/";

	private final PodQueryHandler pods;
	private final ProofRepository proofs;
	private final ObjectStoragePort storage;
	private final ProofSettings settings;
	private final ClockPort clock;

	EvidenceAccessHandler(PodQueryHandler pods, ProofRepository proofs, ObjectStoragePort storage,
			ProofSettings settings, ClockPort clock) {
		this.pods = pods;
		this.proofs = proofs;
		this.storage = storage;
		this.settings = settings;
		this.clock = clock;
	}

	public record Grant(String path, Instant expiresAt) {
	}

	public record Content(byte[] bytes, String mimeType) {
	}

	@Transactional(readOnly = true)
	public Grant grant(ActorContext actor, UUID podId, UUID evidenceId) {
		pods.get(actor, podId);
		PodEvidenceRef ref = proofs.listPodEvidence(podId).stream().filter(e -> e.id().equals(evidenceId)).findFirst()
				.filter(e -> "ATTACHED".equals(e.status()))
				.orElseThrow(() -> BusinessException.notFound("Evidence"));
		Instant expiresAt = clock.now().plus(settings.evidenceUrlTtl());
		String payload = ref.id() + ":" + expiresAt.getEpochSecond();
		String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return new Grant(PATH + encoded + "." + sign(encoded), expiresAt);
	}

	@Transactional(readOnly = true)
	public Content view(String token) {
		int dot = token.indexOf('.');
		if (dot <= 0) {
			throw BusinessException.notFound("Evidence");
		}
		String encoded = token.substring(0, dot);
		String signature = token.substring(dot + 1);
		if (!MessageDigest.isEqual(sign(encoded).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
			throw BusinessException.notFound("Evidence");
		}
		String[] parts;
		try {
			parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split(":");
		}
		catch (IllegalArgumentException e) {
			throw BusinessException.notFound("Evidence");
		}
		if (parts.length != 2 || clock.now().getEpochSecond() >= Long.parseLong(parts[1])) {
			throw BusinessException.notFound("Evidence");
		}
		PodEvidenceRef ref = proofs.findAttachedEvidence(UUID.fromString(parts[0]))
				.orElseThrow(() -> BusinessException.notFound("Evidence"));
		byte[] bytes = storage.read(ref.storageKey()).orElseThrow(() -> BusinessException.notFound("Evidence"));
		return new Content(bytes, ref.detectedMime() == null ? "application/octet-stream" : ref.detectedMime());
	}

	private String sign(String encodedPayload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(settings.evidenceUrlSecret(), "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is always available", e);
		}
	}
}
