package vn.edu.finalism.logistics.modules.audit.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditSanitizer;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.shared.ClockPort;

@Component
class JdbcAuditWriter implements AuditWriter {

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final ClockPort clock;

	JdbcAuditWriter(JdbcClient jdbc, ObjectMapper mapper, ClockPort clock) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void append(AuditEntry entry) {
		jdbc.sql("""
				INSERT INTO audit.audit_records
				  (id, occurred_at, actor_id, actor_type, action, entity_type, entity_id, zone_id,
				   before_state, after_state, reason, correlation_id, command_id)
				VALUES
				  (:id, :occurredAt, :actorId, :actorType, :action, :entityType, :entityId, :zoneId,
				   CAST(:before AS jsonb), CAST(:after AS jsonb), :reason, :correlationId, :commandId)
				""")
				.param("id", UUID.randomUUID())
				.param("occurredAt", OffsetDateTime.ofInstant(clock.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ZoneOffset.UTC))
				.param("actorId", entry.actorId(), java.sql.Types.OTHER)
				.param("actorType", entry.actorType().name())
				.param("action", entry.action().name())
				.param("entityType", entry.entityType())
				.param("entityId", entry.entityId())
				.param("zoneId", entry.zoneId(), java.sql.Types.OTHER)
				.param("before", toJson(AuditSanitizer.sanitize(entry.before())))
				.param("after", toJson(AuditSanitizer.sanitize(entry.after())))
				.param("reason", entry.reason())
				.param("correlationId", entry.correlationId())
				.param("commandId", entry.commandId(), java.sql.Types.OTHER)
				.update();
	}

	private String toJson(Map<String, Object> value) {
		return value == null ? null : mapper.writeValueAsString(value);
	}
}
