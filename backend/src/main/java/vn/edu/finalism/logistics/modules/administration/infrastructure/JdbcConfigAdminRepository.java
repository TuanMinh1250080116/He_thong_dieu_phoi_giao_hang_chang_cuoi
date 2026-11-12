package vn.edu.finalism.logistics.modules.administration.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository;

@Repository
class JdbcConfigAdminRepository implements ConfigAdminRepository {

	private static final UUID NIL = new UUID(0, 0);
	private static final String ZONE_LOCK_KEY = "7101";

	private static final String RULE_SELECT = """
			SELECT id, zone_id, version_no, name, status, w_distance, w_capacity_fit, w_availability, w_zone_compat,
			       ttl_seconds, search_radius_m, max_location_age_s, persist_top_n, effective_from, activated_at,
			       retired_at, created_at
			FROM admin_cfg.dispatch_rule_versions
			""";

	private static final String POLICY_SELECT = """
			SELECT id, zone_id, version_no, name, status, evidence_expression::text AS expression, max_gps_accuracy_m,
			       recipient_name_rule, effective_from, activated_at, retired_at, created_at
			FROM admin_cfg.pod_policy_versions
			""";

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	JdbcConfigAdminRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	public void lockZoneActivation() {
		jdbc.sql("SELECT pg_advisory_xact_lock(" + ZONE_LOCK_KEY + ")").query().singleRow();
	}

	@Override
	public boolean setZoneActive(UUID zoneId, boolean active, Instant now) {
		return jdbc.sql("""
				UPDATE admin_cfg.delivery_zones SET is_active = :active, updated_at = :now, version = version + 1
				WHERE id = :id AND is_active <> :active
				""")
				.param("active", active).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("id", zoneId)
				.update() == 1;
	}

	@Override
	public ZoneUsage zoneUsage(UUID zoneId) {
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM ordering.orders
				        WHERE zone_id = :id AND status IN ('READY_FOR_DISPATCH','BATCHED','ASSIGNED','IN_DELIVERY')) AS orders,
				       (SELECT count(*) FROM dispatch.delivery_batches
				        WHERE zone_id = :id AND status IN ('DRAFT','LOCKED')) AS batches
				""")
				.param("id", zoneId)
				.query((rs, i) -> new ZoneUsage(rs.getInt("orders"), rs.getInt("batches"))).single();
	}

	@Override
	public List<ZoneOption> zoneOptions() {
		return jdbc.sql("SELECT id, code, name FROM admin_cfg.delivery_zones WHERE is_active ORDER BY code")
				.query((rs, i) -> new ZoneOption(rs.getObject("id", UUID.class), rs.getString("code"),
						rs.getString("name")))
				.list();
	}

	@Override
	public List<ZoneRow> listZones(Set<UUID> visibleZoneIds) {
		return jdbc.sql(ZONE_SELECT + " WHERE z.id IN (:ids) ORDER BY z.code")
				.param("ids", visibleZoneIds.isEmpty() ? List.of(NIL) : List.copyOf(visibleZoneIds))
				.query((rs, i) -> mapZone(rs)).list();
	}

	private static final String ZONE_SELECT = """
			SELECT z.id, z.code, z.name, z.is_active, z.version,
			       av.version_no AS active_no, av.id AS active_id,
			       (SELECT count(*) FROM admin_cfg.delivery_zone_versions x WHERE x.zone_id = z.id) AS version_count
			FROM admin_cfg.delivery_zones z
			LEFT JOIN admin_cfg.delivery_zone_versions av ON av.zone_id = z.id AND av.status = 'ACTIVE'
			""";

	@Override
	public Optional<ZoneRow> findZone(UUID id) {
		return jdbc.sql(ZONE_SELECT + " WHERE z.id = :id").param("id", id).query((rs, i) -> mapZone(rs)).optional();
	}

	@Override
	public boolean zoneCodeExists(String code) {
		return jdbc.sql("SELECT count(*) FROM admin_cfg.delivery_zones WHERE code = :code").param("code", code)
				.query(Integer.class).single() > 0;
	}

	@Override
	public boolean zoneExists(UUID zoneId) {
		return jdbc.sql("SELECT count(*) FROM admin_cfg.delivery_zones WHERE id = :id").param("id", zoneId)
				.query(Integer.class).single() > 0;
	}

	@Override
	public void insertZone(UUID id, String code, String name, Instant now) {
		jdbc.sql("INSERT INTO admin_cfg.delivery_zones (id, code, name, created_at, updated_at) VALUES (:id, :code, :name, :now, :now)")
				.param("id", id).param("code", code).param("name", name).param("now", utc(now)).update();
	}

	@Override
	public GeometryCheck checkGeometry(String geoJson) {
		try {
			return jdbc.sql("""
					SELECT ST_IsValid(g) AS valid, ST_IsValidReason(g) AS reason, GeometryType(g) AS type,
					       ST_XMin(g) AS min_lng, ST_XMax(g) AS max_lng, ST_YMin(g) AS min_lat, ST_YMax(g) AS max_lat,
					       CASE WHEN ST_IsValid(g) AND ST_XMin(g) BETWEEN -180 AND 180 AND ST_XMax(g) BETWEEN -180 AND 180
					                 AND ST_YMin(g) BETWEEN -90 AND 90 AND ST_YMax(g) BETWEEN -90 AND 90
					            THEN ST_Area(g::geography) ELSE 0 END AS area
					FROM (SELECT ST_SetSRID(ST_GeomFromGeoJSON(:json), 4326) AS g) t
					""")
					.param("json", geoJson)
					.query((rs, i) -> new GeometryCheck(rs.getBoolean("valid"), rs.getString("reason"),
							rs.getString("type"), rs.getDouble("min_lng"), rs.getDouble("max_lng"),
							rs.getDouble("min_lat"), rs.getDouble("max_lat"), rs.getDouble("area")))
					.single();
		}
		catch (DataAccessException e) {
			return new GeometryCheck(false, "The boundary is not valid GeoJSON", "UNKNOWN", 0, 0, 0, 0, 0);
		}
	}

	@Override
	public int nextZoneVersionNo(UUID zoneId) {
		return jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM admin_cfg.delivery_zone_versions WHERE zone_id = :id")
				.param("id", zoneId).query(Integer.class).single();
	}

	@Override
	public void insertZoneVersion(UUID id, UUID zoneId, int versionNo, String geoJson, UUID createdBy, Instant now) {
		jdbc.sql("""
				INSERT INTO admin_cfg.delivery_zone_versions
				  (id, zone_id, version_no, boundary, status, created_by, created_at)
				VALUES (:id, :zone, :no, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(:json), 4326))::geography,
				        'DRAFT', :by, :now)
				""")
				.param("id", id).param("zone", zoneId).param("no", versionNo).param("json", geoJson)
				.param("by", createdBy).param("now", utc(now)).update();
	}

	@Override
	public List<ZoneVersionRow> listZoneVersions(UUID zoneId) {
		return jdbc.sql(ZONE_VERSION_SELECT + " WHERE zone_id = :id ORDER BY version_no DESC").param("id", zoneId)
				.query((rs, i) -> mapZoneVersion(rs)).list();
	}

	private static final String ZONE_VERSION_SELECT = """
			SELECT id, zone_id, version_no, status, effective_from, activated_at, retired_at, created_at,
			       ST_AsGeoJSON(boundary::geometry, 6) AS boundary_geojson
			FROM admin_cfg.delivery_zone_versions
			""";

	@Override
	public Optional<ZoneVersionRow> findZoneVersionForUpdate(UUID id) {
		return jdbc.sql(ZONE_VERSION_SELECT + " WHERE id = :id FOR UPDATE").param("id", id)
				.query((rs, i) -> mapZoneVersion(rs)).optional();
	}

	@Override
	public List<UUID> overlappingActiveZones(UUID versionId) {
		return jdbc.sql("""
				SELECT DISTINCT o.zone_id
				FROM admin_cfg.delivery_zone_versions n
				JOIN admin_cfg.delivery_zone_versions o
				  ON o.status = 'ACTIVE' AND o.zone_id <> n.zone_id
				 AND ST_Relate(o.boundary::geometry, n.boundary::geometry, 'T********')
				WHERE n.id = :id
				""")
				.param("id", versionId).query((rs, i) -> rs.getObject(1, UUID.class)).list();
	}

	@Override
	public Optional<Integer> retireActiveZoneVersion(UUID zoneId, Instant now) {
		return jdbc.sql("""
				UPDATE admin_cfg.delivery_zone_versions SET status = 'RETIRED', retired_at = :now
				WHERE zone_id = :zone AND status = 'ACTIVE' RETURNING version_no
				""")
				.param("now", utc(now)).param("zone", zoneId).query(Integer.class).optional();
	}

	@Override
	public void activateZoneVersion(UUID id, Instant now) {
		jdbc.sql("""
				UPDATE admin_cfg.delivery_zone_versions SET status = 'ACTIVE', effective_from = :now, activated_at = :now
				WHERE id = :id AND status = 'DRAFT'
				""")
				.param("now", utc(now)).param("id", id).update();
	}

	@Override
	public void touchZone(UUID zoneId, Instant now) {
		jdbc.sql("UPDATE admin_cfg.delivery_zones SET updated_at = :now, version = version + 1 WHERE id = :id")
				.param("now", utc(now)).param("id", zoneId).update();
	}

	@Override
	public List<RuleRow> listRules(Set<UUID> visibleZoneIds, String status) {
		String filter = status == null ? "" : " AND status = :status";
		JdbcClient.StatementSpec spec = jdbc.sql(RULE_SELECT + " WHERE (zone_id IS NULL OR zone_id IN (:ids))" + filter
				+ " ORDER BY COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'), version_no DESC")
				.param("ids", visibleZoneIds.isEmpty() ? List.of(NIL) : List.copyOf(visibleZoneIds));
		if (status != null) {
			spec = spec.param("status", status);
		}
		return spec.query((rs, i) -> mapRule(rs)).list();
	}

	@Override
	public Optional<RuleRow> findRule(UUID id) {
		return jdbc.sql(RULE_SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> mapRule(rs)).optional();
	}

	@Override
	public void insertRule(RuleRow r, UUID createdBy, Instant now) {
		jdbc.sql("""
				INSERT INTO admin_cfg.dispatch_rule_versions
				  (id, zone_id, version_no, name, status, w_distance, w_capacity_fit, w_availability, w_zone_compat,
				   ttl_seconds, search_radius_m, max_location_age_s, persist_top_n, effective_from, created_by, created_at)
				VALUES (:id, :zone, :no, :name, 'DRAFT', :wd, :wc, :wa, :wz, :ttl, :radius, :age, :top, :now, :by, :now)
				""")
				.param("id", r.id()).param("zone", r.zoneId(), java.sql.Types.OTHER).param("no", r.versionNo())
				.param("name", r.name()).param("wd", r.wDistance()).param("wc", r.wCapacityFit())
				.param("wa", r.wAvailability()).param("wz", r.wZoneCompat()).param("ttl", r.ttlSeconds())
				.param("radius", r.searchRadiusM()).param("age", r.maxLocationAgeSeconds()).param("top", r.persistTopN())
				.param("by", createdBy).param("now", utc(now)).update();
	}

	@Override
	public List<PolicyRow> listPolicies(Set<UUID> visibleZoneIds, String status) {
		String filter = status == null ? "" : " AND status = :status";
		JdbcClient.StatementSpec spec = jdbc.sql(POLICY_SELECT + " WHERE (zone_id IS NULL OR zone_id IN (:ids))" + filter
				+ " ORDER BY COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'), version_no DESC")
				.param("ids", visibleZoneIds.isEmpty() ? List.of(NIL) : List.copyOf(visibleZoneIds));
		if (status != null) {
			spec = spec.param("status", status);
		}
		return spec.query((rs, i) -> mapPolicy(rs)).list();
	}

	@Override
	public Optional<PolicyRow> findPolicy(UUID id) {
		return jdbc.sql(POLICY_SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> mapPolicy(rs)).optional();
	}

	@Override
	public void insertPolicy(PolicyRow p, UUID createdBy, Instant now) {
		Map<String, Object> expression = new LinkedHashMap<>();
		expression.put("op", p.evidenceOp());
		expression.put("items", p.evidenceItems());
		jdbc.sql("""
				INSERT INTO admin_cfg.pod_policy_versions
				  (id, zone_id, version_no, name, status, requires_gps, requires_server_ts, max_gps_accuracy_m,
				   evidence_expression, recipient_name_rule, effective_from, created_by, created_at)
				VALUES (:id, :zone, :no, :name, 'DRAFT', true, true, :maxGps, CAST(:expression AS jsonb), :rule,
				        :now, :by, :now)
				""")
				.param("id", p.id()).param("zone", p.zoneId(), java.sql.Types.OTHER).param("no", p.versionNo())
				.param("name", p.name()).param("maxGps", p.maxGpsAccuracyM(), java.sql.Types.INTEGER)
				.param("expression", mapper.writeValueAsString(expression)).param("rule", p.recipientNameRule())
				.param("by", createdBy).param("now", utc(now)).update();
	}

	@Override
	public int nextVersionNo(Kind kind, UUID zoneId) {
		return jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM admin_cfg." + kind.table()
				+ " WHERE COALESCE(zone_id, '00000000-0000-0000-0000-000000000000') = COALESCE(CAST(:zone AS uuid), '00000000-0000-0000-0000-000000000000'::uuid)")
				.param("zone", zoneId, java.sql.Types.OTHER).query(Integer.class).single();
	}

	@Override
	public Optional<VersionHeader> findHeaderForUpdate(Kind kind, UUID id) {
		return jdbc.sql("SELECT id, zone_id, version_no, status, name FROM admin_cfg." + kind.table()
				+ " WHERE id = :id FOR UPDATE")
				.param("id", id)
				.query((rs, i) -> new VersionHeader(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
						rs.getInt("version_no"), rs.getString("status"), rs.getString("name")))
				.optional();
	}

	@Override
	public Optional<Integer> retireActive(Kind kind, UUID zoneId, Instant now) {
		return jdbc.sql("UPDATE admin_cfg." + kind.table() + " SET status = 'RETIRED', retired_at = :now"
				+ " WHERE COALESCE(zone_id, '00000000-0000-0000-0000-000000000000') = COALESCE(CAST(:zone AS uuid), '00000000-0000-0000-0000-000000000000'::uuid)"
				+ " AND status = 'ACTIVE' RETURNING version_no")
				.param("now", utc(now)).param("zone", zoneId, java.sql.Types.OTHER).query(Integer.class).optional();
	}

	@Override
	public boolean schedule(Kind kind, UUID id, Instant effectiveFrom) {
		return jdbc.sql("UPDATE admin_cfg." + kind.table()
				+ " SET status = 'SCHEDULED', effective_from = :at WHERE id = :id AND status = 'DRAFT'")
				.param("at", utc(effectiveFrom)).param("id", id).update() == 1;
	}

	@Override
	public boolean unschedule(Kind kind, UUID id, Instant now) {
		return jdbc.sql("UPDATE admin_cfg." + kind.table()
				+ " SET status = 'DRAFT', effective_from = :now WHERE id = :id AND status = 'SCHEDULED'")
				.param("now", utc(now)).param("id", id).update() == 1;
	}

	@Override
	public boolean scheduledExists(Kind kind, UUID zoneId) {
		return jdbc.sql("SELECT count(*) FROM admin_cfg." + kind.table()
				+ " WHERE status = 'SCHEDULED' AND COALESCE(zone_id, '00000000-0000-0000-0000-000000000000')"
				+ " = COALESCE(CAST(:zone AS uuid), '00000000-0000-0000-0000-000000000000'::uuid)")
				.param("zone", zoneId, java.sql.Types.OTHER).query(Integer.class).single() > 0;
	}

	@Override
	public void lockVersionActivation() {
		jdbc.sql("SELECT pg_advisory_xact_lock(7340002)").query().singleRow();
	}

	@Override
	public List<DuePromotion> promoteDue(Kind kind, Instant now) {
		List<DuePromotion> due = jdbc.sql("SELECT id, zone_id, version_no FROM admin_cfg." + kind.table()
				+ " WHERE status = 'SCHEDULED' AND effective_from <= :now ORDER BY effective_from FOR UPDATE")
				.param("now", utc(now))
				.query((rs, i) -> new DuePromotion(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
						rs.getInt("version_no"), null))
				.list();
		List<DuePromotion> done = new java.util.ArrayList<>();
		for (DuePromotion item : due) {
			Integer previous = retireActive(kind, item.zoneId(), now).orElse(null);
			jdbc.sql("UPDATE admin_cfg." + kind.table()
					+ " SET status = 'ACTIVE', activated_at = :now WHERE id = :id AND status = 'SCHEDULED'")
					.param("now", utc(now)).param("id", item.id()).update();
			done.add(new DuePromotion(item.id(), item.zoneId(), item.versionNo(), previous));
		}
		return done;
	}

	@Override
	public void activate(Kind kind, UUID id, Instant now) {
		jdbc.sql("UPDATE admin_cfg." + kind.table()
				+ " SET status = 'ACTIVE', effective_from = :now, activated_at = :now WHERE id = :id AND status = 'DRAFT'")
				.param("now", utc(now)).param("id", id).update();
	}

	private static ZoneRow mapZone(ResultSet rs) throws SQLException {
		return new ZoneRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
				rs.getBoolean("is_active"), (Integer) rs.getObject("active_no"), rs.getObject("active_id", UUID.class),
				rs.getInt("version_count"), rs.getLong("version"));
	}

	private static ZoneVersionRow mapZoneVersion(ResultSet rs) throws SQLException {
		return new ZoneVersionRow(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
				rs.getInt("version_no"), rs.getString("status"), instant(rs, "effective_from"),
				instant(rs, "activated_at"), instant(rs, "retired_at"), instant(rs, "created_at"),
				rs.getString("boundary_geojson"));
	}

	private static RuleRow mapRule(ResultSet rs) throws SQLException {
		return new RuleRow(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getInt("version_no"),
				rs.getString("name"), rs.getString("status"), rs.getBigDecimal("w_distance"),
				rs.getBigDecimal("w_capacity_fit"), rs.getBigDecimal("w_availability"),
				rs.getBigDecimal("w_zone_compat"), rs.getInt("ttl_seconds"), rs.getInt("search_radius_m"),
				rs.getInt("max_location_age_s"), rs.getInt("persist_top_n"), instant(rs, "effective_from"),
				instant(rs, "activated_at"), instant(rs, "retired_at"), instant(rs, "created_at"));
	}

	private PolicyRow mapPolicy(ResultSet rs) throws SQLException {
		JsonNode expression = mapper.readTree(rs.getString("expression"));
		List<String> items = new ArrayList<>();
		expression.path("items").forEach(item -> items.add(item.asString()));
		return new PolicyRow(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
				rs.getInt("version_no"), rs.getString("name"), rs.getString("status"),
				expression.path("op").asString(), items, (Integer) rs.getObject("max_gps_accuracy_m"),
				rs.getString("recipient_name_rule"), instant(rs, "effective_from"), instant(rs, "activated_at"),
				instant(rs, "retired_at"), instant(rs, "created_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
