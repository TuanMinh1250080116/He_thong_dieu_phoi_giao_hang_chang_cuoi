package vn.edu.finalism.logistics.modules.delivery.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStatus;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStop;
import vn.edu.finalism.logistics.modules.delivery.domain.StopStatus;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Repository
class JdbcDeliveryRepository implements DeliveryRepository {

	private static final String SELECT = """
			SELECT id, code, batch_id, decision_id, status, current_assignment_id, assigned_driver_id, vehicle_id,
			       pod_policy_version_id, pod_policy_snapshot::text AS policy, started_at, completed_at, version
			FROM delivery.deliveries
			""";

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	JdbcDeliveryRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	public String nextCode() {
		Long next = jdbc.sql("SELECT nextval('delivery.delivery_code_seq')").query(Long.class).single();
		return String.format("DLV-%06d", next);
	}

	@Override
	public void insert(Delivery d, Instant now) {
		jdbc.sql("""
				INSERT INTO delivery.deliveries
				  (id, code, batch_id, decision_id, status, current_assignment_id, assigned_driver_id, vehicle_id,
				   created_at, updated_at, version)
				VALUES (:id, :code, :batch, :decision, :status, :assignment, :driver, :vehicle, :now, :now, 0)
				""")
				.param("id", d.id()).param("code", d.code()).param("batch", d.batchId())
				.param("decision", d.decisionId()).param("status", d.status().name())
				.param("assignment", d.currentAssignmentId()).param("driver", d.assignedDriverId())
				.param("vehicle", d.vehicleId()).param("now", utc(now))
				.update();
		Set<UUID> orderIds = new LinkedHashSet<>();
		d.stops().forEach(s -> orderIds.add(s.orderId()));
		for (UUID orderId : orderIds) {
			jdbc.sql("INSERT INTO delivery.delivery_orders (delivery_id, order_id) VALUES (:delivery, :order)")
					.param("delivery", d.id()).param("order", orderId).update();
		}
		for (DeliveryStop s : d.stops()) {
			jdbc.sql("""
					INSERT INTO delivery.delivery_stops
					  (id, delivery_id, sequence_no, stop_type, order_id, address_text, location, window_start,
					   window_end, status)
					VALUES (:id, :delivery, :seq, 'DROPOFF', :order, :address,
					        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :ws, :we, 'PENDING')
					""")
					.param("id", s.id()).param("delivery", d.id()).param("seq", s.sequenceNo())
					.param("order", s.orderId()).param("address", s.addressText())
					.param("lng", s.location().lng()).param("lat", s.location().lat())
					.param("ws", utc(s.windowStart())).param("we", utc(s.windowEnd()))
					.update();
		}
	}

	@Override
	public Optional<Delivery> findById(UUID id) {
		return find(SELECT + " WHERE id = :id", id);
	}

	@Override
	public Optional<Delivery> findByIdForUpdate(UUID id) {
		return find(SELECT + " WHERE id = :id FOR UPDATE", id);
	}

	@Override
	public Optional<Delivery> findByAssignmentIdForUpdate(UUID assignmentId) {
		return find(SELECT + " WHERE current_assignment_id = :id FOR UPDATE", assignmentId);
	}

	@Override
	public Optional<Delivery> findOpenByDecisionIdForUpdate(UUID decisionId) {
		return find(SELECT + " WHERE decision_id = :id AND status <> 'CANCELLED' FOR UPDATE", decisionId);
	}

	@Override
	public boolean cancel(UUID id, long expectedVersion, String reason, Instant now) {
		return jdbc.sql("""
				UPDATE delivery.deliveries
				SET status = 'CANCELLED', cancelled_at = :now, cancel_reason = :reason, updated_at = :now,
				    version = version + 1
				WHERE id = :id AND version = :expected
				""")
				.param("now", utc(now)).param("reason", reason).param("id", id).param("expected", expectedVersion)
				.update() == 1;
	}

	@Override
	public boolean update(Delivery d, long expectedVersion, Instant now) {
		String policy = d.podPolicySnapshot() == null ? null : mapper.writeValueAsString(d.podPolicySnapshot());
		return jdbc.sql("""
				UPDATE delivery.deliveries
				SET status = :status, current_assignment_id = :assignment, assigned_driver_id = :driver,
				    vehicle_id = :vehicle, pod_policy_version_id = :policyVersion,
				    pod_policy_snapshot = CAST(:policy AS jsonb), started_at = :started, completed_at = :completed,
				    updated_at = :now, version = version + 1
				WHERE id = :id AND version = :expected
				""")
				.param("status", d.status().name())
				.param("assignment", d.currentAssignmentId(), Types.OTHER)
				.param("driver", d.assignedDriverId(), Types.OTHER)
				.param("vehicle", d.vehicleId(), Types.OTHER)
				.param("policyVersion", d.podPolicyVersionId(), Types.OTHER)
				.param("policy", policy, Types.VARCHAR)
				.param("started", d.startedAt() == null ? null : utc(d.startedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("completed", d.completedAt() == null ? null : utc(d.completedAt()),
						Types.TIMESTAMP_WITH_TIMEZONE)
				.param("now", utc(now)).param("id", d.id()).param("expected", expectedVersion)
				.update() == 1;
	}

	@Override
	public List<UUID> openBlockingExceptionIds(UUID deliveryId) {
		return jdbc.sql("SELECT exception_id FROM delivery.delivery_blocking_refs WHERE delivery_id = :id AND cleared_at IS NULL ORDER BY created_at")
				.param("id", deliveryId).query((rs, i) -> rs.getObject(1, UUID.class)).list();
	}

	@Override
	public boolean hasOpenBlockingForStop(UUID stopId) {
		return jdbc.sql("SELECT count(*) FROM delivery.delivery_blocking_refs WHERE stop_id = :id AND cleared_at IS NULL")
				.param("id", stopId).query(Integer.class).single() > 0;
	}

	@Override
	public void insertBlockingRef(UUID exceptionId, UUID deliveryId, UUID stopId, String scope, Instant now) {
		jdbc.sql("""
				INSERT INTO delivery.delivery_blocking_refs (exception_id, delivery_id, stop_id, scope, created_at)
				VALUES (:exception, :delivery, :stop, :scope, :now)
				""")
				.param("exception", exceptionId).param("delivery", deliveryId).param("stop", stopId, Types.OTHER)
				.param("scope", scope).param("now", utc(now)).update();
	}

	@Override
	public Optional<UUID> clearBlockingRef(UUID exceptionId, Instant now) {
		return jdbc.sql("""
				UPDATE delivery.delivery_blocking_refs SET cleared_at = :now
				WHERE exception_id = :id AND cleared_at IS NULL RETURNING delivery_id
				""")
				.param("now", utc(now)).param("id", exceptionId).query((rs, i) -> rs.getObject(1, UUID.class)).optional();
	}

	@Override
	public void bumpVersion(UUID deliveryId, Instant now) {
		jdbc.sql("UPDATE delivery.deliveries SET version = version + 1, updated_at = :now WHERE id = :id")
				.param("now", utc(now)).param("id", deliveryId).update();
	}

	@Override
	public void updateStop(DeliveryStop s) {
		jdbc.sql("""
				UPDATE delivery.delivery_stops SET status = :status, arrived_at = :arrived, completed_at = :completed
				WHERE id = :id
				""")
				.param("status", s.status().name())
				.param("arrived", s.arrivedAt() == null ? null : utc(s.arrivedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("completed", s.completedAt() == null ? null : utc(s.completedAt()),
						Types.TIMESTAMP_WITH_TIMEZONE)
				.param("id", s.id())
				.update();
	}

	@Override
	public void insertStopTransition(UUID stopId, UUID deliveryId, String from, String to, String action,
			UUID actorId, Instant now) {
		jdbc.sql("""
				INSERT INTO delivery.stop_transitions
				  (id, stop_id, delivery_id, from_status, to_status, action, actor_id, occurred_at)
				VALUES (:id, :stop, :delivery, :from, :to, :action, :actor, :now)
				""")
				.param("id", UUID.randomUUID()).param("stop", stopId).param("delivery", deliveryId)
				.param("from", from).param("to", to).param("action", action).param("actor", actorId)
				.param("now", utc(now))
				.update();
	}

	@SuppressWarnings("unchecked")
	private Optional<Delivery> find(String sql, UUID id) {
		return jdbc.sql(sql).param("id", id).query((rs, i) -> {
			UUID deliveryId = rs.getObject("id", UUID.class);
			String policy = rs.getString("policy");
			OffsetDateTime started = rs.getObject("started_at", OffsetDateTime.class);
			OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
			return new Delivery(deliveryId, rs.getString("code"), rs.getObject("batch_id", UUID.class),
					rs.getObject("decision_id", UUID.class), DeliveryStatus.valueOf(rs.getString("status")),
					rs.getObject("current_assignment_id", UUID.class), rs.getObject("assigned_driver_id", UUID.class),
					rs.getObject("vehicle_id", UUID.class), rs.getObject("pod_policy_version_id", UUID.class),
					policy == null ? null : (Map<String, Object>) mapper.readValue(policy, Map.class),
					started == null ? null : started.toInstant(), completed == null ? null : completed.toInstant(),
					stops(deliveryId), rs.getLong("version"));
		}).optional();
	}

	private List<DeliveryStop> stops(UUID deliveryId) {
		return jdbc.sql("""
				SELECT id, delivery_id, order_id, sequence_no, address_text,
				       ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng,
				       window_start, window_end, status, arrived_at, completed_at
				FROM delivery.delivery_stops WHERE delivery_id = :id ORDER BY sequence_no
				""")
				.param("id", deliveryId)
				.query((rs, i) -> mapStop(rs)).list();
	}

	private static DeliveryStop mapStop(ResultSet rs) throws SQLException {
		OffsetDateTime arrived = rs.getObject("arrived_at", OffsetDateTime.class);
		OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
		return new DeliveryStop(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
				rs.getObject("order_id", UUID.class), rs.getInt("sequence_no"), rs.getString("address_text"),
				new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")),
				rs.getObject("window_start", OffsetDateTime.class).toInstant(),
				rs.getObject("window_end", OffsetDateTime.class).toInstant(),
				StopStatus.valueOf(rs.getString("status")),
				arrived == null ? null : arrived.toInstant(), completed == null ? null : completed.toInstant());
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
