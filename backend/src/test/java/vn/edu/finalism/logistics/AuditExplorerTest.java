package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditActorType;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class AuditExplorerTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	AuditWriter writer;
	@Autowired
	PlatformTransactionManager transactionManager;

	TestApi api;

	@BeforeEach
	void setUp() {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		api = new TestApi(mvc);
	}

	private void write(AuditAction action, UUID actor, UUID entity, UUID zone, String correlation,
			Map<String, Object> after) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> writer.append(new AuditEntry(action,
				AuditActorType.USER, actor, "TEST_ENTITY", entity, zone, null, after, null, correlation, null)));
	}

	private String range() {
		Instant now = Instant.now();
		return "from=" + now.minus(Duration.ofHours(1)) + "&to=" + now.plus(Duration.ofHours(1));
	}

	@Test
	void opsSeesOnlyItsOwnZonesNewestFirstAndCanFilterAndPage() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		UUID foreignZone = UUID.randomUUID();
		String correlation = "audit-" + UUID.randomUUID();
		UUID entity = UUID.randomUUID();
		write(AuditAction.ORDER_CREATED, w.customer.id(), entity, w.zoneId, correlation, Map.of("status", "A"));
		Thread.sleep(5);
		write(AuditAction.BATCH_LOCKED, w.dispatcher.id(), UUID.randomUUID(), w.zoneId, "other-" + correlation,
				Map.of("status", "B"));
		Thread.sleep(5);
		write(AuditAction.DRIVER_ASSIGNED, w.dispatcher.id(), UUID.randomUUID(), w.zoneId, "third-" + correlation,
				Map.of("status", "C"));
		write(AuditAction.ORDER_CREATED, w.customer.id(), UUID.randomUUID(), foreignZone, correlation,
				Map.of("status", "foreign"));
		write(AuditAction.ROLE_CHANGED, w.admin.id(), UUID.randomUUID(), null, correlation, Map.of("status", "admin"));
		String ops = api.login(w.ops);

		String all = TestApi.bodyOf(api.get(ops, "/api/v1/audit-records?" + range()).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.items.length()").value(3)));
		List<String> actions = JsonPath.read(all, "$.items[*].action");
		assertThat(actions).containsExactly("DRIVER_ASSIGNED", "BATCH_LOCKED", "ORDER_CREATED");

		api.get(ops, "/api/v1/audit-records?" + range() + "&action=BATCH_LOCKED").andExpect(jsonPath("$.items.length()").value(1));
		api.get(ops, "/api/v1/audit-records?" + range() + "&correlationId=" + correlation)
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].entityId").value(entity.toString()));
		api.get(ops, "/api/v1/audit-records?" + range() + "&actorId=" + w.dispatcher.id())
				.andExpect(jsonPath("$.items.length()").value(2));
		api.get(ops, "/api/v1/audit-records?" + range() + "&entityType=TEST_ENTITY&entityId=" + entity)
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].actor.name").exists());

		String first = TestApi.bodyOf(api.get(ops, "/api/v1/audit-records?" + range() + "&limit=2")
				.andExpect(jsonPath("$.items.length()").value(2)));
		String cursor = JsonPath.read(first, "$.nextCursor");
		String second = TestApi.bodyOf(api.get(ops, "/api/v1/audit-records?" + range() + "&limit=2&cursor=" + cursor)
				.andExpect(jsonPath("$.items.length()").value(1)));
		List<String> ids = new ArrayList<>(JsonPath.<List<String>>read(first, "$.items[*].id"));
		ids.addAll(JsonPath.<List<String>>read(second, "$.items[*].id"));
		assertThat(ids).doesNotHaveDuplicates().hasSize(3);
		assertThat(JsonPath.<Object>read(second, "$.nextCursor")).isNull();
	}

	@Test
	void administrativeRecordsWithoutAZoneAreForTheSystemAdminOnly() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		UUID entity = UUID.randomUUID();
		write(AuditAction.ROLE_CHANGED, w.admin.id(), entity, null, "admin-" + UUID.randomUUID(), Map.of("role", "X"));
		String admin = api.login(w.admin);
		String ops = api.login(w.ops);
		String path = "/api/v1/audit-records?" + range() + "&entityId=" + entity;

		String found = TestApi.bodyOf(api.get(admin, path).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)));
		api.get(ops, path).andExpect(jsonPath("$.items.length()").value(0));

		String id = JsonPath.read(found, "$.items[0].id");
		api.get(admin, "/api/v1/audit-records/" + id).andExpect(status().isOk())
				.andExpect(jsonPath("$.after.role").value("X"));
		api.get(ops, "/api/v1/audit-records/" + id).andExpect(status().isNotFound());
	}

	@Test
	void theSearchRequiresAValidTimeRangeAndThePermission() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		Instant now = Instant.now();

		api.get(ops, "/api/v1/audit-records").andExpect(status().isBadRequest());
		api.get(ops, "/api/v1/audit-records?from=" + now).andExpect(status().isBadRequest());
		api.get(ops, "/api/v1/audit-records?from=" + now + "&to=" + now.minusSeconds(5))
				.andExpect(status().isBadRequest());
		api.get(ops, "/api/v1/audit-records?from=" + now.minus(Duration.ofDays(200)) + "&to=" + now)
				.andExpect(status().isBadRequest());
		api.get(api.login(w.dispatcher), "/api/v1/audit-records?" + range()).andExpect(status().isForbidden());
		api.get(api.login(w.customer), "/api/v1/audit-records?" + range()).andExpect(status().isForbidden());
		api.get(api.login(w.dispatcher), "/api/v1/audit-records/" + UUID.randomUUID()).andExpect(status().isForbidden());
	}

	@Test
	void secretsAreRedactedAndThereIsNoWayToChangeAuditThroughTheApi() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		UUID entity = UUID.randomUUID();
		write(AuditAction.ORDER_CREATED, w.customer.id(), entity, w.zoneId, "secret-" + UUID.randomUUID(),
				Map.of("password", "hunter2-plain", "token", "tok-abc", "status", "ok"));
		String ops = api.login(w.ops);

		String body = TestApi.bodyOf(api.get(ops, "/api/v1/audit-records?" + range() + "&entityId=" + entity)
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].after.status").value("ok")));
		assertThat(body).doesNotContain("hunter2-plain").doesNotContain("tok-abc");

		api.post(ops, "/api/v1/audit-records", "{}").andExpect(status().is4xxClientError());
		String id = JsonPath.read(body, "$.items[0].id");
		api.post(ops, "/api/v1/audit-records/" + id, "{}").andExpect(status().is4xxClientError());
	}
}
