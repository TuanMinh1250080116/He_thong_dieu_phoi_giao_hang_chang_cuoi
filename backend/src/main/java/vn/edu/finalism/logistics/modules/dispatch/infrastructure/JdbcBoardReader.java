package vn.edu.finalism.logistics.modules.dispatch.infrastructure;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.dispatch.application.port.BoardReadPort;
import vn.edu.finalism.logistics.shared.PageCursor;

@Repository
class JdbcBoardReader implements BoardReadPort {

	private static final UUID NIL = new UUID(0, 0);

	private static final String OPEN_EXCEPTIONS = "(SELECT count(*) FROM exc.delivery_exceptions x WHERE x.delivery_id = dl.id AND x.status IN ('OPEN','INVESTIGATING'))";

	private static final String CUSTOMER_IMPACT = "EXISTS (SELECT 1 FROM exc.delivery_exceptions x WHERE x.delivery_id = dl.id AND x.status IN ('OPEN','INVESTIGATING') AND x.impact = 'CUSTOMER_IMPACTING')";

	private static final String DELIVERY_COLUMNS = """
			dl.id, dl.code, b.code AS batch_code, b.zone_id, dl.status, dr.id AS driver_id, dr.full_name AS driver_name,
			b.order_count,
			(SELECT count(*) FROM delivery.delivery_stops s WHERE s.delivery_id = dl.id AND s.status = 'COMPLETED') AS completed_stops,
			(SELECT count(*) FROM delivery.delivery_stops s WHERE s.delivery_id = dl.id) AS total_stops,
			dl.updated_at AS last_update_at, dl.created_at,
			""" + OPEN_EXCEPTIONS + " AS open_exception_count, " + CUSTOMER_IMPACT + " AS customer_impacting";

	private final JdbcClient jdbc;

	JdbcBoardReader(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record PanelSpec(String name, String columns, String fromWhere, String orderBy, boolean usesNow,
			String queryColumns) {
	}

	@Override
	public Map<String, Panel> board(Set<UUID> zoneIds, String query, TimeRange range, Instant now, int itemLimit) {
		List<PanelSpec> specs = List.of(
				new PanelSpec("readyOrders",
						"o.id, o.code, o.zone_id, o.window_start, o.window_end, o.weight_g",
						"FROM ordering.orders o WHERE o.status = 'READY_FOR_DISPATCH' AND o.zone_id IN (:zones)",
						"o.window_start, o.id", false, "o.code"),
				new PanelSpec("draftBatches",
						"b.id, b.code, b.zone_id, b.order_count, b.total_weight_g, b.created_at",
						"FROM dispatch.delivery_batches b WHERE b.status = 'DRAFT' AND b.zone_id IN (:zones)",
						"b.created_at DESC, b.id", false, "b.code"),
				new PanelSpec("lockedWithoutRecommendation",
						"b.id, b.code, b.zone_id, b.order_count, b.locked_at",
						"FROM dispatch.delivery_batches b WHERE b.status = 'LOCKED' AND b.zone_id IN (:zones)"
								+ " AND NOT EXISTS (SELECT 1 FROM dispatch.dispatch_decisions d WHERE d.batch_id = b.id AND d.status <> 'CANCELLED')",
						"b.locked_at, b.id", false, "b.code"),
				new PanelSpec("expiredRecommendations",
						"d.id AS decision_id, b.id AS batch_id, b.code, b.zone_id, "
								+ "(SELECT max(s.expires_at) FROM dispatch.recommendation_snapshots s WHERE s.decision_id = d.id) AS expired_at",
						"FROM dispatch.dispatch_decisions d JOIN dispatch.delivery_batches b ON b.id = d.batch_id"
								+ " WHERE d.status = 'PENDING_DECISION' AND b.zone_id IN (:zones)"
								+ " AND NOT EXISTS (SELECT 1 FROM dispatch.recommendation_snapshots s WHERE s.decision_id = d.id AND s.status = 'ACTIVE' AND s.expires_at > :now)",
						"d.updated_at, d.id", true, "b.code"),
				new PanelSpec("awaitingDriverResponse",
						"d.id AS decision_id, b.id AS batch_id, b.code, b.zone_id, dr.full_name AS driver_name, a.decided_at",
						"FROM dispatch.dispatch_decisions d JOIN dispatch.delivery_batches b ON b.id = d.batch_id"
								+ " JOIN dispatch.driver_assignments a ON a.decision_id = d.id AND a.is_current"
								+ " JOIN fleet.drivers dr ON dr.id = a.driver_id"
								+ " WHERE d.status = 'AWAITING_DRIVER_RESPONSE' AND b.zone_id IN (:zones)",
						"a.decided_at, d.id", false, "b.code"),
				new PanelSpec("awaitingReassignment",
						"d.id AS decision_id, b.id AS batch_id, b.code, b.zone_id, dr.full_name AS driver_name,"
								+ " a.rejection_reason_code, a.responded_at",
						"FROM dispatch.dispatch_decisions d JOIN dispatch.delivery_batches b ON b.id = d.batch_id"
								+ " JOIN dispatch.driver_assignments a ON a.decision_id = d.id"
								+ " AND a.sequence_no = (SELECT max(x.sequence_no) FROM dispatch.driver_assignments x WHERE x.decision_id = d.id)"
								+ " JOIN fleet.drivers dr ON dr.id = a.driver_id"
								+ " WHERE d.status = 'AWAITING_REASSIGNMENT' AND b.zone_id IN (:zones)",
						"a.responded_at, d.id", false, "b.code"),
				new PanelSpec("activeDeliveries", DELIVERY_COLUMNS,
						"FROM delivery.deliveries dl JOIN dispatch.delivery_batches b ON b.id = dl.batch_id"
								+ " JOIN fleet.drivers dr ON dr.id = dl.assigned_driver_id"
								+ " WHERE dl.status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS') AND b.zone_id IN (:zones)",
						"dl.updated_at DESC, dl.id", false, "dl.code"),
				new PanelSpec("openExceptions",
						"e.id, e.code, e.severity, e.impact, e.status, e.reported_at, dl.code AS delivery_code, b.zone_id",
						"FROM exc.delivery_exceptions e JOIN delivery.deliveries dl ON dl.id = e.delivery_id"
								+ " JOIN dispatch.delivery_batches b ON b.id = dl.batch_id"
								+ " WHERE e.status IN ('OPEN','INVESTIGATING') AND b.zone_id IN (:zones)",
						"CASE e.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, e.reported_at, e.id",
						false, "e.code"));
		Map<String, Panel> panels = new LinkedHashMap<>();
		for (PanelSpec spec : specs) {
			panels.put(spec.name(), panel(spec, zoneIds, query, "readyOrders".equals(spec.name()) ? range : TimeRange.NONE, now, itemLimit));
		}
		return panels;
	}

	private Panel panel(PanelSpec spec, Set<UUID> zoneIds, String query, TimeRange range, Instant now, int itemLimit) {
		String filter = query == null ? "" : " AND " + spec.queryColumns() + " ILIKE :q";
		if (range.from() != null) {
			filter += " AND o.window_end > :from";
		}
		if (range.to() != null) {
			filter += " AND o.window_start < :to";
		}
		String fromWhere = spec.fromWhere() + filter;
		int count = bind(jdbc.sql("SELECT count(*) " + fromWhere), spec, zoneIds, query, range, now).query(Integer.class)
				.single();
		List<Map<String, Object>> items = bind(
				jdbc.sql("SELECT " + spec.columns() + " " + fromWhere + " ORDER BY " + spec.orderBy() + " LIMIT :limit")
						.param("limit", itemLimit),
				spec, zoneIds, query, range, now).query((rs, i) -> row(rs)).list();
		return new Panel(count, items);
	}

	private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, PanelSpec spec, Set<UUID> zoneIds,
			String query, TimeRange range, Instant now) {
		JdbcClient.StatementSpec bound = statement.param("zones", zones(zoneIds));
		if (query != null) {
			bound = bound.param("q", like(query));
		}
		if (range.from() != null) {
			bound = bound.param("from", utc(range.from()));
		}
		if (range.to() != null) {
			bound = bound.param("to", utc(range.to()));
		}
		if (spec.usesNow()) {
			bound = bound.param("now", utc(now));
		}
		return bound;
	}

	@Override
	public List<Map<String, Object>> batches(Set<UUID> zoneIds, String status, String query, TimeRange range,
			PageCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT b.id, b.code, b.zone_id, b.status, b.order_count, b.total_weight_g, b.total_volume_cm3,
				       lower(b.planned_window) AS planned_start, upper(b.planned_window) AS planned_end, b.version,
				       b.created_at,
				       (SELECT d.status FROM dispatch.dispatch_decisions d WHERE d.batch_id = b.id AND d.status <> 'CANCELLED') AS decision_status
				FROM dispatch.delivery_batches b
				WHERE b.zone_id IN (:zones)
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("zones", zones(zoneIds));
		if (status != null) {
			sql.append(" AND b.status = :status");
			params.put("status", status);
		}
		if (query != null) {
			sql.append(" AND b.code ILIKE :q");
			params.put("q", like(query));
		}
		if (range.from() != null) {
			sql.append(" AND b.created_at >= :from");
			params.put("from", utc(range.from()));
		}
		if (range.to() != null) {
			sql.append(" AND b.created_at < :to");
			params.put("to", utc(range.to()));
		}
		if (cursor != null) {
			sql.append(" AND (date_trunc('milliseconds', b.created_at), b.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(cursor.sortKey()));
			params.put("cursorId", cursor.id().toString());
		}
		sql.append(" ORDER BY date_trunc('milliseconds', b.created_at) DESC, b.id DESC LIMIT :limit");
		params.put("limit", limit);
		return run(sql.toString(), params);
	}

	@Override
	public List<Map<String, Object>> deliveries(Set<UUID> zoneIds, List<String> statuses, String query,
			TimeRange range, PageCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder("SELECT " + DELIVERY_COLUMNS + """

				FROM delivery.deliveries dl
				JOIN dispatch.delivery_batches b ON b.id = dl.batch_id
				JOIN fleet.drivers dr ON dr.id = dl.assigned_driver_id
				WHERE b.zone_id IN (:zones) AND dl.status IN (:statuses)
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("zones", zones(zoneIds));
		params.put("statuses", statuses);
		if (query != null) {
			sql.append(" AND (dl.code ILIKE :q OR b.code ILIKE :q OR dr.full_name ILIKE :q)");
			params.put("q", like(query));
		}
		if (range.from() != null) {
			sql.append(" AND dl.created_at >= :from");
			params.put("from", utc(range.from()));
		}
		if (range.to() != null) {
			sql.append(" AND dl.created_at < :to");
			params.put("to", utc(range.to()));
		}
		if (cursor != null) {
			sql.append(" AND (date_trunc('milliseconds', dl.created_at), dl.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(cursor.sortKey()));
			params.put("cursorId", cursor.id().toString());
		}
		sql.append(" ORDER BY date_trunc('milliseconds', dl.created_at) DESC, dl.id DESC LIMIT :limit");
		params.put("limit", limit);
		return run(sql.toString(), params);
	}

	@Override
	public List<Map<String, Object>> driverDeliveries(UUID driverId, List<String> statuses, TimeRange range,
			PageCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT dl.id, dl.code, dl.status, b.code AS batch_code, z.code AS zone_code,
				       lower(b.planned_window) AS window_start, upper(b.planned_window) AS window_end,
				       (SELECT count(*) FROM delivery.delivery_stops s WHERE s.delivery_id = dl.id) AS stop_count,
				       (SELECT count(*) FROM delivery.delivery_stops s WHERE s.delivery_id = dl.id AND s.status = 'COMPLETED') AS completed_stops,
				       dl.current_assignment_id AS assignment_id, a.status AS assignment_status, dd.version AS decision_version,
				       (SELECT string_agg(x.code, ', ' ORDER BY x.code) FROM exc.delivery_exceptions x WHERE x.delivery_id = dl.id) AS exception_codes,
				       dl.cancel_reason AS note, dl.created_at, dl.completed_at, dl.cancelled_at
				FROM delivery.deliveries dl
				JOIN dispatch.delivery_batches b ON b.id = dl.batch_id
				JOIN admin_cfg.delivery_zones z ON z.id = b.zone_id
				JOIN dispatch.dispatch_decisions dd ON dd.id = dl.decision_id
				LEFT JOIN dispatch.driver_assignments a ON a.id = dl.current_assignment_id
				WHERE dl.assigned_driver_id = :driver
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("driver", driverId);
		if (statuses != null && !statuses.isEmpty()) {
			sql.append(" AND dl.status IN (:statuses)");
			params.put("statuses", statuses);
		}
		if (range.from() != null) {
			sql.append(" AND dl.created_at >= :from");
			params.put("from", utc(range.from()));
		}
		if (range.to() != null) {
			sql.append(" AND dl.created_at < :to");
			params.put("to", utc(range.to()));
		}
		if (cursor != null) {
			sql.append(" AND (date_trunc('milliseconds', dl.created_at), dl.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(cursor.sortKey()));
			params.put("cursorId", cursor.id().toString());
		}
		sql.append(" ORDER BY date_trunc('milliseconds', dl.created_at) DESC, dl.id DESC LIMIT :limit");
		params.put("limit", limit);
		return run(sql.toString(), params);
	}

	@Override
	public Map<String, Object> driverDeliveryOverview(UUID driverId, UUID deliveryId) {
		List<Map<String, Object>> head = run("""
				SELECT dl.id, dl.code, dl.status, b.code AS batch_code, z.code AS zone_code, z.name AS zone_name,
				       lower(b.planned_window) AS window_start, upper(b.planned_window) AS window_end,
				       v.code AS vehicle_code, v.vehicle_type, dl.current_assignment_id AS assignment_id,
				       a.status AS assignment_status, dd.version AS decision_version, dl.cancel_reason AS note
				FROM delivery.deliveries dl
				JOIN dispatch.delivery_batches b ON b.id = dl.batch_id
				JOIN admin_cfg.delivery_zones z ON z.id = b.zone_id
				JOIN dispatch.dispatch_decisions dd ON dd.id = dl.decision_id
				LEFT JOIN fleet.vehicles v ON v.id = dl.vehicle_id
				LEFT JOIN dispatch.driver_assignments a ON a.id = dl.current_assignment_id
				WHERE dl.id = :delivery AND dl.assigned_driver_id = :driver
				""", new LinkedHashMap<>(Map.of("delivery", deliveryId, "driver", driverId)));
		if (head.isEmpty()) {
			return null;
		}
		List<Map<String, Object>> stops = run("""
				SELECT s.id, s.sequence_no, o.code AS order_code, s.address_text AS address, s.window_start, s.window_end,
				       ST_Y(s.location::geometry) AS lat, ST_X(s.location::geometry) AS lng,
				       o.weight_g, s.status,
				       (SELECT string_agg(x.code, ', ' ORDER BY x.code) FROM exc.delivery_exceptions x
				         WHERE x.delivery_id = s.delivery_id AND x.stop_id = s.id) AS exception_codes
				FROM delivery.delivery_stops s JOIN ordering.orders o ON o.id = s.order_id
				WHERE s.delivery_id = :delivery ORDER BY s.sequence_no
				""", new LinkedHashMap<>(Map.of("delivery", deliveryId)));
		Map<String, Object> result = new LinkedHashMap<>(head.get(0));
		result.put("stops", stops);
		return result;
	}

	private List<Map<String, Object>> run(String sql, Map<String, Object> params) {
		JdbcClient.StatementSpec spec = jdbc.sql(sql);
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query((rs, i) -> row(rs)).list();
	}

	private static Map<String, Object> row(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> row = new LinkedHashMap<>();
		for (int column = 1; column <= meta.getColumnCount(); column++) {
			Object value = rs.getObject(column);
			if (value instanceof OffsetDateTime time) {
				value = time.toInstant();
			}
			else if (value instanceof java.sql.Timestamp timestamp) {
				value = timestamp.toInstant();
			}
			row.put(camel(meta.getColumnLabel(column)), value);
		}
		return row;
	}

	private static String camel(String snake) {
		StringBuilder out = new StringBuilder();
		boolean upper = false;
		for (char c : snake.toCharArray()) {
			if (c == '_') {
				upper = true;
			}
			else {
				out.append(upper ? Character.toUpperCase(c) : c);
				upper = false;
			}
		}
		return out.toString();
	}

	private static List<UUID> zones(Set<UUID> zoneIds) {
		return zoneIds.isEmpty() ? List.of(NIL) : List.copyOf(zoneIds);
	}

	private static String like(String query) {
		return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
