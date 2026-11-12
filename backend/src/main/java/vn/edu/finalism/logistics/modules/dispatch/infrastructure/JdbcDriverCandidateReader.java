package vn.edu.finalism.logistics.modules.dispatch.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.dispatch.application.port.DriverCandidateReadPort;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverFacts;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Repository
class JdbcDriverCandidateReader implements DriverCandidateReadPort {

	private static final String BASE = """
			WITH pts AS (
			  SELECT o.pickup_point AS pt FROM dispatch.batch_orders bo
			  JOIN ordering.orders o ON o.id = bo.order_id
			  WHERE bo.batch_id = :batch AND bo.released_at IS NULL
			  UNION ALL
			  SELECT o.dropoff_point FROM dispatch.batch_orders bo
			  JOIN ordering.orders o ON o.id = bo.order_id
			  WHERE bo.batch_id = :batch AND bo.released_at IS NULL
			)
			SELECT d.id AS driver_id, d.user_id, d.full_name, d.is_active, d.availability, d.vehicle_id,
			       v.vehicle_type, COALESCE(v.is_active, false) AS vehicle_active,
			       v.capacity_weight_g, v.capacity_volume_cm3,
			       (dz.zone_id IS NOT NULL) AS serves_zone,
			       l.recorded_at,
			       ST_Distance(l.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_m,
			       EXISTS (SELECT 1 FROM dispatch.driver_commitments c
			               WHERE c.driver_id = d.id AND c.status = 'ACTIVE'
			                 AND c.time_window && tstzrange(:ws, :we, '[)')) AS has_conflict,
			       (SELECT count(*) FROM dispatch.driver_assignments a
			        WHERE a.driver_id = d.id AND a.status IN ('PENDING','ACCEPTED')
			          AND a.decided_at >= now() - interval '24 hours') AS recent_assignments,
			       COALESCE((SELECT avg(CASE WHEN EXISTS (
			                   SELECT 1 FROM fleet.driver_zones dz2
			                   JOIN admin_cfg.delivery_zone_versions zv
			                     ON zv.zone_id = dz2.zone_id AND zv.status = 'ACTIVE'
			                   WHERE dz2.driver_id = d.id AND ST_Covers(zv.boundary, p.pt)) THEN 1.0 ELSE 0.0 END)
			                 FROM pts p), 0) AS zone_coverage
			FROM fleet.drivers d
			LEFT JOIN fleet.driver_zones dz ON dz.driver_id = d.id AND dz.zone_id = :zone
			LEFT JOIN fleet.vehicles v ON v.id = d.vehicle_id
			LEFT JOIN fleet.driver_locations l ON l.driver_id = d.id
			""";

	private final JdbcClient jdbc;

	JdbcDriverCandidateReader(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public GeoPoint pickupCentroid(UUID batchId) {
		return jdbc.sql("""
				SELECT ST_Y(c::geometry) AS lat, ST_X(c::geometry) AS lng
				FROM (SELECT ST_Centroid(ST_Collect(o.pickup_point::geometry))::geography AS c
				      FROM dispatch.batch_orders bo
				      JOIN ordering.orders o ON o.id = bo.order_id
				      WHERE bo.batch_id = :batch AND bo.released_at IS NULL) t
				""")
				.param("batch", batchId)
				.query((rs, i) -> new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")))
				.single();
	}

	@Override
	public List<DriverFacts> loadCandidates(UUID batchId, UUID zoneId, GeoPoint pickup, int radiusM,
			Instant windowStart, Instant windowEnd) {
		return jdbc.sql(BASE + """
				WHERE dz.zone_id IS NOT NULL
				  AND (l.location IS NULL
				       OR ST_DWithin(l.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius))
				""")
				.param("batch", batchId)
				.param("lng", pickup.lng()).param("lat", pickup.lat()).param("zone", zoneId)
				.param("ws", utc(windowStart)).param("we", utc(windowEnd)).param("radius", radiusM)
				.query((rs, i) -> map(rs)).list();
	}

	@Override
	public Optional<DriverFacts> loadOne(UUID batchId, UUID driverId, UUID zoneId, GeoPoint pickup,
			Instant windowStart, Instant windowEnd) {
		return jdbc.sql(BASE + " WHERE d.id = :driver")
				.param("batch", batchId)
				.param("lng", pickup.lng()).param("lat", pickup.lat()).param("zone", zoneId)
				.param("ws", utc(windowStart)).param("we", utc(windowEnd)).param("driver", driverId)
				.query((rs, i) -> map(rs)).optional();
	}

	private static DriverFacts map(ResultSet rs) throws SQLException {
		OffsetDateTime recorded = rs.getObject("recorded_at", OffsetDateTime.class);
		return new DriverFacts(
				rs.getObject("driver_id", UUID.class),
				rs.getObject("user_id", UUID.class),
				rs.getString("full_name"),
				rs.getBoolean("is_active"),
				rs.getString("availability"),
				rs.getObject("vehicle_id", UUID.class),
				rs.getString("vehicle_type"),
				rs.getBoolean("vehicle_active"),
				(Integer) rs.getObject("capacity_weight_g"),
				(Long) rs.getObject("capacity_volume_cm3"),
				rs.getBoolean("serves_zone"),
				recorded == null ? null : recorded.toInstant(),
				(Double) rs.getObject("distance_m"),
				rs.getBoolean("has_conflict"),
				rs.getInt("recent_assignments"),
				rs.getDouble("zone_coverage"));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
