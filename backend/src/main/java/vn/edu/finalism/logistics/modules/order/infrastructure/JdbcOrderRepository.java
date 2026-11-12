package vn.edu.finalism.logistics.modules.order.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository;
import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Repository
class JdbcOrderRepository implements OrderRepository {

	private static final String SELECT = """
			SELECT id, code, customer_id, status, pickup_address,
			       ST_Y(pickup_point::geometry) AS pickup_lat, ST_X(pickup_point::geometry) AS pickup_lng,
			       dropoff_address,
			       ST_Y(dropoff_point::geometry) AS dropoff_lat, ST_X(dropoff_point::geometry) AS dropoff_lng,
			       recipient_name, recipient_contact, weight_g, length_cm, width_cm, height_cm, volume_cm3,
			       package_note, window_start, window_end, zone_id, zone_version_id, version, created_at,
			       cancelled_from_status, cancel_reason
			FROM ordering.orders
			""";

	private final JdbcClient jdbc;

	JdbcOrderRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public String nextCode() {
		Long next = jdbc.sql("SELECT nextval('ordering.order_code_seq')").query(Long.class).single();
		return String.format("ORD-%06d", next);
	}

	@Override
	public void insert(Order o, Instant now) {
		OffsetDateTime ts = utc(now);
		jdbc.sql("""
				INSERT INTO ordering.orders
				  (id, code, customer_id, status, pickup_address, pickup_point, dropoff_address, dropoff_point,
				   recipient_name, recipient_contact, weight_g, length_cm, width_cm, height_cm, volume_cm3,
				   package_note, window_start, window_end, zone_id, zone_version_id, created_at, updated_at, version)
				VALUES
				  (:id, :code, :customerId, :status, :pickupAddress,
				   CASE WHEN CAST(:pLng AS double precision) IS NULL THEN NULL
				        ELSE ST_SetSRID(ST_MakePoint(:pLng, :pLat), 4326)::geography END,
				   :dropoffAddress,
				   CASE WHEN CAST(:dLng AS double precision) IS NULL THEN NULL
				        ELSE ST_SetSRID(ST_MakePoint(:dLng, :dLat), 4326)::geography END,
				   :recipientName, :recipientContact, :weightG, :lengthCm, :widthCm, :heightCm, :volumeCm3,
				   :note, :windowStart, :windowEnd, :zoneId, :zoneVersionId, :now, :now, 0)
				""")
				.param("id", o.id())
				.param("code", o.code())
				.param("customerId", o.customerId())
				.param("status", o.status().name())
				.param("pickupAddress", o.pickupAddress())
				.param("pLng", o.pickup() == null ? null : o.pickup().lng(), Types.DOUBLE)
				.param("pLat", o.pickup() == null ? null : o.pickup().lat(), Types.DOUBLE)
				.param("dropoffAddress", o.dropoffAddress())
				.param("dLng", o.dropoff() == null ? null : o.dropoff().lng(), Types.DOUBLE)
				.param("dLat", o.dropoff() == null ? null : o.dropoff().lat(), Types.DOUBLE)
				.param("recipientName", o.recipientName())
				.param("recipientContact", o.recipientContact(), Types.VARCHAR)
				.param("weightG", o.weightG())
				.param("lengthCm", o.lengthCm(), Types.INTEGER)
				.param("widthCm", o.widthCm(), Types.INTEGER)
				.param("heightCm", o.heightCm(), Types.INTEGER)
				.param("volumeCm3", o.volumeCm3(), Types.BIGINT)
				.param("note", o.packageNote(), Types.VARCHAR)
				.param("windowStart", utc(o.windowStart()))
				.param("windowEnd", utc(o.windowEnd()))
				.param("zoneId", o.zoneId(), Types.OTHER)
				.param("zoneVersionId", o.zoneVersionId(), Types.OTHER)
				.param("now", ts)
				.update();
	}

	@Override
	public Optional<Order> findById(UUID id) {
		return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> map(rs)).optional();
	}

	@Override
	public Optional<Order> findByIdForUpdate(UUID id) {
		return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query((rs, i) -> map(rs)).optional();
	}

	@Override
	public List<Order> findAllForUpdate(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql(SELECT + " WHERE id IN (:ids) ORDER BY id FOR UPDATE").param("ids", ids)
				.query((rs, i) -> map(rs)).list();
	}

	@Override
	public void updateState(Order o, Instant now) {
		jdbc.sql("""
				UPDATE ordering.orders
				SET status = :status, zone_id = :zoneId, zone_version_id = :zoneVersionId,
				    pickup_address = :pickupAddress,
				    pickup_point = CASE WHEN CAST(:pLng AS double precision) IS NULL THEN NULL
				                        ELSE ST_SetSRID(ST_MakePoint(:pLng, :pLat), 4326)::geography END,
				    dropoff_address = :dropoffAddress,
				    dropoff_point = CASE WHEN CAST(:dLng AS double precision) IS NULL THEN NULL
				                         ELSE ST_SetSRID(ST_MakePoint(:dLng, :dLat), 4326)::geography END,
				    recipient_name = :recipientName, recipient_contact = :recipientContact, weight_g = :weightG,
				    length_cm = :lengthCm, width_cm = :widthCm, height_cm = :heightCm, volume_cm3 = :volumeCm3,
				    package_note = :note, window_start = :windowStart, window_end = :windowEnd,
				    cancelled_from_status = :cancelledFrom, cancel_reason = :cancelReason,
				    updated_at = :now, version = version + 1
				WHERE id = :id
				""")
				.param("status", o.status().name())
				.param("zoneId", o.zoneId(), Types.OTHER)
				.param("zoneVersionId", o.zoneVersionId(), Types.OTHER)
				.param("pickupAddress", o.pickupAddress())
				.param("pLng", o.pickup() == null ? null : o.pickup().lng(), Types.DOUBLE)
				.param("pLat", o.pickup() == null ? null : o.pickup().lat(), Types.DOUBLE)
				.param("dropoffAddress", o.dropoffAddress())
				.param("dLng", o.dropoff() == null ? null : o.dropoff().lng(), Types.DOUBLE)
				.param("dLat", o.dropoff() == null ? null : o.dropoff().lat(), Types.DOUBLE)
				.param("recipientName", o.recipientName())
				.param("recipientContact", o.recipientContact(), Types.VARCHAR)
				.param("weightG", o.weightG())
				.param("lengthCm", o.lengthCm(), Types.INTEGER)
				.param("widthCm", o.widthCm(), Types.INTEGER)
				.param("heightCm", o.heightCm(), Types.INTEGER)
				.param("volumeCm3", o.volumeCm3(), Types.BIGINT)
				.param("note", o.packageNote(), Types.VARCHAR)
				.param("windowStart", utc(o.windowStart()))
				.param("windowEnd", utc(o.windowEnd()))
				.param("cancelledFrom", o.cancelledFrom() == null ? null : o.cancelledFrom().name(), Types.VARCHAR)
				.param("cancelReason", o.cancelReason(), Types.VARCHAR)
				.param("now", utc(now))
				.param("id", o.id())
				.update();
	}

	@Override
	public List<OrderRow> search(SearchCriteria c) {
		StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
		List<Object[]> params = new ArrayList<>();
		if (c.customerId() != null) {
			sql.append(" AND customer_id = :customerId");
			params.add(new Object[] {"customerId", c.customerId()});
		}
		if (c.zoneIds() != null) {
			sql.append(" AND zone_id IN (:zoneIds)");
			params.add(new Object[] {"zoneIds", c.zoneIds()});
		}
		if (c.status() != null) {
			sql.append(" AND status = :status");
			params.add(new Object[] {"status", c.status().name()});
		}
		if (c.zoneId() != null) {
			sql.append(" AND zone_id = :zoneId");
			params.add(new Object[] {"zoneId", c.zoneId()});
		}
		if (c.codePrefix() != null && !c.codePrefix().isBlank()) {
			sql.append(" AND code ILIKE :codePrefix");
			params.add(new Object[] {"codePrefix", c.codePrefix().trim() + "%"});
		}
		if (c.windowFrom() != null) {
			sql.append(" AND window_end > :windowFrom");
			params.add(new Object[] {"windowFrom", utc(c.windowFrom())});
		}
		if (c.windowTo() != null) {
			sql.append(" AND window_start < :windowTo");
			params.add(new Object[] {"windowTo", utc(c.windowTo())});
		}
		if (c.after() != null) {
			sql.append(" AND (created_at, id) < (:afterTs, :afterId)");
			params.add(new Object[] {"afterTs", utc(c.after().sortKey())});
			params.add(new Object[] {"afterId", c.after().id()});
		}
		sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
		params.add(new Object[] {"limit", c.limit() + 1});

		JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
		for (Object[] param : params) {
			spec = spec.param((String) param[0], param[1]);
		}
		return spec.query((rs, i) -> new OrderRow(map(rs),
				rs.getObject("created_at", OffsetDateTime.class).toInstant())).list();
	}

	private static Order map(ResultSet rs) throws SQLException {
		return new Order(
				rs.getObject("id", UUID.class),
				rs.getString("code"),
				rs.getObject("customer_id", UUID.class),
				OrderStatus.valueOf(rs.getString("status")),
				rs.getString("pickup_address"),
				point(rs, "pickup_lat", "pickup_lng"),
				rs.getString("dropoff_address"),
				point(rs, "dropoff_lat", "dropoff_lng"),
				rs.getString("recipient_name"),
				rs.getString("recipient_contact"),
				rs.getInt("weight_g"),
				(Integer) rs.getObject("length_cm"),
				(Integer) rs.getObject("width_cm"),
				(Integer) rs.getObject("height_cm"),
				(Long) rs.getObject("volume_cm3"),
				rs.getString("package_note"),
				rs.getObject("window_start", OffsetDateTime.class).toInstant(),
				rs.getObject("window_end", OffsetDateTime.class).toInstant(),
				rs.getObject("zone_id", UUID.class),
				rs.getObject("zone_version_id", UUID.class),
				rs.getLong("version"),
				rs.getString("cancelled_from_status") == null ? null
						: OrderStatus.valueOf(rs.getString("cancelled_from_status")),
				rs.getString("cancel_reason"));
	}

	private static GeoPoint point(ResultSet rs, String latColumn, String lngColumn) throws SQLException {
		Double lat = (Double) rs.getObject(latColumn);
		Double lng = (Double) rs.getObject(lngColumn);
		return lat == null || lng == null ? null : new GeoPoint(lat, lng);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
