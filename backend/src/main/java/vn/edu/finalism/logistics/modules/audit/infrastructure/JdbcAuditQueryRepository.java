package vn.edu.finalism.logistics.modules.audit.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.audit.application.port.AuditQueryRepository;

@Repository
class JdbcAuditQueryRepository implements AuditQueryRepository {

	private static final UUID NIL = new UUID(0, 0);

	private static final String SELECT = """
			SELECT a.id, a.occurred_at, a.actor_id, u.display_name AS actor_name, a.actor_type, a.action,
			       a.entity_type, a.entity_id, a.zone_id, a.before_state::text AS before_state,
			       a.after_state::text AS after_state, a.reason, a.correlation_id
			FROM audit.audit_records a
			LEFT JOIN iam.users u ON u.id = a.actor_id
			""";

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	JdbcAuditQueryRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	public List<AuditRecordView> search(AuditFilter f, int limit) {
		StringBuilder sql = new StringBuilder(SELECT)
				.append(" WHERE a.occurred_at >= :from AND a.occurred_at < :to")
				.append(" AND (a.zone_id IN (:zones)")
				.append(f.includeNullZone() ? " OR a.zone_id IS NULL)" : ")");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("from", utc(f.from()));
		params.put("to", utc(f.to()));
		params.put("zones", f.zoneIds().isEmpty() ? List.of(NIL) : List.copyOf(f.zoneIds()));
		if (f.actorId() != null) {
			sql.append(" AND a.actor_id = :actor");
			params.put("actor", f.actorId());
		}
		if (f.action() != null) {
			sql.append(" AND a.action = :action");
			params.put("action", f.action());
		}
		if (f.entityType() != null) {
			sql.append(" AND a.entity_type = :entityType");
			params.put("entityType", f.entityType());
		}
		if (f.entityId() != null) {
			sql.append(" AND a.entity_id = :entityId");
			params.put("entityId", f.entityId());
		}
		if (f.correlationId() != null) {
			sql.append(" AND a.correlation_id = :correlation");
			params.put("correlation", f.correlationId());
		}
		if (f.cursor() != null) {
			sql.append(" AND (a.occurred_at, a.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(f.cursor().sortKey()));
			params.put("cursorId", f.cursor().id().toString());
		}
		sql.append(" ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit");
		params.put("limit", limit);
		JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query((rs, i) -> map(rs)).list();
	}

	@Override
	public Optional<AuditRecordView> findById(UUID id) {
		return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query((rs, i) -> map(rs)).optional();
	}

	@SuppressWarnings("unchecked")
	private AuditRecordView map(ResultSet rs) throws SQLException {
		String before = rs.getString("before_state");
		String after = rs.getString("after_state");
		return new AuditRecordView(rs.getObject("id", UUID.class),
				rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getObject("actor_id", UUID.class),
				rs.getString("actor_name"), rs.getString("actor_type"), rs.getString("action"),
				rs.getString("entity_type"), rs.getObject("entity_id", UUID.class), rs.getObject("zone_id", UUID.class),
				before == null ? null : mapper.readValue(before, Map.class),
				after == null ? null : mapper.readValue(after, Map.class), rs.getString("reason"),
				rs.getString("correlation_id"));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
