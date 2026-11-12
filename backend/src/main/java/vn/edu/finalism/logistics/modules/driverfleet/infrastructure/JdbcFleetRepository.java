package vn.edu.finalism.logistics.modules.driverfleet.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository;
import vn.edu.finalism.logistics.shared.PageCursor;

@Repository
class JdbcFleetRepository implements FleetRepository {

	private static final UUID NIL = new UUID(0, 0);

	private static final String VEHICLE = """
			SELECT id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3, is_active, note, version, created_at
			FROM fleet.vehicles v
			""";

	private static final String DRIVER = """
			SELECT d.id, d.user_id, d.code, d.full_name, d.contact, d.is_active, d.availability, d.vehicle_id,
			       v.code AS vehicle_code, v.vehicle_type, d.version, d.created_at,
			       COALESCE((SELECT array_agg(z.zone_id ORDER BY z.zone_id) FROM fleet.driver_zones z
			                 WHERE z.driver_id = d.id), '{}') AS zones
			FROM fleet.drivers d
			LEFT JOIN fleet.vehicles v ON v.id = d.vehicle_id
			""";

	private final JdbcClient jdbc;

	JdbcFleetRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<VehicleRow> listVehicles(Boolean active, String query, PageCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder(VEHICLE).append(" WHERE 1 = 1");
		Map<String, Object> params = new LinkedHashMap<>();
		if (active != null) {
			sql.append(" AND v.is_active = :active");
			params.put("active", active);
		}
		if (query != null) {
			sql.append(" AND (v.code ILIKE :q OR v.vehicle_type ILIKE :q)");
			params.put("q", like(query));
		}
		if (cursor != null) {
			sql.append(" AND (date_trunc('milliseconds', v.created_at), v.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(cursor.sortKey()));
			params.put("cursorId", cursor.id().toString());
		}
		sql.append(" ORDER BY date_trunc('milliseconds', v.created_at) DESC, v.id DESC LIMIT :limit");
		params.put("limit", limit);
		return run(sql.toString(), params).query((rs, i) -> mapVehicle(rs)).list();
	}

	@Override
	public Optional<VehicleRow> findVehicle(UUID id) {
		return jdbc.sql(VEHICLE + " WHERE v.id = :id").param("id", id).query((rs, i) -> mapVehicle(rs)).optional();
	}

	@Override
	public Optional<VehicleRow> findVehicleForUpdate(UUID id) {
		return jdbc.sql(VEHICLE + " WHERE v.id = :id FOR UPDATE").param("id", id).query((rs, i) -> mapVehicle(rs)).optional();
	}

	@Override
	public boolean vehicleCodeExists(String code) {
		return jdbc.sql("SELECT count(*) FROM fleet.vehicles WHERE code = :code").param("code", code)
				.query(Integer.class).single() > 0;
	}

	@Override
	public void insertVehicle(UUID id, String code, String type, int weightG, long volumeCm3, String note, Instant now) {
		jdbc.sql("""
				INSERT INTO fleet.vehicles (id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3, note,
				                            created_at, updated_at)
				VALUES (:id, :code, :type, :weight, :volume, :note, :now, :now)
				""")
				.param("id", id).param("code", code).param("type", type).param("weight", weightG)
				.param("volume", volumeCm3).param("note", note, Types.VARCHAR).param("now", utc(now)).update();
	}

	@Override
	public boolean updateVehicle(UUID id, String type, int weightG, long volumeCm3, String note, long expectedVersion,
			Instant now) {
		return jdbc.sql("""
				UPDATE fleet.vehicles SET vehicle_type = :type, capacity_weight_g = :weight,
				       capacity_volume_cm3 = :volume, note = :note, updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version
				""")
				.param("type", type).param("weight", weightG).param("volume", volumeCm3)
				.param("note", note, Types.VARCHAR).param("now", utc(now)).param("id", id)
				.param("version", expectedVersion).update() == 1;
	}

	@Override
	public boolean setVehicleActive(UUID id, boolean active, long expectedVersion, Instant now) {
		return jdbc.sql("UPDATE fleet.vehicles SET is_active = :active, updated_at = :now, version = version + 1 WHERE id = :id AND version = :version")
				.param("active", active).param("now", utc(now)).param("id", id).param("version", expectedVersion)
				.update() == 1;
	}

	@Override
	public Optional<UUID> driverUsingVehicle(UUID vehicleId, UUID exceptDriverId) {
		return jdbc.sql("SELECT id FROM fleet.drivers WHERE vehicle_id = :vehicle AND id <> :except")
				.param("vehicle", vehicleId).param("except", exceptDriverId == null ? NIL : exceptDriverId)
				.query((rs, i) -> rs.getObject(1, UUID.class)).optional();
	}

	@Override
	public List<DriverRow> listDrivers(Set<UUID> scopeZoneIds, Boolean active, String query, PageCursor cursor,
			int limit) {
		StringBuilder sql = new StringBuilder(DRIVER).append("""
				 WHERE (EXISTS (SELECT 1 FROM fleet.driver_zones z WHERE z.driver_id = d.id AND z.zone_id IN (:zones))
				        OR NOT EXISTS (SELECT 1 FROM fleet.driver_zones z WHERE z.driver_id = d.id))
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("zones", scopeZoneIds.isEmpty() ? List.of(NIL) : List.copyOf(scopeZoneIds));
		if (active != null) {
			sql.append(" AND d.is_active = :active");
			params.put("active", active);
		}
		if (query != null) {
			sql.append(" AND (d.code ILIKE :q OR d.full_name ILIKE :q)");
			params.put("q", like(query));
		}
		if (cursor != null) {
			sql.append(" AND (date_trunc('milliseconds', d.created_at), d.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(cursor.sortKey()));
			params.put("cursorId", cursor.id().toString());
		}
		sql.append(" ORDER BY date_trunc('milliseconds', d.created_at) DESC, d.id DESC LIMIT :limit");
		params.put("limit", limit);
		return run(sql.toString(), params).query((rs, i) -> mapDriver(rs)).list();
	}

	@Override
	public Optional<DriverRow> findDriver(UUID id) {
		return jdbc.sql(DRIVER + " WHERE d.id = :id").param("id", id).query((rs, i) -> mapDriver(rs)).optional();
	}

	@Override
	public Optional<DriverRow> findDriverForUpdate(UUID id) {
		jdbc.sql("SELECT id FROM fleet.drivers WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
		return findDriver(id);
	}

	@Override
	public Optional<DriverRow> findDriverByUser(UUID userId) {
		return jdbc.sql(DRIVER + " WHERE d.user_id = :id").param("id", userId).query((rs, i) -> mapDriver(rs)).optional();
	}

	@Override
	public boolean userHasRole(UUID userId, String roleCode) {
		return jdbc.sql("""
				SELECT count(*) FROM iam.user_roles ur JOIN iam.roles r ON r.id = ur.role_id
				WHERE ur.user_id = :user AND r.code = :role
				""")
				.param("user", userId).param("role", roleCode).query(Integer.class).single() > 0;
	}

	@Override
	public List<DriverCandidate> driverCandidates() {
		return jdbc.sql("""
				SELECT u.id, u.email, u.display_name
				FROM iam.users u
				JOIN iam.user_roles ur ON ur.user_id = u.id
				JOIN iam.roles r ON r.id = ur.role_id AND r.code = 'DRIVER'
				WHERE u.status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM fleet.drivers d WHERE d.user_id = u.id)
				ORDER BY u.email LIMIT 200
				""")
				.query((rs, i) -> new DriverCandidate(rs.getObject("id", UUID.class), rs.getString("email"),
						rs.getString("display_name")))
				.list();
	}

	@Override
	public boolean driverExistsForUser(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM fleet.drivers WHERE user_id = :id").param("id", userId)
				.query(Integer.class).single() > 0;
	}

	@Override
	public String nextDriverCode() {
		Long next = jdbc.sql("SELECT nextval('fleet.driver_code_seq')").query(Long.class).single();
		return String.format("DRV-%06d", next);
	}

	@Override
	public void insertDriver(UUID id, UUID userId, String code, String fullName, String contact, UUID vehicleId,
			Instant now) {
		jdbc.sql("""
				INSERT INTO fleet.drivers (id, user_id, code, full_name, contact, vehicle_id, created_at, updated_at)
				VALUES (:id, :user, :code, :name, :contact, :vehicle, :now, :now)
				""")
				.param("id", id).param("user", userId).param("code", code).param("name", fullName)
				.param("contact", contact, Types.VARCHAR).param("vehicle", vehicleId, Types.OTHER)
				.param("now", utc(now)).update();
	}

	@Override
	public boolean updateDriver(UUID id, String fullName, String contact, UUID vehicleId, long expectedVersion,
			Instant now) {
		return jdbc.sql("""
				UPDATE fleet.drivers SET full_name = :name, contact = :contact, vehicle_id = :vehicle,
				       updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version
				""")
				.param("name", fullName).param("contact", contact, Types.VARCHAR).param("vehicle", vehicleId, Types.OTHER)
				.param("now", utc(now)).param("id", id).param("version", expectedVersion).update() == 1;
	}

	@Override
	public boolean setDriverActive(UUID id, boolean active, long expectedVersion, Instant now) {
		return jdbc.sql("""
				UPDATE fleet.drivers SET is_active = :active,
				       availability = CASE WHEN :active THEN availability ELSE 'OFFLINE' END,
				       updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version
				""")
				.param("active", active).param("now", utc(now)).param("id", id).param("version", expectedVersion)
				.update() == 1;
	}

	@Override
	public boolean setDriverAvailability(UUID id, String availability, long expectedVersion, Instant now) {
		return jdbc.sql("UPDATE fleet.drivers SET availability = :availability, updated_at = :now, version = version + 1 WHERE id = :id AND version = :version")
				.param("availability", availability).param("now", utc(now)).param("id", id)
				.param("version", expectedVersion).update() == 1;
	}

	@Override
	public void replaceDriverZones(UUID driverId, Collection<UUID> zoneIds) {
		jdbc.sql("DELETE FROM fleet.driver_zones WHERE driver_id = :id").param("id", driverId).update();
		for (UUID zoneId : zoneIds) {
			jdbc.sql("INSERT INTO fleet.driver_zones (driver_id, zone_id) VALUES (:driver, :zone)")
					.param("driver", driverId).param("zone", zoneId).update();
		}
	}

	@Override
	public Set<UUID> existingZoneIds(Collection<UUID> zoneIds) {
		if (zoneIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(jdbc.sql("SELECT id FROM admin_cfg.delivery_zones WHERE id IN (:ids)").param("ids", zoneIds)
				.query((rs, i) -> rs.getObject(1, UUID.class)).list());
	}

	@Override
	public List<UUID> openAssignmentIdsOfDriver(UUID driverId) {
		return jdbc.sql("""
				SELECT a.id FROM dispatch.driver_assignments a
				WHERE a.driver_id = :id AND a.is_current
				  AND NOT EXISTS (SELECT 1 FROM delivery.deliveries dl
				                  WHERE dl.current_assignment_id = a.id AND dl.status IN ('COMPLETED','CANCELLED'))
				ORDER BY a.decided_at
				""")
				.param("id", driverId).query((rs, i) -> rs.getObject(1, UUID.class)).list();
	}

	@Override
	public int pendingAssignmentCount(UUID driverId) {
		return jdbc.sql("SELECT count(*) FROM dispatch.driver_assignments WHERE driver_id = :id AND is_current AND status = 'PENDING'")
				.param("id", driverId).query(Integer.class).single();
	}

	@Override
	public void upsertLocation(UUID driverId, double lat, double lng, Double accuracyM, Instant recordedAt) {
		jdbc.sql("""
				INSERT INTO fleet.driver_locations (driver_id, location, accuracy_m, recorded_at)
				VALUES (:driver, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :accuracy, :at)
				ON CONFLICT (driver_id) DO UPDATE
				SET location = EXCLUDED.location, accuracy_m = EXCLUDED.accuracy_m, recorded_at = EXCLUDED.recorded_at
				""")
				.param("driver", driverId).param("lng", lng).param("lat", lat)
				.param("accuracy", accuracyM == null ? null : BigDecimal.valueOf(accuracyM).setScale(1, java.math.RoundingMode.HALF_UP),
						Types.NUMERIC)
				.param("at", utc(recordedAt)).update();
	}

	@Override
	public Optional<LocationRow> findLocation(UUID driverId) {
		return jdbc.sql("""
				SELECT ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng, accuracy_m, recorded_at
				FROM fleet.driver_locations WHERE driver_id = :id
				""")
				.param("id", driverId)
				.query((rs, i) -> new LocationRow(rs.getDouble("lat"), rs.getDouble("lng"),
						rs.getBigDecimal("accuracy_m") == null ? null : rs.getBigDecimal("accuracy_m").doubleValue(),
						rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
				.optional();
	}

	private JdbcClient.StatementSpec run(String sql, Map<String, Object> params) {
		JdbcClient.StatementSpec spec = jdbc.sql(sql);
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec;
	}

	private static VehicleRow mapVehicle(ResultSet rs) throws SQLException {
		return new VehicleRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("vehicle_type"),
				rs.getInt("capacity_weight_g"), rs.getLong("capacity_volume_cm3"), rs.getBoolean("is_active"),
				rs.getString("note"), rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private static DriverRow mapDriver(ResultSet rs) throws SQLException {
		return new DriverRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("code"),
				rs.getString("full_name"), rs.getString("contact"), rs.getBoolean("is_active"),
				rs.getString("availability"), rs.getObject("vehicle_id", UUID.class), rs.getString("vehicle_code"),
				rs.getString("vehicle_type"), Arrays.asList((UUID[]) rs.getArray("zones").getArray()),
				rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private static String like(String query) {
		return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
