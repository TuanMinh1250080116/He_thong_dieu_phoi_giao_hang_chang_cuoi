package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.platform.event.EventHandlerRuns;
import vn.edu.finalism.logistics.platform.event.OutboxRelay;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class OutboxRelayTest {

	@Autowired
	EventPublicationPort publicationPort;
	@Autowired
	OutboxRelay relay;
	@Autowired
	EventHandlerRuns handlerRuns;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	ObjectMapper mapper;
	@Autowired
	ClockPort clock;
	@Autowired
	RabbitAdmin rabbitAdmin;
	@Autowired
	RabbitTemplate rabbitTemplate;
	@Autowired
	TopicExchange eventsExchange;

	Queue testQueue;

	@BeforeEach
	void bindTestQueue() {
		jdbc.sql("UPDATE platform.event_publications SET status = 'DISPATCHED', dispatched_at = now() WHERE status IN ('PENDING','FAILED')")
				.update();
		testQueue = new Queue("test.events." + UUID.randomUUID(), false, true, true);
		rabbitAdmin.declareQueue(testQueue);
		rabbitAdmin.declareBinding(BindingBuilder.bind(testQueue).to(eventsExchange).with("#"));
	}

	private EventEnvelope event(UUID eventId, String correlationId) {
		return new EventEnvelope(eventId, "OrderCreated", 1, "Order", UUID.randomUUID(), clock.now(),
				UUID.randomUUID(), correlationId, Map.of("orderCode", "ORD-1", "password", "plain-secret"));
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
	}

	private String statusOf(UUID eventId) {
		return jdbc.sql("SELECT status FROM platform.event_publications WHERE event_id = :id")
				.param("id", eventId).query(String.class).single();
	}

	private int attemptsOf(UUID eventId) {
		return jdbc.sql("SELECT attempts FROM platform.event_publications WHERE event_id = :id")
				.param("id", eventId).query(Integer.class).single();
	}

	@Test
	void appendRequiresATransaction() {
		assertThatThrownBy(() -> publicationPort.append(event(UUID.randomUUID(), "corr-1")))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void appendRollsBackTogetherWithTheBusinessTransaction() {
		UUID eventId = UUID.randomUUID();

		assertThatThrownBy(() -> inTransaction(() -> {
			publicationPort.append(event(eventId, "corr-1"));
			throw new IllegalStateException("business step failed");
		})).isInstanceOf(IllegalStateException.class);

		Integer rows = jdbc.sql("SELECT count(*) FROM platform.event_publications WHERE event_id = :id")
				.param("id", eventId).query(Integer.class).single();
		assertThat(rows).isZero();
	}

	@Test
	void relayPublishesPendingEventToTheBrokerAndMarksItDispatched() throws Exception {
		UUID eventId = UUID.randomUUID();
		inTransaction(() -> publicationPort.append(event(eventId, "corr-relay")));
		assertThat(statusOf(eventId)).isEqualTo("PENDING");

		relay.dispatchBatch();

		assertThat(statusOf(eventId)).isEqualTo("DISPATCHED");
		Message message = rabbitTemplate.receive(testQueue.getName(), 5000);
		assertThat(message).isNotNull();
		JsonNode body = mapper.readTree(new String(message.getBody()));
		assertThat(body.get("eventId").asString()).isEqualTo(eventId.toString());
		assertThat(body.get("eventType").asString()).isEqualTo("OrderCreated");
		assertThat(body.get("correlationId").asString()).isEqualTo("corr-relay");
		assertThat(body.get("payload").get("orderCode").asString()).isEqualTo("ORD-1");
		assertThat(new String(message.getBody())).doesNotContain("plain-secret");
		assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
	}

	@Test
	void failedPublishIsMarkedFailedAndRetriedLater() {
		UUID eventId = UUID.randomUUID();
		inTransaction(() -> publicationPort.append(event(eventId, "corr-fail")));
		OutboxRelay failingRelay = new OutboxRelay(jdbc, outbound -> {
			throw new AmqpConnectException(new RuntimeException("broker down"));
		}, mapper, clock, 50);

		failingRelay.dispatchBatch();

		assertThat(statusOf(eventId)).isEqualTo("FAILED");
		assertThat(attemptsOf(eventId)).isEqualTo(1);
		Instant nextAttempt = jdbc.sql("SELECT next_attempt_at FROM platform.event_publications WHERE event_id = :id")
				.param("id", eventId).query((rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant()).single();
		assertThat(nextAttempt).isAfter(clock.now());

		relay.dispatchBatch();
		assertThat(statusOf(eventId)).isEqualTo("FAILED");

		jdbc.sql("UPDATE platform.event_publications SET next_attempt_at = :past WHERE event_id = :id")
				.param("past", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
				.param("id", eventId).update();
		relay.dispatchBatch();
		assertThat(statusOf(eventId)).isEqualTo("DISPATCHED");
		assertThat(attemptsOf(eventId)).isEqualTo(2);
	}

	@Test
	void handlerClaimWorksOncePerEventAndHandler() {
		UUID eventId = UUID.randomUUID();
		boolean[] results = new boolean[3];

		inTransaction(() -> {
			results[0] = handlerRuns.claim(eventId, "notification");
			results[1] = handlerRuns.claim(eventId, "notification");
			results[2] = handlerRuns.claim(eventId, "tracking");
		});

		assertThat(results).containsExactly(true, false, true);
	}
}
