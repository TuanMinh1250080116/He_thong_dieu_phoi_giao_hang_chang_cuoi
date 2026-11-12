package vn.edu.finalism.logistics.modules.proof.infrastructure;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.proof.application.PodQueryPort;
import vn.edu.finalism.logistics.modules.proof.application.port.ProofRepository;
import vn.edu.finalism.logistics.modules.proof.domain.EvidenceObject;
import vn.edu.finalism.logistics.modules.proof.domain.OtpChallenge;
import vn.edu.finalism.logistics.modules.proof.domain.ProofOfDelivery;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Repository
class JdbcProofRepository implements ProofRepository, PodQueryPort {

	private static final String POD = """
			SELECT id, delivery_id, stop_id, order_id, captured_at, ST_Y(gps_point::geometry) AS lat,
			       ST_X(gps_point::geometry) AS lng, gps_accuracy_m, recipient_name, is_direct_handoff, note,
			       policy_version_id, submitted_by, otp_challenge_id
			FROM proof.proofs_of_delivery
			""";

	private static final String EVIDENCE_REF = """
			SELECT e.id, e.kind, e.status, e.storage_key, e.detected_mime FROM proof.evidence_objects e
			""";

	private static final String OTP = """
			SELECT id, delivery_id, stop_id, code_hmac, status, attempts, max_attempts, expires_at
			FROM proof.otp_challenges
			""";

	private final JdbcClient jdbc;

	JdbcProofRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insertEvidence(EvidenceObject e) {
		jdbc.sql("""
				INSERT INTO proof.evidence_objects
				  (id, purpose, kind, owner_user_id, delivery_id, stop_id, storage_key, declared_mime, size_bytes,
				   status, created_at, expires_at)
				VALUES (:id, :purpose, :kind, :owner, :delivery, :stop, :key, :mime, :size, 'PREPARED', :created, :expires)
				""")
				.param("id", e.id()).param("purpose", e.purpose()).param("kind", e.kind())
				.param("owner", e.ownerUserId()).param("delivery", e.deliveryId())
				.param("stop", e.stopId(), Types.OTHER).param("key", e.storageKey())
				.param("mime", e.declaredMime()).param("size", e.sizeBytes())
				.param("created", utc(e.createdAt())).param("expires", utc(e.expiresAt()))
				.update();
	}

	@Override
	public Optional<EvidenceObject> findEvidenceForUpdate(UUID id) {
		return jdbc.sql("""
				SELECT id, purpose, kind, owner_user_id, delivery_id, stop_id, storage_key, declared_mime,
				       detected_mime, size_bytes, status, created_at, expires_at
				FROM proof.evidence_objects WHERE id = :id FOR UPDATE
				""")
				.param("id", id)
				.query((rs, i) -> new EvidenceObject(rs.getObject("id", UUID.class), rs.getString("purpose"),
						rs.getString("kind"), rs.getObject("owner_user_id", UUID.class),
						rs.getObject("delivery_id", UUID.class), rs.getObject("stop_id", UUID.class),
						rs.getString("storage_key"), rs.getString("declared_mime"), rs.getString("detected_mime"),
						(Long) rs.getObject("size_bytes"), rs.getString("status"),
						rs.getObject("created_at", OffsetDateTime.class).toInstant(),
						rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
				.optional();
	}

	@Override
	public void markUploaded(UUID id) {
		jdbc.sql("UPDATE proof.evidence_objects SET status = 'UPLOADED' WHERE id = :id").param("id", id).update();
	}

	@Override
	public void markVerified(UUID id, String detectedMime, long sizeBytes, byte[] sha256, Instant now) {
		jdbc.sql("""
				UPDATE proof.evidence_objects
				SET status = 'VERIFIED', detected_mime = :mime, size_bytes = :size, sha256 = :sha, verified_at = :now
				WHERE id = :id
				""")
				.param("mime", detectedMime).param("size", sizeBytes).param("sha", sha256)
				.param("now", utc(now)).param("id", id).update();
	}

	@Override
	public void markQuarantined(UUID id) {
		jdbc.sql("UPDATE proof.evidence_objects SET status = 'QUARANTINED' WHERE id = :id").param("id", id).update();
	}

	@Override
	public void markAttached(UUID id, Instant now) {
		jdbc.sql("UPDATE proof.evidence_objects SET status = 'ATTACHED', attached_at = :now WHERE id = :id")
				.param("now", utc(now)).param("id", id).update();
	}

	@Override
	public void insertPod(ProofOfDelivery p, UUID commandId, Instant now) {
		jdbc.sql("""
				INSERT INTO proof.proofs_of_delivery
				  (id, delivery_id, stop_id, order_id, captured_at, gps_point, gps_accuracy_m, recipient_name,
				   is_direct_handoff, otp_challenge_id, otp_verified, note, policy_version_id, submitted_by, command_id, created_at)
				VALUES (:id, :delivery, :stop, :order, :captured,
				        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :accuracy, :recipient,
				        :direct, :otp, :otpVerified, :note, :policy, :by, :command, :now)
				""")
				.param("id", p.id()).param("delivery", p.deliveryId()).param("stop", p.stopId())
				.param("order", p.orderId()).param("captured", utc(p.capturedAt()))
				.param("lng", p.gps().lng()).param("lat", p.gps().lat())
				.param("accuracy", java.math.BigDecimal.valueOf(p.gpsAccuracyM()).setScale(1, java.math.RoundingMode.HALF_UP))
				.param("recipient", p.recipientName(), Types.VARCHAR).param("direct", p.directHandoff())
				.param("otp", p.otpChallengeId(), Types.OTHER).param("otpVerified", p.otpChallengeId() != null)
				.param("note", p.note(), Types.VARCHAR).param("policy", p.policyVersionId(), Types.OTHER)
				.param("by", p.submittedBy()).param("command", commandId).param("now", utc(now))
				.update();
	}

	@Override
	public void insertPodEvidence(UUID podId, UUID evidenceId, String kind) {
		jdbc.sql("INSERT INTO proof.pod_evidence (pod_id, evidence_object_id, kind) VALUES (:pod, :evidence, :kind)")
				.param("pod", podId).param("evidence", evidenceId).param("kind", kind).update();
	}

	@Override
	public Optional<ProofOfDelivery> findPod(UUID id) {
		return jdbc.sql(POD + " WHERE id = :id").param("id", id).query((rs, i) -> mapPod(rs)).optional();
	}

	@Override
	public Optional<ProofOfDelivery> findPodByStop(UUID stopId) {
		return jdbc.sql(POD + " WHERE stop_id = :id").param("id", stopId).query((rs, i) -> mapPod(rs)).optional();
	}

	@Override
	public Set<UUID> stopIdsWithPod(Collection<UUID> stopIds) {
		if (stopIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("SELECT stop_id FROM proof.proofs_of_delivery WHERE stop_id IN (:ids)")
				.param("ids", stopIds)
				.query((rs, i) -> rs.getObject(1, UUID.class)).list());
	}

	@Override
	public Optional<ProofOfDelivery> findPodByOrder(UUID orderId) {
		return jdbc.sql(POD + " WHERE order_id = :id ORDER BY created_at DESC LIMIT 1").param("id", orderId)
				.query((rs, i) -> mapPod(rs)).optional();
	}

	@Override
	public List<PodEvidenceRef> listPodEvidence(UUID podId) {
		return jdbc.sql(EVIDENCE_REF + " JOIN proof.pod_evidence pe ON pe.evidence_object_id = e.id WHERE pe.pod_id = :id ORDER BY e.created_at, e.id")
				.param("id", podId).query((rs, i) -> mapEvidenceRef(rs)).list();
	}

	@Override
	public Optional<PodEvidenceRef> findAttachedEvidence(UUID evidenceId) {
		return jdbc.sql(EVIDENCE_REF + " WHERE e.id = :id AND e.status = 'ATTACHED'").param("id", evidenceId)
				.query((rs, i) -> mapEvidenceRef(rs)).optional();
	}

	@Override
	public void insertOtp(OtpChallenge c, byte[] codeHmac, Instant issuedAt) {
		jdbc.sql("""
				INSERT INTO proof.otp_challenges
				  (id, delivery_id, stop_id, code_hmac, status, attempts, max_attempts, issued_at, expires_at)
				VALUES (:id, :delivery, :stop, :hmac, 'ISSUED', 0, :max, :issued, :expires)
				""")
				.param("id", c.id()).param("delivery", c.deliveryId()).param("stop", c.stopId())
				.param("hmac", codeHmac).param("max", c.maxAttempts()).param("issued", utc(issuedAt))
				.param("expires", utc(c.expiresAt())).update();
	}

	@Override
	public void expireIssuedOtp(UUID stopId) {
		jdbc.sql("UPDATE proof.otp_challenges SET status = 'EXPIRED' WHERE stop_id = :id AND status = 'ISSUED'")
				.param("id", stopId).update();
	}

	@Override
	public int countOtpIssuedSince(UUID stopId, Instant since) {
		return jdbc.sql("SELECT count(*) FROM proof.otp_challenges WHERE stop_id = :id AND issued_at >= :since")
				.param("id", stopId).param("since", utc(since)).query(Integer.class).single();
	}

	@Override
	public Optional<OtpRecord> findOtp(UUID id) {
		return jdbc.sql(OTP + " WHERE id = :id").param("id", id).query((rs, i) -> mapOtp(rs)).optional();
	}

	@Override
	public Optional<OtpRecord> findOtpForUpdate(UUID id) {
		return jdbc.sql(OTP + " WHERE id = :id FOR UPDATE").param("id", id).query((rs, i) -> mapOtp(rs)).optional();
	}

	@Override
	public void updateOtpAttempts(UUID id, int attempts, String status) {
		jdbc.sql("UPDATE proof.otp_challenges SET attempts = :attempts, status = :status WHERE id = :id")
				.param("attempts", attempts).param("status", status).param("id", id).update();
	}

	@Override
	public void markOtpExpired(UUID id) {
		jdbc.sql("UPDATE proof.otp_challenges SET status = 'EXPIRED' WHERE id = :id AND status = 'ISSUED'")
				.param("id", id).update();
	}

	@Override
	public void markOtpVerified(UUID id, int attempts, Instant now) {
		jdbc.sql("UPDATE proof.otp_challenges SET status = 'VERIFIED', attempts = :attempts, verified_at = :now WHERE id = :id")
				.param("attempts", attempts).param("now", utc(now)).param("id", id).update();
	}

	@Override
	public boolean otpUsedByPod(UUID challengeId) {
		return jdbc.sql("SELECT count(*) FROM proof.proofs_of_delivery WHERE otp_challenge_id = :id")
				.param("id", challengeId).query(Integer.class).single() > 0;
	}

	private static PodEvidenceRef mapEvidenceRef(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new PodEvidenceRef(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"),
				rs.getString("storage_key"), rs.getString("detected_mime"));
	}

	private static OtpRecord mapOtp(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new OtpRecord(new OtpChallenge(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
				rs.getObject("stop_id", UUID.class), rs.getString("status"), rs.getInt("attempts"),
				rs.getInt("max_attempts"), rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
				rs.getBytes("code_hmac"));
	}

	private ProofOfDelivery mapPod(java.sql.ResultSet rs) throws java.sql.SQLException {
		UUID id = rs.getObject("id", UUID.class);
		List<String> kinds = jdbc.sql("SELECT kind FROM proof.pod_evidence WHERE pod_id = :id ORDER BY kind")
				.param("id", id).query(String.class).list();
		return new ProofOfDelivery(id, rs.getObject("delivery_id", UUID.class), rs.getObject("stop_id", UUID.class),
				rs.getObject("order_id", UUID.class), rs.getObject("captured_at", OffsetDateTime.class).toInstant(),
				new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")), rs.getBigDecimal("gps_accuracy_m").doubleValue(),
				rs.getString("recipient_name"), rs.getBoolean("is_direct_handoff"), rs.getString("note"),
				rs.getObject("policy_version_id", UUID.class), rs.getObject("submitted_by", UUID.class), kinds,
				rs.getObject("otp_challenge_id", UUID.class));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
