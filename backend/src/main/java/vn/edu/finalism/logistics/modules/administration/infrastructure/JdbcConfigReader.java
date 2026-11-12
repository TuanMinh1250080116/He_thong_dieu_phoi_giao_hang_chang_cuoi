package vn.edu.finalism.logistics.modules.administration.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.administration.application.DispatchRule;
import vn.edu.finalism.logistics.modules.administration.application.EffectivePodPolicyPort;
import vn.edu.finalism.logistics.modules.administration.application.EffectiveRulePort;
import vn.edu.finalism.logistics.modules.administration.application.PodPolicy;
import vn.edu.finalism.logistics.modules.administration.application.ZoneLookupPort;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Component
class JdbcConfigReader implements ZoneLookupPort, EffectiveRulePort, EffectivePodPolicyPort {

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final ClockPort clock;

	JdbcConfigReader(JdbcClient jdbc, ObjectMapper mapper, ClockPort clock) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Override
	public Optional<ZoneMatch> findZone(GeoPoint point) {
		return jdbc.sql("""
				SELECT v.zone_id, v.id AS zone_version_id
				FROM admin_cfg.delivery_zone_versions v
				JOIN admin_cfg.delivery_zones z ON z.id = v.zone_id
				WHERE v.status = 'ACTIVE' AND z.is_active
				  AND ST_Covers(v.boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
				ORDER BY z.code
				LIMIT 1
				""")
				.param("lng", point.lng())
				.param("lat", point.lat())
				.query((rs, i) -> new ZoneMatch(rs.getObject("zone_id", UUID.class),
						rs.getObject("zone_version_id", UUID.class)))
				.optional();
	}

	@Override
	public DispatchRule effectiveRule(UUID zoneId) {
		return jdbc.sql("""
				SELECT id, version_no, w_distance, w_capacity_fit, w_availability, w_zone_compat,
				       ttl_seconds, search_radius_m, max_location_age_s, persist_top_n
				FROM admin_cfg.dispatch_rule_versions
				WHERE status = 'ACTIVE' AND effective_from <= :now AND (zone_id = :zone OR zone_id IS NULL)
				ORDER BY (zone_id IS NULL)
				LIMIT 1
				""")
				.param("now", utc(clock.now()))
				.param("zone", zoneId)
				.query((rs, i) -> new DispatchRule(
						rs.getObject("id", UUID.class),
						rs.getInt("version_no"),
						rs.getBigDecimal("w_distance"),
						rs.getBigDecimal("w_capacity_fit"),
						rs.getBigDecimal("w_availability"),
						rs.getBigDecimal("w_zone_compat"),
						rs.getInt("ttl_seconds"),
						rs.getInt("search_radius_m"),
						rs.getInt("max_location_age_s"),
						rs.getInt("persist_top_n")))
				.optional()
				.orElseThrow(() -> new BusinessException(ErrorCode.UNEXPECTED_FAILURE,
						"No active dispatch rule is configured."));
	}

	@Override
	@SuppressWarnings("unchecked")
	public PodPolicy effectivePolicy(UUID zoneId) {
		return jdbc.sql("""
				SELECT id, version_no, zone_id, requires_gps, max_gps_accuracy_m,
				       evidence_expression::text AS expression, recipient_name_rule, effective_from
				FROM admin_cfg.pod_policy_versions
				WHERE status = 'ACTIVE' AND effective_from <= :now AND (zone_id = :zone OR zone_id IS NULL)
				ORDER BY (zone_id IS NULL)
				LIMIT 1
				""")
				.param("now", utc(clock.now()))
				.param("zone", zoneId)
				.query((rs, i) -> new PodPolicy(
						rs.getObject("id", UUID.class),
						rs.getInt("version_no"),
						rs.getObject("zone_id") == null ? "DEFAULT" : "ZONE_OVERRIDE",
						rs.getBoolean("requires_gps"),
						(Integer) rs.getObject("max_gps_accuracy_m"),
						(Map<String, Object>) mapper.readValue(rs.getString("expression"), Map.class),
						rs.getString("recipient_name_rule"),
						rs.getObject("effective_from", OffsetDateTime.class).toInstant()))
				.optional()
				.orElseThrow(() -> new BusinessException(ErrorCode.UNEXPECTED_FAILURE,
						"No active POD policy is configured."));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
