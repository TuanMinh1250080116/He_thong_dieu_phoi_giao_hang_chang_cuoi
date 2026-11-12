package vn.edu.finalism.logistics.modules.dispatch.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment;
import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment.AssignmentStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.Candidate;
import vn.edu.finalism.logistics.modules.dispatch.domain.DecisionStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.dispatch.domain.Snapshot;

@Repository
class JdbcDispatchRepository implements DispatchRepository {

	private static final String DECISION = """
			SELECT id, batch_id, status, created_by, version FROM dispatch.dispatch_decisions
			""";

	private static final String ASSIGNMENT = """
			SELECT id, decision_id, driver_id, vehicle_id, kind, status, snapshot_id, decided_by, decided_at
			FROM dispatch.driver_assignments
			""";

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	JdbcDispatchRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	public Optional<DispatchDecision> findOpenDecisionByBatchForUpdate(UUID batchId) {
		return jdbc.sql(DECISION + " WHERE batch_id = :id AND status <> 'CANCELLED' FOR UPDATE")
				.param("id", batchId).query((rs, i) -> mapDecision(rs)).optional();
	}

	@Override
	public Optional<DispatchDecision> findDecisionByBatch(UUID batchId) {
		return jdbc.sql(DECISION + " WHERE batch_id = :id AND status <> 'CANCELLED'")
				.param("id", batchId).query((rs, i) -> mapDecision(rs)).optional();
	}

	@Override
	public Optional<AssignmentRow> findAssignmentRow(UUID assignmentId) {
		return jdbc.sql("""
				SELECT a.id, a.decision_id, a.driver_id, a.vehicle_id, a.kind, a.status, a.snapshot_id,
				       a.decided_by, a.decided_at, dd.version AS decision_version, dl.id AS delivery_id
				FROM dispatch.driver_assignments a
				JOIN dispatch.dispatch_decisions dd ON dd.id = a.decision_id
				LEFT JOIN delivery.deliveries dl ON dl.decision_id = a.decision_id
				WHERE a.id = :id
				""")
				.param("id", assignmentId)
				.query((rs, i) -> new AssignmentRow(mapAssignment(rs), rs.getLong("decision_version"),
						rs.getObject("delivery_id", UUID.class)))
				.optional();
	}

	@Override
	public Optional<DispatchDecision> findDecisionById(UUID id) {
		return jdbc.sql(DECISION + " WHERE id = :id").param("id", id).query((rs, i) -> mapDecision(rs)).optional();
	}

	@Override
	public Optional<DispatchDecision> findDecisionByIdForUpdate(UUID id) {
		return jdbc.sql(DECISION + " WHERE id = :id FOR UPDATE").param("id", id)
				.query((rs, i) -> mapDecision(rs)).optional();
	}

	@Override
	public void insertDecision(DispatchDecision d, Instant now) {
		jdbc.sql("""
				INSERT INTO dispatch.dispatch_decisions (id, batch_id, status, created_by, created_at, updated_at, version)
				VALUES (:id, :batch, :status, :by, :now, :now, 0)
				""")
				.param("id", d.id()).param("batch", d.batchId()).param("status", d.status().name())
				.param("by", d.createdBy()).param("now", utc(now)).update();
	}

	@Override
	public boolean updateDecision(UUID id, DecisionStatus status, long expectedVersion, Instant now) {
		return jdbc.sql("""
				UPDATE dispatch.dispatch_decisions SET status = :status, updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version
				""")
				.param("status", status.name()).param("now", utc(now)).param("id", id)
				.param("version", expectedVersion).update() == 1;
	}

	@Override
	public void closeActiveSnapshots(UUID decisionId, Instant now) {
		jdbc.sql("""
				UPDATE dispatch.recommendation_snapshots SET
				  status = CASE WHEN expires_at <= :now THEN 'EXPIRED' ELSE 'INVALIDATED' END,
				  invalidated_at = CASE WHEN expires_at <= :now THEN NULL ELSE :now END,
				  invalidation_reason = CASE WHEN expires_at <= :now THEN NULL ELSE 'REGENERATED' END
				WHERE decision_id = :id AND status = 'ACTIVE'
				""")
				.param("now", utc(now)).param("id", decisionId).update();
	}

	@Override
	public void insertSnapshot(Snapshot s, UUID generatedBy, int persistTopN) {
		jdbc.sql("""
				INSERT INTO dispatch.recommendation_snapshots
				  (id, decision_id, batch_id, batch_version, rule_version_id, status, generated_at, expires_at,
				   generated_by, candidate_count, eligible_count, persisted_top_n, exclusion_summary)
				VALUES (:id, :decision, :batch, :batchVersion, :rule, 'ACTIVE', :generatedAt, :expiresAt,
				        :by, :candidateCount, :eligibleCount, :topN, CAST(:summary AS jsonb))
				""")
				.param("id", s.id()).param("decision", s.decisionId()).param("batch", s.batchId())
				.param("batchVersion", s.batchVersion()).param("rule", s.ruleVersionId())
				.param("generatedAt", utc(s.generatedAt())).param("expiresAt", utc(s.expiresAt()))
				.param("by", generatedBy).param("candidateCount", s.candidateCount())
				.param("eligibleCount", s.eligibleCount()).param("topN", persistTopN)
				.param("summary", mapper.writeValueAsString(s.exclusionSummary()))
				.update();
		for (Candidate c : s.candidates()) {
			Map<String, Object> breakdown = new LinkedHashMap<>();
			breakdown.put("distance", c.scoreDistance());
			breakdown.put("capacityFit", c.scoreCapacityFit());
			breakdown.put("availability", c.scoreAvailability());
			breakdown.put("zoneCompatibility", c.scoreZoneCompat());
			jdbc.sql("""
					INSERT INTO dispatch.recommendation_candidates
					  (snapshot_id, driver_id, rank, vehicle_id, total_score, distance_m, score_distance,
					   score_capacity_fit, score_availability, score_zone_compat, vehicle_capacity_weight_g,
					   vehicle_capacity_volume_cm3, breakdown)
					VALUES (:snapshot, :driver, :rank, :vehicle, :total, :distance, :sd, :sc, :sa, :sz,
					        :capWeight, :capVolume, CAST(:breakdown AS jsonb))
					""")
					.param("snapshot", s.id()).param("driver", c.driverId()).param("rank", c.rank())
					.param("vehicle", c.vehicleId()).param("total", c.totalScore())
					.param("distance", BigDecimal.valueOf(c.distanceM()).setScale(1, java.math.RoundingMode.HALF_UP))
					.param("sd", c.scoreDistance()).param("sc", c.scoreCapacityFit())
					.param("sa", c.scoreAvailability()).param("sz", c.scoreZoneCompat())
					.param("capWeight", c.vehicleCapacityWeightG()).param("capVolume", c.vehicleCapacityVolumeCm3())
					.param("breakdown", mapper.writeValueAsString(breakdown))
					.update();
		}
	}

	@Override
	public Optional<Snapshot> findSnapshot(UUID id) {
		return snapshot("WHERE s.id = :id", Map.of("id", id));
	}

	@Override
	public Optional<Snapshot> findLatestSnapshot(UUID decisionId) {
		return snapshot("WHERE s.decision_id = :id ORDER BY s.generated_at DESC LIMIT 1", Map.of("id", decisionId));
	}

	@Override
	public void markSnapshotConsumed(UUID id, Instant now) {
		jdbc.sql("UPDATE dispatch.recommendation_snapshots SET status = 'CONSUMED', consumed_at = :now WHERE id = :id AND status = 'ACTIVE'")
				.param("now", utc(now)).param("id", id).update();
	}

	@Override
	public void insertAssignment(Assignment a, UUID recommendedDriverId, int sequenceNo) {
		insertAssignment(a, recommendedDriverId, sequenceNo, null, null);
	}

	@Override
	public void insertAssignment(Assignment a, UUID recommendedDriverId, int sequenceNo, String overrideReason,
			String reassignReason) {
		jdbc.sql("""
				INSERT INTO dispatch.driver_assignments
				  (id, decision_id, sequence_no, driver_id, vehicle_id, kind, status, is_current, snapshot_id,
				   recommended_driver_id, decided_by, decided_at, override_reason, reassign_reason)
				VALUES (:id, :decision, :seq, :driver, :vehicle, :kind, 'PENDING', true, :snapshot,
				        :recommended, :by, :at, :overrideReason, :reassignReason)
				""")
				.param("id", a.id()).param("decision", a.decisionId()).param("seq", sequenceNo)
				.param("driver", a.driverId()).param("vehicle", a.vehicleId()).param("kind", a.kind())
				.param("snapshot", a.snapshotId(), java.sql.Types.OTHER)
				.param("recommended", recommendedDriverId, java.sql.Types.OTHER)
				.param("by", a.decidedBy()).param("at", utc(a.decidedAt()))
				.param("overrideReason", overrideReason, java.sql.Types.VARCHAR)
				.param("reassignReason", reassignReason, java.sql.Types.VARCHAR)
				.update();
	}

	@Override
	public Optional<Assignment> findCurrentAssignmentForUpdate(UUID decisionId) {
		return jdbc.sql(ASSIGNMENT + " WHERE decision_id = :id AND is_current FOR UPDATE").param("id", decisionId)
				.query((rs, i) -> mapAssignment(rs)).optional();
	}

	@Override
	public int nextAssignmentSequence(UUID decisionId) {
		return jdbc.sql("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM dispatch.driver_assignments WHERE decision_id = :id")
				.param("id", decisionId).query(Integer.class).single();
	}

	@Override
	public boolean markAssignmentRejected(UUID id, String reasonCode, String description, Instant now) {
		return jdbc.sql("""
				UPDATE dispatch.driver_assignments
				SET status = 'REJECTED', is_current = false, responded_at = :now,
				    rejection_reason_code = :code, rejection_description = :description
				WHERE id = :id AND status = 'PENDING'
				""")
				.param("now", utc(now)).param("code", reasonCode)
				.param("description", description, java.sql.Types.VARCHAR).param("id", id).update() == 1;
	}

	@Override
	public void supersedeAssignment(UUID id, UUID supersededBy) {
		jdbc.sql("""
				UPDATE dispatch.driver_assignments SET status = 'SUPERSEDED', is_current = false, superseded_by = :by
				WHERE id = :id AND status IN ('PENDING','ACCEPTED')
				""")
				.param("by", supersededBy).param("id", id).update();
	}

	@Override
	public void cancelCurrentAssignment(UUID decisionId, String reason) {
		jdbc.sql("""
				UPDATE dispatch.driver_assignments SET status = 'CANCELLED', is_current = false, cancel_reason = :reason
				WHERE decision_id = :id AND is_current
				""")
				.param("reason", reason).param("id", decisionId).update();
	}

	@Override
	public Optional<AssignmentSummary> latestAssignmentSummary(UUID decisionId) {
		return jdbc.sql("""
				SELECT a.id, a.status, a.kind, a.driver_id, d.full_name, a.rejection_reason_code
				FROM dispatch.driver_assignments a
				JOIN fleet.drivers d ON d.id = a.driver_id
				WHERE a.decision_id = :id
				ORDER BY a.sequence_no DESC LIMIT 1
				""")
				.param("id", decisionId)
				.query((rs, i) -> new AssignmentSummary(rs.getObject("id", UUID.class), rs.getString("status"),
						rs.getString("kind"), rs.getObject("driver_id", UUID.class), rs.getString("full_name"),
						rs.getString("rejection_reason_code")))
				.optional();
	}

	@Override
	public Optional<Boolean> rejectionReasonRequiresDescription(String code) {
		return jdbc.sql("SELECT requires_description FROM admin_cfg.rejection_reason_catalog WHERE code = :code AND is_active")
				.param("code", code).query(Boolean.class).optional();
	}

	@Override
	public List<RejectionReason> activeRejectionReasons() {
		return jdbc.sql("SELECT code, requires_description FROM admin_cfg.rejection_reason_catalog WHERE is_active ORDER BY code")
				.query((rs, i) -> new RejectionReason(rs.getString("code"), rs.getBoolean("requires_description")))
				.list();
	}

	@Override
	public void cancelDecision(UUID id, String reason, long expectedVersion, Instant now) {
		int updated = jdbc.sql("""
				UPDATE dispatch.dispatch_decisions SET status = 'CANCELLED', cancel_reason = :reason,
				       updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version
				""")
				.param("reason", reason).param("now", utc(now)).param("id", id).param("version", expectedVersion)
				.update();
		if (updated != 1) {
			throw vn.edu.finalism.logistics.shared.BusinessException.versionConflict(expectedVersion + 1);
		}
	}

	@Override
	public void invalidateActiveSnapshots(UUID decisionId, String reason, Instant now) {
		jdbc.sql("""
				UPDATE dispatch.recommendation_snapshots
				SET status = 'INVALIDATED', invalidated_at = :now, invalidation_reason = :reason
				WHERE decision_id = :id AND status = 'ACTIVE'
				""")
				.param("now", utc(now)).param("reason", reason).param("id", decisionId).update();
	}

	@Override
	public Optional<Assignment> findAssignment(UUID id) {
		return jdbc.sql(ASSIGNMENT + " WHERE id = :id").param("id", id).query((rs, i) -> mapAssignment(rs)).optional();
	}

	@Override
	public boolean markAssignmentAccepted(UUID id, Instant now) {
		return jdbc.sql("""
				UPDATE dispatch.driver_assignments SET status = 'ACCEPTED', responded_at = :now
				WHERE id = :id AND status = 'PENDING'
				""")
				.param("now", utc(now)).param("id", id).update() == 1;
	}

	@Override
	public List<AssignmentRow> listAssignmentsForDriver(UUID driverId, int limit) {
		return jdbc.sql("""
				SELECT a.id, a.decision_id, a.driver_id, a.vehicle_id, a.kind, a.status, a.snapshot_id,
				       a.decided_by, a.decided_at, dd.version AS decision_version, dl.id AS delivery_id
				FROM dispatch.driver_assignments a
				JOIN dispatch.dispatch_decisions dd ON dd.id = a.decision_id
				LEFT JOIN delivery.deliveries dl ON dl.decision_id = a.decision_id
				WHERE a.driver_id = :driver
				ORDER BY a.decided_at DESC, a.id DESC
				LIMIT :limit
				""")
				.param("driver", driverId).param("limit", limit)
				.query((rs, i) -> new AssignmentRow(mapAssignment(rs), rs.getLong("decision_version"),
						rs.getObject("delivery_id", UUID.class)))
				.list();
	}

	@Override
	public void insertCommitment(UUID assignmentId, UUID driverId, Instant start, Instant end, Instant now) {
		jdbc.sql("""
				INSERT INTO dispatch.driver_commitments (id, driver_id, assignment_id, time_window, status, created_at)
				VALUES (:id, :driver, :assignment, tstzrange(:start, :end, '[)'), 'ACTIVE', :now)
				""")
				.param("id", UUID.randomUUID()).param("driver", driverId).param("assignment", assignmentId)
				.param("start", utc(start)).param("end", utc(end)).param("now", utc(now))
				.update();
	}

	@Override
	public void releaseCommitments(UUID decisionId, Instant now) {
		jdbc.sql("""
				UPDATE dispatch.driver_commitments SET status = 'RELEASED', released_at = :now
				WHERE status = 'ACTIVE'
				  AND assignment_id IN (SELECT id FROM dispatch.driver_assignments WHERE decision_id = :decision)
				""")
				.param("now", utc(now)).param("decision", decisionId).update();
	}

	@Override
	public UUID batchIdOfAssignment(UUID assignmentId) {
		return jdbc.sql("""
				SELECT dd.batch_id FROM dispatch.driver_assignments a
				JOIN dispatch.dispatch_decisions dd ON dd.id = a.decision_id WHERE a.id = :id
				""")
				.param("id", assignmentId).query((rs, i) -> rs.getObject(1, UUID.class)).single();
	}

	@SuppressWarnings("unchecked")
	private Optional<Snapshot> snapshot(String where, Map<String, Object> params) {
		JdbcClient.StatementSpec spec = jdbc.sql("""
				SELECT s.id, s.decision_id, s.batch_id, s.batch_version, s.rule_version_id, r.version_no,
				       s.status, s.generated_at, s.expires_at, s.candidate_count, s.eligible_count,
				       s.exclusion_summary::text AS summary
				FROM dispatch.recommendation_snapshots s
				JOIN admin_cfg.dispatch_rule_versions r ON r.id = s.rule_version_id
				""" + where);
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query((rs, i) -> {
			UUID id = rs.getObject("id", UUID.class);
			Map<String, Object> raw = (Map<String, Object>) mapper.readValue(rs.getString("summary"), Map.class);
			Map<String, Integer> summary = new HashMap<>();
			raw.forEach((k, v) -> summary.put(k, ((Number) v).intValue()));
			return new Snapshot(id, rs.getObject("decision_id", UUID.class), rs.getObject("batch_id", UUID.class),
					rs.getLong("batch_version"), rs.getObject("rule_version_id", UUID.class), rs.getInt("version_no"),
					rs.getString("status"), rs.getObject("generated_at", OffsetDateTime.class).toInstant(),
					rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getInt("candidate_count"),
					rs.getInt("eligible_count"), summary, candidates(id));
		}).optional();
	}

	private List<Candidate> candidates(UUID snapshotId) {
		return jdbc.sql("""
				SELECT c.rank, c.driver_id, d.full_name, c.vehicle_id, v.vehicle_type, c.total_score, c.distance_m,
				       c.score_distance, c.score_capacity_fit, c.score_availability, c.score_zone_compat,
				       c.vehicle_capacity_weight_g, c.vehicle_capacity_volume_cm3
				FROM dispatch.recommendation_candidates c
				JOIN fleet.drivers d ON d.id = c.driver_id
				JOIN fleet.vehicles v ON v.id = c.vehicle_id
				WHERE c.snapshot_id = :id ORDER BY c.rank
				""")
				.param("id", snapshotId)
				.query((rs, i) -> new Candidate(rs.getInt("rank"), rs.getObject("driver_id", UUID.class),
						rs.getString("full_name"), rs.getObject("vehicle_id", UUID.class), rs.getString("vehicle_type"),
						rs.getBigDecimal("total_score"), rs.getBigDecimal("distance_m").doubleValue(),
						rs.getBigDecimal("score_distance"), rs.getBigDecimal("score_capacity_fit"),
						rs.getBigDecimal("score_availability"), rs.getBigDecimal("score_zone_compat"),
						rs.getInt("vehicle_capacity_weight_g"), rs.getLong("vehicle_capacity_volume_cm3")))
				.list();
	}

	private static DispatchDecision mapDecision(ResultSet rs) throws SQLException {
		return new DispatchDecision(rs.getObject("id", UUID.class), rs.getObject("batch_id", UUID.class),
				DecisionStatus.valueOf(rs.getString("status")), rs.getObject("created_by", UUID.class),
				rs.getLong("version"));
	}

	private static Assignment mapAssignment(ResultSet rs) throws SQLException {
		return new Assignment(rs.getObject("id", UUID.class), rs.getObject("decision_id", UUID.class),
				rs.getObject("driver_id", UUID.class), rs.getObject("vehicle_id", UUID.class),
				rs.getString("kind"), AssignmentStatus.valueOf(rs.getString("status")),
				rs.getObject("snapshot_id", UUID.class), rs.getObject("decided_by", UUID.class),
				rs.getObject("decided_at", OffsetDateTime.class).toInstant());
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
