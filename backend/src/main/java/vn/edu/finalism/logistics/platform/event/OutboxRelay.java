package vn.edu.finalism.logistics.platform.event;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import vn.edu.finalism.logistics.shared.ClockPort;

@Component
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final JdbcClient jdbc;
	private final EventTransport transport;
	private final ObjectMapper mapper;
	private final ClockPort clock;
	private final int batchSize;

	public OutboxRelay(JdbcClient jdbc, EventTransport transport, ObjectMapper mapper, ClockPort clock,
			@Value("${app.events.relay.batch-size:50}") int batchSize) {
		this.jdbc = jdbc;
		this.transport = transport;
		this.mapper = mapper;
		this.clock = clock;
		this.batchSize = batchSize;
	}

	private record Pending(UUID id, UUID eventId, String eventType, int eventVersion,
			String aggregateType, UUID aggregateId, UUID actorId, String correlationId,
			Instant occurredAt, String payload, int attempts) {
	}

	@Transactional
	public int dispatchBatch() {
		Instant now = clock.now();
		List<Pending> due = jdbc.sql("""
				SELECT id, event_id, event_type, event_version, aggregate_type, aggregate_id, actor_id,
				       correlation_id, occurred_at, safe_payload::text AS payload, attempts
				FROM platform.event_publications
				WHERE status IN ('PENDING','FAILED')
				  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
				ORDER BY occurred_at, id
				LIMIT :limit
				FOR UPDATE SKIP LOCKED
				""")
				.param("now", utc(now))
				.param("limit", batchSize)
				.query((rs, i) -> new Pending(
						rs.getObject("id", UUID.class),
						rs.getObject("event_id", UUID.class),
						rs.getString("event_type"),
						rs.getInt("event_version"),
						rs.getString("aggregate_type"),
						rs.getObject("aggregate_id", UUID.class),
						rs.getObject("actor_id", UUID.class),
						rs.getString("correlation_id"),
						rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
						rs.getString("payload"),
						rs.getInt("attempts")))
				.list();

		int sent = 0;
		for (Pending event : due) {
			try {
				transport.send(toOutbound(event));
				markDispatched(event, now);
				sent++;
			}
			catch (RuntimeException e) {
				markFailed(event, now, e);
				if (e instanceof AmqpException) {
					break;
				}
			}
		}
		return sent;
	}

	private OutboundEvent toOutbound(Pending event) {
		ObjectNode node = mapper.createObjectNode();
		node.put("eventId", event.eventId().toString());
		node.put("eventType", event.eventType());
		node.put("eventVersion", event.eventVersion());
		node.put("aggregateType", event.aggregateType());
		node.put("aggregateId", event.aggregateId().toString());
		node.put("occurredAt", event.occurredAt().toString());
		node.put("actorId", event.actorId() == null ? null : event.actorId().toString());
		node.put("correlationId", event.correlationId());
		node.set("payload", mapper.readTree(event.payload()));
		return new OutboundEvent(event.eventId(), event.eventType(), event.correlationId(),
				mapper.writeValueAsBytes(node));
	}

	private void markDispatched(Pending event, Instant now) {
		jdbc.sql("""
				UPDATE platform.event_publications
				SET status = 'DISPATCHED', dispatched_at = :now, attempts = attempts + 1,
				    next_attempt_at = NULL, last_error_code = NULL
				WHERE id = :id
				""")
				.param("now", utc(now))
				.param("id", event.id())
				.update();
	}

	private void markFailed(Pending event, Instant now, RuntimeException error) {
		log.warn("Could not publish event {} ({}): {}", event.eventId(), event.eventType(),
				error.getClass().getSimpleName());
		int attempts = event.attempts() + 1;
		jdbc.sql("""
				UPDATE platform.event_publications
				SET status = 'FAILED', attempts = :attempts, next_attempt_at = :next, last_error_code = :code
				WHERE id = :id
				""")
				.param("attempts", attempts)
				.param("next", utc(now.plus(RelayBackoff.delayAfter(attempts))))
				.param("code", error instanceof AmqpException ? "BROKER_UNAVAILABLE" : "PUBLISH_FAILED")
				.param("id", event.id())
				.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
