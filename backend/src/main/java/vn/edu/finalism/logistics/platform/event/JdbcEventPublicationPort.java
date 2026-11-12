package vn.edu.finalism.logistics.platform.event;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.audit.application.AuditSanitizer;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Component
class JdbcEventPublicationPort implements EventPublicationPort {

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	JdbcEventPublicationPort(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void append(EventEnvelope envelope) {
		String payload = mapper.writeValueAsString(AuditSanitizer.sanitize(envelope.safePayload()));
		jdbc.sql("""
				INSERT INTO platform.event_publications
				  (id, event_id, event_type, event_version, aggregate_type, aggregate_id, actor_id,
				   correlation_id, occurred_at, safe_payload, status, attempts)
				VALUES
				  (:id, :eventId, :eventType, :eventVersion, :aggregateType, :aggregateId, :actorId,
				   :correlationId, :occurredAt, CAST(:payload AS jsonb), 'PENDING', 0)
				""")
				.param("id", UUID.randomUUID())
				.param("eventId", envelope.eventId())
				.param("eventType", envelope.eventType())
				.param("eventVersion", envelope.eventVersion())
				.param("aggregateType", envelope.aggregateType())
				.param("aggregateId", envelope.aggregateId())
				.param("actorId", envelope.actorId(), java.sql.Types.OTHER)
				.param("correlationId", envelope.correlationId())
				.param("occurredAt", OffsetDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC))
				.param("payload", payload)
				.update();
	}
}
