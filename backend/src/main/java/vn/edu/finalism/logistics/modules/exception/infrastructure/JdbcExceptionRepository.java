package vn.edu.finalism.logistics.modules.exception.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository;
import vn.edu.finalism.logistics.modules.exception.domain.DeliveryException;
import vn.edu.finalism.logistics.modules.exception.domain.ExceptionStatus;

@Repository
class JdbcExceptionRepository implements ExceptionRepository {

	private static final UUID NIL = new UUID(0, 0);

	private static final String COLUMNS = """
			e.id, e.code, e.delivery_id, e.stop_id, e.order_id, e.exception_type, e.severity, e.impact, e.status,
			e.description, e.reporter_user_id, e.assignee_user_id, e.reported_at, e.investigating_at, e.closed_at,
			e.closed_by, e.resolution_outcome, e.resolution_note, e.cancellation_reason, e.version
			""";

	private static final String VIEW = "SELECT " + COLUMNS + """
			, b.zone_id, dl.code AS delivery_code, dl.assigned_driver_id, t.name AS type_name,
			  ru.display_name AS reporter_name, au.display_name AS assignee_name, s.status AS stop_status
			FROM exc.delivery_exceptions e
			JOIN delivery.deliveries dl ON dl.id = e.delivery_id
			JOIN dispatch.delivery_batches b ON b.id = dl.batch_id
			JOIN admin_cfg.exception_type_catalog t ON t.code = e.exception_type
			JOIN iam.users ru ON ru.id = e.reporter_user_id
			LEFT JOIN iam.users au ON au.id = e.assignee_user_id
			LEFT JOIN delivery.delivery_stops s ON s.id = e.stop_id
			""";

	private final JdbcClient jdbc;

	JdbcExceptionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public String nextCode() {
		Long next = jdbc.sql("SELECT nextval('exc.exception_code_seq')").query(Long.class).single();
		return "EXC-" + String.format("%06d", next);
	}

	@Override
	public void insert(DeliveryException e, Instant now) {
		jdbc.sql("""
				INSERT INTO exc.delivery_exceptions
				  (id, code, delivery_id, stop_id, order_id, exception_type, severity, impact, status, description,
				   reporter_user_id, reported_at, created_at, updated_at, version)
				VALUES (:id, :code, :delivery, :stop, :order, :type, :severity, :impact, 'OPEN', :description,
				        :reporter, :reported, :now, :now, 0)
				""")
				.param("id", e.id()).param("code", e.code()).param("delivery", e.deliveryId())
				.param("stop", e.stopId(), Types.OTHER).param("order", e.orderId(), Types.OTHER)
				.param("type", e.typeCode()).param("severity", e.severity()).param("impact", e.impact())
				.param("description", e.description()).param("reporter", e.reporterUserId())
				.param("reported", utc(e.reportedAt())).param("now", utc(now)).update();
	}

	@Override
	public Optional<DeliveryException> findByIdForUpdate(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM exc.delivery_exceptions e WHERE e.id = :id FOR UPDATE")
				.param("id", id).query((rs, i) -> map(rs)).optional();
	}

	@Override
	public boolean update(DeliveryException e, long expectedVersion, Instant now) {
		return jdbc.sql("""
				UPDATE exc.delivery_exceptions
				SET status = :status, assignee_user_id = :assignee, investigating_at = :investigating,
				    closed_at = :closedAt, closed_by = :closedBy, resolution_outcome = :outcome,
				    resolution_note = :note, cancellation_reason = :cancellation, updated_at = :now,
				    version = version + 1
				WHERE id = :id AND version = :expected
				""")
				.param("status", e.status().name()).param("assignee", e.assigneeUserId(), Types.OTHER)
				.param("investigating", ts(e.investigatingAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("closedAt", ts(e.closedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("closedBy", e.closedBy(), Types.OTHER)
				.param("outcome", e.resolutionOutcome(), Types.VARCHAR)
				.param("note", e.resolutionNote(), Types.VARCHAR)
				.param("cancellation", e.cancellationReason(), Types.VARCHAR)
				.param("now", utc(now)).param("id", e.id()).param("expected", expectedVersion).update() == 1;
	}

	@Override
	public void insertNote(UUID exceptionId, UUID authorId, String noteType, String body, Instant now) {
		jdbc.sql("""
				INSERT INTO exc.exception_notes (id, exception_id, author_id, note_type, body, created_at)
				VALUES (:id, :exception, :author, :type, :body, :now)
				""")
				.param("id", UUID.randomUUID()).param("exception", exceptionId).param("author", authorId)
				.param("type", noteType).param("body", body).param("now", utc(now)).update();
	}

	@Override
	public void insertEvidence(UUID exceptionId, UUID evidenceId) {
		jdbc.sql("INSERT INTO exc.exception_evidence (exception_id, evidence_object_id) VALUES (:exception, :evidence)")
				.param("exception", exceptionId).param("evidence", evidenceId).update();
	}

	@Override
	public Optional<ExceptionView> findView(UUID id) {
		return jdbc.sql(VIEW + " WHERE e.id = :id").param("id", id).query((rs, i) -> mapView(rs)).optional();
	}

	@Override
	public List<ExceptionView> queue(QueueFilter f, int limit) {
		StringBuilder sql = new StringBuilder(VIEW).append(" WHERE (b.zone_id IN (:zones) OR dl.assigned_driver_id = :driver)");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("zones", f.zoneIds().isEmpty() ? List.of(NIL) : List.copyOf(f.zoneIds()));
		params.put("driver", f.driverId() == null ? NIL : f.driverId());
		if (f.status() != null) {
			sql.append(" AND e.status = :status");
			params.put("status", f.status());
		}
		if (f.severity() != null) {
			sql.append(" AND e.severity = :severity");
			params.put("severity", f.severity());
		}
		if (f.type() != null) {
			sql.append(" AND e.exception_type = :type");
			params.put("type", f.type());
		}
		if (f.assigneeId() != null) {
			sql.append(" AND e.assignee_user_id = :assignee");
			params.put("assignee", f.assigneeId());
		}
		if (f.deliveryId() != null) {
			sql.append(" AND e.delivery_id = :delivery");
			params.put("delivery", f.deliveryId());
		}
		if (f.blocking() != null) {
			sql.append(f.blocking() ? " AND e.impact IN ('BLOCKS_STOP','BLOCKS_DELIVERY_COMPLETION')"
					: " AND e.impact NOT IN ('BLOCKS_STOP','BLOCKS_DELIVERY_COMPLETION')");
		}
		if (f.from() != null) {
			sql.append(" AND e.reported_at >= :from");
			params.put("from", utc(f.from()));
		}
		if (f.to() != null) {
			sql.append(" AND e.reported_at < :to");
			params.put("to", utc(f.to()));
		}
		if (f.cursor() != null) {
			sql.append(" AND (e.reported_at, e.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(f.cursor().sortKey()));
			params.put("cursorId", f.cursor().id().toString());
		}
		sql.append(" ORDER BY e.reported_at DESC, e.id DESC LIMIT :limit");
		params.put("limit", limit);
		JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query((rs, i) -> mapView(rs)).list();
	}

	@Override
	public List<NoteView> notes(UUID exceptionId) {
		return jdbc.sql("""
				SELECT n.id, n.author_id, u.display_name, n.note_type, n.body, n.created_at
				FROM exc.exception_notes n JOIN iam.users u ON u.id = n.author_id
				WHERE n.exception_id = :id ORDER BY n.created_at, n.id
				""")
				.param("id", exceptionId)
				.query((rs, i) -> new NoteView(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
						rs.getString("display_name"), rs.getString("note_type"), rs.getString("body"),
						rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	@Override
	public List<UUID> evidenceIds(UUID exceptionId) {
		return jdbc.sql("SELECT evidence_object_id FROM exc.exception_evidence WHERE exception_id = :id ORDER BY evidence_object_id")
				.param("id", exceptionId).query((rs, i) -> rs.getObject(1, UUID.class)).list();
	}

	@Override
	public Optional<ExceptionType> findActiveType(String code) {
		return jdbc.sql("SELECT code, name, default_severity, impact FROM admin_cfg.exception_type_catalog WHERE code = :code AND is_active")
				.param("code", code).query((rs, i) -> mapType(rs)).optional();
	}

	@Override
	public List<ExceptionType> activeTypes() {
		return new ArrayList<>(jdbc.sql("SELECT code, name, default_severity, impact FROM admin_cfg.exception_type_catalog WHERE is_active ORDER BY code")
				.query((rs, i) -> mapType(rs)).list());
	}

	private static ExceptionType mapType(ResultSet rs) throws SQLException {
		return new ExceptionType(rs.getString("code"), rs.getString("name"), rs.getString("default_severity"),
				rs.getString("impact"));
	}

	private static ExceptionView mapView(ResultSet rs) throws SQLException {
		return new ExceptionView(map(rs), rs.getObject("zone_id", UUID.class), rs.getString("delivery_code"),
				rs.getObject("assigned_driver_id", UUID.class), rs.getString("type_name"),
				rs.getString("reporter_name"), rs.getString("assignee_name"), rs.getString("stop_status"));
	}

	private static DeliveryException map(ResultSet rs) throws SQLException {
		return new DeliveryException(rs.getObject("id", UUID.class), rs.getString("code"),
				rs.getObject("delivery_id", UUID.class), rs.getObject("stop_id", UUID.class),
				rs.getObject("order_id", UUID.class), rs.getString("exception_type"), rs.getString("severity"),
				rs.getString("impact"), ExceptionStatus.valueOf(rs.getString("status")), rs.getString("description"),
				rs.getObject("reporter_user_id", UUID.class), rs.getObject("assignee_user_id", UUID.class),
				instant(rs, "reported_at"), instant(rs, "investigating_at"), instant(rs, "closed_at"),
				rs.getObject("closed_by", UUID.class), rs.getString("resolution_outcome"),
				rs.getString("resolution_note"), rs.getString("cancellation_reason"), rs.getLong("version"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime ts(Instant instant) {
		return instant == null ? null : utc(instant);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
