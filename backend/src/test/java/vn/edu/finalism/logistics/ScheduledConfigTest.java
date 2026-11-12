package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
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
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;
import vn.edu.finalism.logistics.modules.administration.application.ConfigVersionPromotion;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class ScheduledConfigTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	ConfigVersionPromotion promotion;

	TestApi api;

	@BeforeEach
	void setUp() {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		api = new TestApi(mvc);
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private String ruleBody(TestWorld w, String name) {
		return "{\"zoneId\":\"" + w.zoneId + "\",\"name\":\"" + name + "\",\"weights\":{\"distance\":0.4,\"capacityFit\":0.25,"
				+ "\"availability\":0.15,\"zoneCompat\":0.2},\"ttlSeconds\":300,\"searchRadiusM\":15000,"
				+ "\"maxLocationAgeSeconds\":600,\"persistTopN\":50}";
	}

	private String stateOf(String id) {
		return jdbc.sql("SELECT status FROM admin_cfg.dispatch_rule_versions WHERE id = CAST(:id AS uuid)").param("id", id)
				.query(String.class).single();
	}

	@Test
	void aRuleCanBeScheduledCancelledRescheduledAndThenTakesOverWhenDue() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		String first = JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/dispatch-rules", ruleBody(w, "Now"),
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.id");
		api.post(ops, "/api/v1/admin/dispatch-rules/" + first + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		String second = JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/dispatch-rules", ruleBody(w, "Later"),
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.id");
		String third = JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/dispatch-rules", ruleBody(w, "Even later"),
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.id");
		String path = "/api/v1/admin/dispatch-rules/";

		api.post(ops, path + second + "/activate", "{\"effectiveFrom\":\"" + Instant.now().plusSeconds(10) + "\"}",
				"Idempotency-Key", key()).andExpect(status().isBadRequest());
		api.post(ops, path + second + "/activate", "{\"effectiveFrom\":\"" + Instant.now().plus(Duration.ofDays(400)) + "\"}",
				"Idempotency-Key", key()).andExpect(status().isBadRequest());

		String at = Instant.now().plus(Duration.ofHours(2)).toString();
		api.post(ops, path + second + "/activate", "{\"effectiveFrom\":\"" + at + "\"}", "Idempotency-Key", key())
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
		assertThat(stateOf(first)).isEqualTo("ACTIVE");
		api.post(ops, path + third + "/activate", "{\"effectiveFrom\":\"" + at + "\"}", "Idempotency-Key", key())
				.andExpect(status().isConflict());

		api.post(ops, path + second + "/unschedule", "").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DRAFT"));
		api.post(ops, path + second + "/unschedule", "").andExpect(status().isConflict());
		api.post(ops, path + second + "/activate", "{\"effectiveFrom\":\"" + at + "\"}", "Idempotency-Key", key())
				.andExpect(status().isOk());

		assertThat(promotion.promoteDue()).isGreaterThanOrEqualTo(0);
		assertThat(stateOf(second)).isEqualTo("SCHEDULED");

		jdbc.sql("UPDATE admin_cfg.dispatch_rule_versions SET effective_from = now() - interval '1 minute' WHERE id = CAST(:id AS uuid)")
				.param("id", second).update();
		assertThat(promotion.promoteDue()).isGreaterThanOrEqualTo(1);

		assertThat(stateOf(second)).isEqualTo("ACTIVE");
		assertThat(stateOf(first)).isEqualTo("RETIRED");
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND actor_type = 'SYSTEM'")
				.param("id", second).query(Integer.class).single()).isEqualTo(1);
		api.post(ops, path + third + "/activate", "{\"effectiveFrom\":\"" + Instant.now().plus(Duration.ofDays(1)) + "\"}",
				"Idempotency-Key", key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
	}

	@Test
	void globalRulesAndPoliciesWithoutAZoneCanBeSavedAsDrafts() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		api.post(ops, "/api/v1/admin/dispatch-rules", "{\"zoneId\":null,\"name\":\"Quy tắc mới\",\"weights\":{\"distance\":0.4,"
				+ "\"capacityFit\":0.25,\"availability\":0.15,\"zoneCompat\":0.2},\"ttlSeconds\":300,\"searchRadiusM\":15000,"
				+ "\"maxLocationAgeSeconds\":600,\"persistTopN\":50}", "Idempotency-Key", key())
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));
		api.post(ops, "/api/v1/admin/pod-policies", "{\"zoneId\":null,\"name\":\"Chính sách mới\",\"evidenceExpression\":"
				+ "{\"op\":\"ANY_OF\",\"items\":[\"PHOTO\"]},\"maxGpsAccuracyM\":100,\"recipientNameRule\":\"DIRECT_HANDOFF\"}",
				"Idempotency-Key", key()).andExpect(status().isCreated());
	}
}
