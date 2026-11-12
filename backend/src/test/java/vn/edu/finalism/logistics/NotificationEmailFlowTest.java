package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import vn.edu.finalism.logistics.TestWorld.TestUser;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;
import vn.edu.finalism.logistics.modules.notification.application.EmailDispatcher;
import vn.edu.finalism.logistics.modules.notification.application.NotificationService;
import vn.edu.finalism.logistics.modules.notification.application.NotificationService.IncomingEvent;
import vn.edu.finalism.logistics.modules.notification.application.port.EmailSenderPort;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class NotificationEmailFlowTest {

	static class FakeEmailSender implements EmailSenderPort {

		final List<String> recipients = new CopyOnWriteArrayList<>();
		volatile String failWith;

		@Override
		public void send(String to, String subject, String body) throws EmailDeliveryException {
			if (failWith != null) {
				throw new EmailDeliveryException(failWith);
			}
			recipients.add(to);
		}
	}

	@TestConfiguration
	static class Config {

		@Bean
		@Primary
		FakeEmailSender fakeEmailSender() {
			return new FakeEmailSender();
		}
	}

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	NotificationService service;
	@Autowired
	EmailDispatcher dispatcher;
	@Autowired
	FakeEmailSender sender;

	TestApi api;

	@BeforeEach
	void setUp() {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		api = new TestApi(mvc);
		sender.failWith = null;
		sender.recipients.clear();
		jdbc.sql("UPDATE notif.notifications SET status = 'SENT', sent_at = now(), next_attempt_at = NULL WHERE channel = 'EMAIL' AND status IN ('PENDING','FAILED')")
				.update();
	}

	private TestUser newUser() {
		return TestWorld.create(jdbc, encoder, true).customer;
	}

	private int notify(String eventType, TestUser user, UUID eventId) {
		return service.onEvent(new IncomingEvent(eventId, eventType,
				Map.of("batchCode", "B-1", "recipientUserIds", List.of(user.id().toString()))));
	}

	private int count(TestUser user, String channel) {
		return jdbc.sql("SELECT count(*) FROM notif.notifications WHERE recipient_user_id = :id AND channel = :channel")
				.param("id", user.id()).param("channel", channel).query(Integer.class).single();
	}

	private Map<String, Object> emailRow(TestUser user) {
		return jdbc.sql("SELECT status, attempts, next_attempt_at, last_error_code, sent_at FROM notif.notifications WHERE recipient_user_id = :id AND channel = 'EMAIL'")
				.param("id", user.id()).query().singleRow();
	}

	private void makeDue(TestUser user) {
		jdbc.sql("UPDATE notif.notifications SET next_attempt_at = now() - interval '1 minute' WHERE recipient_user_id = :id AND channel = 'EMAIL' AND next_attempt_at IS NOT NULL")
				.param("id", user.id()).update();
	}

	@Test
	void anEventCreatesTheInAppRecordAndOnlyEmailsWhenThePolicySaysSo() {
		TestUser emailed = newUser();
		TestUser inAppOnly = newUser();

		notify("DriverAssigned", emailed, UUID.randomUUID());
		notify("DriverAssignmentRejected", inAppOnly, UUID.randomUUID());

		assertThat(count(emailed, "IN_APP")).isEqualTo(1);
		assertThat(count(emailed, "EMAIL")).isEqualTo(1);
		assertThat(emailRow(emailed).get("status")).isEqualTo("PENDING");
		assertThat(count(inAppOnly, "IN_APP")).isEqualTo(1);
		assertThat(count(inAppOnly, "EMAIL")).isZero();
	}

	@Test
	void aSuccessfulSendMarksTheEmailSent() {
		TestUser user = newUser();
		notify("DriverAssigned", user, UUID.randomUUID());

		dispatcher.dispatchDue();

		Map<String, Object> row = emailRow(user);
		assertThat(row.get("status")).isEqualTo("SENT");
		assertThat(row.get("attempts")).isEqualTo(1);
		assertThat(row.get("sent_at")).isNotNull();
		assertThat(row.get("next_attempt_at")).isNull();
		String address = jdbc.sql("SELECT email FROM iam.users WHERE id = :id").param("id", user.id())
				.query(String.class).single();
		assertThat(sender.recipients).contains(address);
	}

	@Test
	void aFailureIsRecordedSafelyAndRetriedLaterWithoutBreakingAnything() {
		TestUser user = newUser();
		notify("DriverAssigned", user, UUID.randomUUID());
		sender.failWith = "SMTP_UNAVAILABLE";

		dispatcher.dispatchDue();

		Map<String, Object> failed = emailRow(user);
		assertThat(failed.get("status")).isEqualTo("FAILED");
		assertThat(failed.get("attempts")).isEqualTo(1);
		assertThat(failed.get("last_error_code")).isEqualTo("SMTP_UNAVAILABLE");
		assertThat(failed.get("next_attempt_at")).isNotNull();
		assertThat(count(user, "IN_APP")).isEqualTo(1);

		dispatcher.dispatchDue();
		assertThat(emailRow(user).get("attempts")).isEqualTo(1);

		sender.failWith = null;
		makeDue(user);
		dispatcher.dispatchDue();

		Map<String, Object> sent = emailRow(user);
		assertThat(sent.get("status")).isEqualTo("SENT");
		assertThat(sent.get("attempts")).isEqualTo(2);
		assertThat(sent.get("last_error_code")).isNull();
	}

	@Test
	void afterFiveFailuresTheEmailGivesUpForGood() {
		TestUser user = newUser();
		notify("DriverAssigned", user, UUID.randomUUID());
		sender.failWith = "SMTP_UNAVAILABLE";

		for (int i = 0; i < 5; i++) {
			makeDue(user);
			dispatcher.dispatchDue();
		}

		Map<String, Object> row = emailRow(user);
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("attempts")).isEqualTo(5);
		assertThat(row.get("next_attempt_at")).isNull();

		sender.failWith = null;
		dispatcher.dispatchDue();
		assertThat(emailRow(user).get("status")).isEqualTo("FAILED");
		assertThat(emailRow(user).get("attempts")).isEqualTo(5);
	}

	@Test
	void theSameEventIsNeverNotifiedTwice() {
		TestUser user = newUser();
		UUID eventId = UUID.randomUUID();

		assertThat(notify("DriverAssigned", user, eventId)).isEqualTo(1);
		assertThat(notify("DriverAssigned", user, eventId)).isZero();

		assertThat(count(user, "IN_APP")).isEqualTo(1);
		assertThat(count(user, "EMAIL")).isEqualTo(1);
	}

	@Test
	void theInboxSupportsMarkingOneAndAllAsReadIdempotently() throws Exception {
		TestUser user = newUser();
		TestUser other = newUser();
		notify("DriverAssigned", user, UUID.randomUUID());
		notify("DeliveryStarted", user, UUID.randomUUID());
		notify("DriverAssigned", other, UUID.randomUUID());
		String token = api.login(user);

		api.get(token, "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(2));
		String first = jdbc.sql("SELECT id::text FROM notif.notifications WHERE recipient_user_id = :id AND channel = 'IN_APP' ORDER BY id LIMIT 1")
				.param("id", user.id()).query(String.class).single();
		String foreign = jdbc.sql("SELECT id::text FROM notif.notifications WHERE recipient_user_id = :id AND channel = 'IN_APP'")
				.param("id", other.id()).query(String.class).single();

		api.post(token, "/api/v1/notifications/" + first + "/read", "").andExpect(status().isNoContent());
		api.post(token, "/api/v1/notifications/" + first + "/read", "").andExpect(status().isNoContent());
		api.get(token, "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(1));
		api.post(token, "/api/v1/notifications/" + foreign + "/read", "").andExpect(status().isNotFound());

		api.post(token, "/api/v1/notifications/read-all", "").andExpect(status().isNoContent());
		api.post(token, "/api/v1/notifications/read-all", "").andExpect(status().isNoContent());
		api.get(token, "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(0));
		assertThat(jdbc.sql("SELECT count(*) FROM notif.notifications WHERE recipient_user_id = :id AND channel = 'IN_APP' AND status <> 'READ'")
				.param("id", other.id()).query(Integer.class).single()).isEqualTo(1);
	}
}
