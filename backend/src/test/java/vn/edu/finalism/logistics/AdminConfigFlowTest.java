package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.TestWorld.TestUser;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;
import vn.edu.finalism.logistics.modules.administration.application.EffectivePodPolicyPort;
import vn.edu.finalism.logistics.modules.administration.application.EffectiveRulePort;
import vn.edu.finalism.logistics.modules.administration.application.ZoneLookupPort;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class AdminConfigFlowTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	ZoneLookupPort zoneLookup;
	@Autowired
	EffectiveRulePort effectiveRule;
	@Autowired
	EffectivePodPolicyPort effectivePolicy;

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

	private static String square(double lng, double lat, double size) {
		return "{\"boundary\":{\"type\":\"Polygon\",\"coordinates\":[[[" + lng + "," + lat + "],[" + (lng + size) + ","
				+ lat + "],[" + (lng + size) + "," + (lat + size) + "],[" + lng + "," + (lat + size) + "],[" + lng + ","
				+ lat + "]]]}}";
	}

	private String newZone(String ops) throws Exception {
		String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		return JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/zones",
				"{\"code\":\"" + code + "\",\"name\":\"Test zone\"}", "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.id");
	}

	private String addVersion(String ops, String zoneId, String body) throws Exception {
		return JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/zones/" + zoneId + "/versions", body,
				"Idempotency-Key", key()).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))),
				"$.id");
	}

	private String rule(String zoneId, String weights, int ttl) {
		return "{\"zoneId\":\"" + zoneId + "\",\"name\":\"Rule\",\"weights\":" + weights + ",\"ttlSeconds\":" + ttl
				+ ",\"searchRadiusM\":15000,\"maxLocationAgeSeconds\":600,\"persistTopN\":50}";
	}

	@Test
	void nobodyExceptTheOperationsManagerCanTouchConfiguration() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		for (TestUser user : new TestUser[] { w.customer, w.dispatcher, w.driverUser, w.admin }) {
			String token = api.login(user);
			api.get(token, "/api/v1/admin/zones").andExpect(status().isForbidden());
			api.get(token, "/api/v1/admin/dispatch-rules").andExpect(status().isForbidden());
			api.get(token, "/api/v1/admin/pod-policies").andExpect(status().isForbidden());
			api.post(token, "/api/v1/admin/zones", "{\"code\":\"AB\",\"name\":\"x\"}", "Idempotency-Key", key())
					.andExpect(status().isForbidden());
		}
		api.get(api.login(w.dispatcher), "/api/v1/admin/zone-options").andExpect(status().isForbidden());
		api.get(api.login(w.admin), "/api/v1/admin/zone-options").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id == '" + w.zoneId + "')].code").exists());
		String ops = api.login(w.ops);
		api.get(ops, "/api/v1/admin/zones").andExpect(status().isOk());
		api.get(ops, "/api/v1/admin/dispatch-rules").andExpect(status().isOk());
		api.get(ops, "/api/v1/admin/pod-policies").andExpect(status().isOk());
	}

	@Test
	void aZoneGoesFromDraftToActiveWithoutOverlappingAnotherActiveZone() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		double lat = -(20 + ThreadLocalRandom.current().nextInt(60));
		double lng = -170 + ThreadLocalRandom.current().nextInt(330);

		String zoneA = newZone(ops);
		api.post(ops, "/api/v1/admin/zones", "{\"code\":\"bad code\",\"name\":\"x\"}", "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.get(ops, "/api/v1/admin/zones").andExpect(jsonPath("$.items[?(@.id == '" + zoneA + "')]").exists());

		String bowtie = "{\"boundary\":{\"type\":\"Polygon\",\"coordinates\":[[[" + lng + "," + lat + "],[" + (lng + 1) + ","
				+ (lat + 1) + "],[" + (lng + 1) + "," + lat + "],[" + lng + "," + (lat + 1) + "],[" + lng + "," + lat + "]]]}}";
		api.post(ops, "/api/v1/admin/zones/" + zoneA + "/versions", bowtie, "Idempotency-Key", key())
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		api.post(ops, "/api/v1/admin/zones/" + zoneA + "/versions",
				"{\"boundary\":{\"type\":\"Point\",\"coordinates\":[" + lng + "," + lat + "]}}", "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/zones/" + zoneA + "/versions", "{\"boundary\":{\"type\":\"Nonsense\"}}",
				"Idempotency-Key", key()).andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/zones/" + zoneA + "/versions",
				square(300, lat, 0.1), "Idempotency-Key", key()).andExpect(status().isBadRequest());

		String versionA1 = addVersion(ops, zoneA, square(lng, lat, 0.1));
		String activationKey = key();
		api.post(ops, "/api/v1/admin/zone-versions/" + versionA1 + "/activate", "", "Idempotency-Key", activationKey)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		api.post(ops, "/api/v1/admin/zone-versions/" + versionA1 + "/activate", "", "Idempotency-Key", activationKey)
				.andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
		api.post(ops, "/api/v1/admin/zone-versions/" + versionA1 + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isConflict());
		assertThat(zoneLookup.findZone(new GeoPoint(lat + 0.05, lng + 0.05))).get()
				.extracting(ZoneLookupPort.ZoneMatch::zoneId).isEqualTo(UUID.fromString(zoneA));

		String zoneB = newZone(ops);
		String overlapping = addVersion(ops, zoneB, square(lng + 0.05, lat + 0.05, 0.1));
		api.post(ops, "/api/v1/admin/zone-versions/" + overlapping + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"))
				.andExpect(jsonPath("$.details.overlappingZoneIds[0]").value(zoneA));
		String adjacent = addVersion(ops, zoneB, square(lng + 0.1, lat, 0.1));
		api.post(ops, "/api/v1/admin/zone-versions/" + adjacent + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk());

		String versionA2 = addVersion(ops, zoneA, square(lng, lat, 0.09));
		api.post(ops, "/api/v1/admin/zone-versions/" + versionA2 + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk());
		api.get(ops, "/api/v1/admin/zones/" + zoneA + "/versions").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].versionNo").value(2)).andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.items[1].status").value("RETIRED")).andExpect(jsonPath("$.items[1].retiredAt").exists());
		api.get(ops, "/api/v1/admin/zones").andExpect(jsonPath("$.items[?(@.id == '" + zoneA + "')].activeVersionNo").value(2));

		TestWorld other = TestWorld.create(jdbc, encoder, true);
		String foreignOps = api.login(other.ops);
		api.post(foreignOps, "/api/v1/admin/zones/" + zoneA + "/versions", square(lng, lat, 0.1), "Idempotency-Key", key())
				.andExpect(status().isNotFound());
		api.get(foreignOps, "/api/v1/admin/zones/" + zoneA + "/versions").andExpect(status().isNotFound());
		api.post(foreignOps, "/api/v1/admin/zone-versions/" + versionA2 + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isNotFound());

		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND action = 'ZONE_CHANGED'")
				.param("id", zoneA).query(Integer.class).single()).isEqualTo(2);
	}

	@Test
	void aPublishedVersionCannotBeEditedOrDeletedOnlyRetired() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		String zone = newZone(ops);
		String body = rule(zone, "{\"distance\":0.4,\"capacityFit\":0.25,\"availability\":0.15,\"zoneCompat\":0.2}", 300);
		String ruleId = JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/dispatch-rules", body, "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.id");
		api.post(ops, "/api/v1/admin/dispatch-rules/" + ruleId + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk());

		assertThatThrownBy(() -> jdbc.sql("UPDATE admin_cfg.dispatch_rule_versions SET name = 'Hacked' WHERE id = CAST(:id AS uuid)")
				.param("id", ruleId).update()).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE admin_cfg.dispatch_rule_versions SET ttl_seconds = 60, w_distance = 0.5, w_capacity_fit = 0.15 WHERE id = CAST(:id AS uuid)")
				.param("id", ruleId).update()).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE admin_cfg.dispatch_rule_versions SET status = 'DRAFT' WHERE id = CAST(:id AS uuid)")
				.param("id", ruleId).update()).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM admin_cfg.dispatch_rule_versions WHERE id = CAST(:id AS uuid)")
				.param("id", ruleId).update()).isInstanceOf(DataAccessException.class);

		assertThat(jdbc.sql("UPDATE admin_cfg.dispatch_rule_versions SET status = 'RETIRED', retired_at = now() WHERE id = CAST(:id AS uuid)")
				.param("id", ruleId).update()).isEqualTo(1);
		assertThatThrownBy(() -> jdbc.sql("UPDATE admin_cfg.dispatch_rule_versions SET status = 'ACTIVE' WHERE id = CAST(:id AS uuid)")
				.param("id", ruleId).update()).isInstanceOf(DataAccessException.class);

		String zoneVersion = addVersion(ops, zone, square(10, -10 - ThreadLocalRandom.current().nextInt(5), 0.1));
		api.post(ops, "/api/v1/admin/zone-versions/" + zoneVersion + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk());
		assertThatThrownBy(() -> jdbc.sql("UPDATE admin_cfg.delivery_zone_versions SET effective_from = now() - interval '1 day' WHERE id = CAST(:id AS uuid)")
				.param("id", zoneVersion).update()).isInstanceOf(DataAccessException.class);
	}

	@Test
	void aNewRuleVersionReplacesTheOldOneForThatZoneOnly() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		String zone = newZone(ops);
		UUID zoneId = UUID.fromString(zone);
		String balanced = "{\"distance\":0.25,\"capacityFit\":0.25,\"availability\":0.25,\"zoneCompat\":0.25}";

		api.post(ops, "/api/v1/admin/dispatch-rules", rule(zone, "{\"distance\":0.5,\"capacityFit\":0.5,\"availability\":0.5,\"zoneCompat\":0.5}", 300),
				"Idempotency-Key", key()).andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/dispatch-rules", rule(zone, balanced, 30), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/dispatch-rules", rule(UUID.randomUUID().toString(), balanced, 300), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/dispatch-rules", rule(w.zoneId.toString(), balanced, 300), "Idempotency-Key", key())
				.andExpect(status().isCreated());
		api.post(api.login(TestWorld.create(jdbc, encoder, true).ops), "/api/v1/admin/dispatch-rules",
				rule(zone, balanced, 300), "Idempotency-Key", key()).andExpect(status().isNotFound());

		String createKey = key();
		String first = TestApi.bodyOf(api.post(ops, "/api/v1/admin/dispatch-rules", rule(zone, balanced, 300),
				"Idempotency-Key", createKey).andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(1))
				.andExpect(jsonPath("$.status").value("DRAFT")));
		String firstId = JsonPath.read(first, "$.id");
		api.post(ops, "/api/v1/admin/dispatch-rules", rule(zone, balanced, 300), "Idempotency-Key", createKey)
				.andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.id").value(firstId));
		api.post(ops, "/api/v1/admin/dispatch-rules/" + firstId + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk());
		assertThat(effectiveRule.effectiveRule(zoneId).versionNo()).isEqualTo(1);

		String heavy = "{\"distance\":0.7,\"capacityFit\":0.1,\"availability\":0.1,\"zoneCompat\":0.1}";
		String secondId = JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/dispatch-rules", rule(zone, heavy, 600),
				"Idempotency-Key", key()).andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(2))), "$.id");
		api.post(ops, "/api/v1/admin/dispatch-rules/" + secondId + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk()).andExpect(jsonPath("$.activatedAt").exists());

		var now = effectiveRule.effectiveRule(zoneId);
		assertThat(now.versionNo()).isEqualTo(2);
		assertThat(now.weightDistance()).isEqualByComparingTo(new BigDecimal("0.7"));
		assertThat(now.ttlSeconds()).isEqualTo(600);
		api.get(ops, "/api/v1/admin/dispatch-rules?status=RETIRED").andExpect(jsonPath("$.items[?(@.id == '" + firstId + "')]").exists());
		assertThat(jdbc.sql("SELECT status FROM admin_cfg.dispatch_rule_versions WHERE id = CAST(:id AS uuid)")
				.param("id", firstId).query(String.class).single()).isEqualTo("RETIRED");
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE action = 'DISPATCH_RULE_CHANGED' AND zone_id = CAST(:id AS uuid)")
				.param("id", zone).query(Integer.class).single()).isEqualTo(2);
	}

	@Test
	void aPodPolicyVersionBecomesTheSnapshotSourceForNewDeliveries() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		String zone = newZone(ops);

		api.post(ops, "/api/v1/admin/pod-policies", policy(zone, "SOME_OF", "[\"OTP\"]"), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/pod-policies", policy(zone, "ALL_OF", "[\"OTP\",\"FINGERPRINT\"]"), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/pod-policies", policy(zone, "ALL_OF", "[]"), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());

		String id = JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/pod-policies",
				policy(zone, "ALL_OF", "[\"OTP\",\"SIGNATURE\"]"), "Idempotency-Key", key()).andExpect(status().isCreated())
				.andExpect(jsonPath("$.requiresGps").value(true)).andExpect(jsonPath("$.versionNo").value(1))), "$.id");
		api.post(ops, "/api/v1/admin/pod-policies/" + id + "/activate", "", "Idempotency-Key", key())
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

		var policy = effectivePolicy.effectivePolicy(UUID.fromString(zone));
		assertThat(policy.evidenceExpression()).containsEntry("op", "ALL_OF");
		assertThat(policy.maxGpsAccuracyM()).isEqualTo(40);
		assertThat(policy.recipientNameRule()).isEqualTo("ALWAYS");
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE action = 'POD_POLICY_CHANGED' AND zone_id = CAST(:id AS uuid)")
				.param("id", zone).query(Integer.class).single()).isEqualTo(1);
	}

	private String policy(String zone, String op, String items) {
		return "{\"zoneId\":\"" + zone + "\",\"name\":\"Strict\",\"evidenceExpression\":{\"op\":\"" + op + "\",\"items\":"
				+ items + "},\"maxGpsAccuracyM\":40,\"recipientNameRule\":\"ALWAYS\"}";
	}
}
