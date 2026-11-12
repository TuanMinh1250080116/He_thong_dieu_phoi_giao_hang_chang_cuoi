package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;
import vn.edu.finalism.logistics.platform.event.OutboxRelay;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class SliceOneFlowTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	OutboxRelay relay;

	TestApi api;

	@BeforeEach
	void setUp() {
		jdbc.sql("UPDATE platform.event_publications SET status = 'DISPATCHED', dispatched_at = now() WHERE status IN ('PENDING','FAILED')")
				.update();
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		api = new TestApi(mvc);
	}

	private record Scenario(TestWorld world, String customerToken, String otherCustomerToken, String dispatcherToken,
			String driverToken, String orderOne, String orderTwo, String batchId, String decisionId,
			String snapshotId, String assignmentId, String deliveryId) {
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private static String orderBody(TestWorld w, String recipient) {
		Instant now = Instant.now();
		return """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"%s","contact":"+84900000000"},
				 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":"fragile"},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(), recipient,
				now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
	}

	private String createOrder(String token, TestWorld w, String recipient) throws Exception {
		ResultActions created = api.post(token, "/api/v1/orders", orderBody(w, recipient), "Idempotency-Key", key())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("READY_FOR_DISPATCH"))
				.andExpect(header().string("ETag", "\"0\""));
		return JsonPath.read(TestApi.bodyOf(created), "$.data.id");
	}

	private Scenario upToRecommendation(TestWorld w) throws Exception {
		String customerToken = api.login(w.customer);
		String otherCustomerToken = api.login(w.otherCustomer);
		String dispatcherToken = api.login(w.dispatcher);
		String driverToken = api.login(w.driverUser);

		String orderOne = createOrder(customerToken, w, "Nguyen Van A");
		String orderTwo = createOrder(otherCustomerToken, w, "Tran Thi B");

		String batchBody = "{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + orderOne + "\",\"" + orderTwo + "\"]}";
		ResultActions batch = api.post(dispatcherToken, "/api/v1/dispatch/batches", batchBody, "Idempotency-Key", key())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("DRAFT"))
				.andExpect(jsonPath("$.data.orderCount").value(2));
		String batchId = JsonPath.read(TestApi.bodyOf(batch), "$.data.id");

		api.get(customerToken, "/api/v1/orders/" + orderOne)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BATCHED"));

		api.post(dispatcherToken, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("LOCKED"))
				.andExpect(header().string("ETag", "\"1\""));

		ResultActions recommendation = api.post(dispatcherToken,
				"/api/v1/dispatch/batches/" + batchId + "/recommendations", "", "If-Match", "\"1\"")
				.andExpect(status().isCreated())
				.andExpect(header().string("ETag", "\"1\""));
		String json = TestApi.bodyOf(recommendation);
		return new Scenario(w, customerToken, otherCustomerToken, dispatcherToken, driverToken, orderOne, orderTwo,
				batchId, JsonPath.read(json, "$.data.decisionId"), JsonPath.read(json, "$.data.snapshot.id"),
				null, null);
	}

	private Scenario upToStartedDelivery(TestWorld w) throws Exception {
		Scenario s = upToRecommendation(w);
		api.post(s.dispatcherToken(), "/api/v1/dispatch/decisions/" + s.decisionId() + "/assign",
				"{\"snapshotId\":\"" + s.snapshotId() + "\"}", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.currentVersion").value(1));

		ResultActions assigned = api.post(s.dispatcherToken(), "/api/v1/dispatch/decisions/" + s.decisionId() + "/assign",
				"{\"snapshotId\":\"" + s.snapshotId() + "\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("PENDING"))
				.andExpect(header().string("ETag", "\"2\""));
		String deliveryId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.deliveryId");
		String assignmentId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.id");

		api.get(s.driverToken(), "/api/v1/me/assignments").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(assignmentId))
				.andExpect(jsonPath("$.items[0].version").value(2));
		api.post(s.driverToken(), "/api/v1/assignments/" + assignmentId + "/accept", "",
				"Idempotency-Key", key(), "If-Match", "\"2\"")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"))
				.andExpect(header().string("ETag", "\"3\""));

		ResultActions delivery = api.get(s.driverToken(), "/api/v1/deliveries/" + deliveryId)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		api.post(s.driverToken(), "/api/v1/deliveries/" + deliveryId + "/start", "",
				"Idempotency-Key", key(), "If-Match", TestApi.etagOf(delivery))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
		return new Scenario(w, s.customerToken(), s.otherCustomerToken(), s.dispatcherToken(), s.driverToken(),
				s.orderOne(), s.orderTwo(), s.batchId(), s.decisionId(), s.snapshotId(), assignmentId, deliveryId);
	}

	private String deliveryEtag(Scenario s) throws Exception {
		return TestApi.etagOf(api.get(s.driverToken(), "/api/v1/deliveries/" + s.deliveryId()));
	}

	private void stopAction(Scenario s, String stopId, String action) throws Exception {
		api.post(s.driverToken(), "/api/v1/deliveries/" + s.deliveryId() + "/stops/" + stopId + "/actions",
				"{\"action\":\"" + action + "\"}", "If-Match", deliveryEtag(s)).andExpect(status().isOk());
	}

	private String uploadVerifiedPhoto(Scenario s, String stopId) throws Exception {
		byte[] jpeg = new byte[2048];
		jpeg[0] = (byte) 0xFF;
		jpeg[1] = (byte) 0xD8;
		jpeg[2] = (byte) 0xFF;
		for (int i = 3; i < jpeg.length; i++) {
			jpeg[i] = (byte) (i % 251);
		}
		ResultActions ticket = api.post(s.driverToken(), "/api/v1/deliveries/" + s.deliveryId() + "/evidence-uploads",
				"{\"purpose\":\"POD\",\"kind\":\"PHOTO\",\"stopId\":\"" + stopId + "\",\"mimeType\":\"image/jpeg\","
						+ "\"sizeBytes\":" + jpeg.length + "}")
				.andExpect(status().isCreated());
		String evidenceId = JsonPath.read(TestApi.bodyOf(ticket), "$.evidenceId");
		String uploadUrl = JsonPath.read(TestApi.bodyOf(ticket), "$.uploadUrl");
		api.putBytes(s.driverToken(), uploadUrl, jpeg).andExpect(status().isNoContent());
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
		api.post(s.driverToken(), "/api/v1/evidence/" + evidenceId + "/complete", "{\"sha256\":\"" + sha + "\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
		return evidenceId;
	}

	private String podBody(TestWorld w, String evidenceId) {
		return """
				{"gps":{"point":{"lat":%s,"lng":%s},"accuracyM":12.5},
				 "isDirectHandoff":true,"recipientName":"Nguyen Van A","evidenceIds":["%s"],"note":"handed over"}
				""".formatted(w.dropoffLat(), w.dropoffLng(), evidenceId);
	}

	@Test
	void fullDeliveryFlowFromOrderToCompletion() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		Scenario s = upToRecommendation(w);

		String snapshotJson = TestApi.bodyOf(api.get(s.dispatcherToken(),
				"/api/v1/dispatch/batches/" + s.batchId() + "/recommendations/current").andExpect(status().isOk()));
		assertThat((String) JsonPath.read(snapshotJson, "$.data.snapshot.outcome")).isEqualTo("RANKED");
		assertThat((String) JsonPath.read(snapshotJson, "$.data.snapshot.candidates[0].driverId"))
				.isEqualTo(w.driverId.toString());
		assertThat((Object) JsonPath.read(snapshotJson, "$.data.snapshot.candidates[0].breakdown.distance")).isNotNull();

		String replayKey = key();
		String assignBody = "{\"snapshotId\":\"" + s.snapshotId() + "\"}";
		ResultActions assigned = api.post(s.dispatcherToken(), "/api/v1/dispatch/decisions/" + s.decisionId() + "/assign",
				assignBody, "Idempotency-Key", replayKey, "If-Match", "\"1\"").andExpect(status().isOk());
		String assignmentId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.id");
		api.post(s.dispatcherToken(), "/api/v1/dispatch/decisions/" + s.decisionId() + "/assign", assignBody,
				"Idempotency-Key", replayKey, "If-Match", "\"1\"")
				.andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.data.id").value(assignmentId));
		assertThat(jdbc.sql("SELECT availability FROM fleet.drivers WHERE id = :id").param("id", w.driverId)
				.query(String.class).single()).isEqualTo("RESERVED");

		api.post(s.driverToken(), "/api/v1/assignments/" + assignmentId + "/accept", "",
				"Idempotency-Key", key(), "If-Match", "\"2\"").andExpect(status().isOk());
		String deliveryId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.deliveryId");
		Scenario started = new Scenario(w, s.customerToken(), s.otherCustomerToken(), s.dispatcherToken(),
				s.driverToken(), s.orderOne(), s.orderTwo(), s.batchId(), s.decisionId(), s.snapshotId(),
				assignmentId, deliveryId);
		api.post(started.driverToken(), "/api/v1/deliveries/" + deliveryId + "/start", "",
				"Idempotency-Key", key(), "If-Match", deliveryEtag(started))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
		assertThat(jdbc.sql("SELECT availability FROM fleet.drivers WHERE id = :id").param("id", w.driverId)
				.query(String.class).single()).isEqualTo("BUSY");

		String deliveryJson = TestApi.bodyOf(api.get(started.driverToken(), "/api/v1/deliveries/" + deliveryId)
				.andExpect(status().isOk()));
		List<String> stopIds = JsonPath.read(deliveryJson, "$.stops[*].id");
		assertThat(stopIds).hasSize(2);

		for (int i = 0; i < stopIds.size(); i++) {
			String stopId = stopIds.get(i);
			stopAction(started, stopId, "START_STOP");
			stopAction(started, stopId, "MARK_ARRIVED");
			String evidenceId = uploadVerifiedPhoto(started, stopId);
			api.post(started.driverToken(), "/api/v1/deliveries/" + deliveryId + "/stops/" + stopId + "/pod",
					podBody(w, evidenceId), "Idempotency-Key", key())
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.data.evidenceTypes[0]").value("PHOTO"))
					.andExpect(jsonPath("$.data.otpStatus").value("NOT_VERIFIED"));

			if (i == 0) {
				api.post(started.driverToken(), "/api/v1/deliveries/" + deliveryId + "/complete", "",
						"Idempotency-Key", key(), "If-Match", deliveryEtag(started))
						.andExpect(status().isConflict())
						.andExpect(jsonPath("$.code").value("COMPLETION_PREREQUISITES_MISSING"))
						.andExpect(jsonPath("$.details.incompleteStopIds.length()").value(1));
			}
		}

		api.post(started.driverToken(), "/api/v1/deliveries/" + deliveryId + "/complete", "",
				"Idempotency-Key", key(), "If-Match", deliveryEtag(started))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));

		api.get(s.customerToken(), "/api/v1/orders/" + s.orderOne())
				.andExpect(jsonPath("$.status").value("DELIVERED"));
		api.get(s.otherCustomerToken(), "/api/v1/orders/" + s.orderTwo())
				.andExpect(jsonPath("$.status").value("DELIVERED"));
		assertThat(jdbc.sql("SELECT availability FROM fleet.drivers WHERE id = :id").param("id", w.driverId)
				.query(String.class).single()).isEqualTo("AVAILABLE");
		assertThat(jdbc.sql("SELECT status FROM dispatch.delivery_batches WHERE id = :id")
				.param("id", UUID.fromString(s.batchId())).query(String.class).single()).isEqualTo("COMPLETED");
		assertThat(jdbc.sql("SELECT count(*) FROM dispatch.driver_commitments WHERE driver_id = :id AND status = 'ACTIVE'")
				.param("id", w.driverId).query(Long.class).single()).isZero();

		Set<String> audited = jdbc.sql("""
				SELECT DISTINCT action FROM audit.audit_records WHERE actor_id IN (:actors)
				""")
				.param("actors", List.of(w.customer.id(), w.otherCustomer.id(), w.dispatcher.id(), w.driverUser.id()))
				.query(String.class).list().stream().collect(Collectors.toSet());
		assertThat(audited).contains("ORDER_CREATED", "BATCH_CREATED", "BATCH_LOCKED", "RECOMMENDATION_GENERATED",
				"DRIVER_ASSIGNED", "DRIVER_ASSIGNMENT_ACCEPTED", "DELIVERY_STARTED", "POD_SUBMITTED",
				"DELIVERY_COMPLETED");

		relay.dispatchBatch();
		Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			String customerInbox = TestApi.bodyOf(api.get(s.customerToken(), "/api/v1/notifications")
					.andExpect(status().isOk()));
			List<String> titles = JsonPath.read(customerInbox, "$.items[*].title");
			assertThat(titles).hasSize(3);
			assertThat(JsonPath.<List<String>>read(TestApi.bodyOf(api.get(s.driverToken(), "/api/v1/notifications")),
					"$.items[*].title")).hasSize(1);
			assertThat(JsonPath.<List<String>>read(TestApi.bodyOf(api.get(s.dispatcherToken(), "/api/v1/notifications")),
					"$.items[*].title")).hasSize(1);
		});

		api.get(s.customerToken(), "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(3));
		String firstId = JsonPath.read(TestApi.bodyOf(api.get(s.customerToken(), "/api/v1/notifications")),
				"$.items[0].id");
		api.post(s.customerToken(), "/api/v1/notifications/" + firstId + "/read", "").andExpect(status().isNoContent());
		api.get(s.customerToken(), "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(2));
	}

	@Test
	void customerCannotSeeAnotherCustomersOrder() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String orderId = createOrder(api.login(w.customer), w, "Nguyen Van A");

		api.get(api.login(w.otherCustomer), "/api/v1/orders/" + orderId)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void batchingNeedsDispatcherRoleAndZoneScope() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		TestWorld elsewhere = TestWorld.create(jdbc, encoder, true);
		String customerToken = api.login(w.customer);
		String orderId = createOrder(customerToken, w, "Nguyen Van A");
		String body = "{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + orderId + "\"]}";

		api.post(customerToken, "/api/v1/dispatch/batches", body, "Idempotency-Key", key())
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		api.post(api.login(elsewhere.dispatcher), "/api/v1/dispatch/batches", body, "Idempotency-Key", key())
				.andExpect(status().isNotFound());
	}

	@Test
	void staleDriverLocationLeavesNoEligibleDriver() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, false);
		Scenario s = upToRecommendation(w);

		String json = TestApi.bodyOf(api.get(s.dispatcherToken(),
				"/api/v1/dispatch/batches/" + s.batchId() + "/recommendations/current").andExpect(status().isOk()));
		assertThat((String) JsonPath.read(json, "$.data.snapshot.outcome")).isEqualTo("NO_ELIGIBLE_DRIVER");
		assertThat((Integer) JsonPath.read(json, "$.data.snapshot.exclusionSummary.LOCATION_STALE")).isEqualTo(1);

		api.post(s.dispatcherToken(), "/api/v1/dispatch/decisions/" + s.decisionId() + "/assign",
				"{\"snapshotId\":\"" + s.snapshotId() + "\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
	}

	@Test
	void expiredRecommendationCannotBeAssigned() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		Scenario s = upToRecommendation(w);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbc.sql("UPDATE dispatch.recommendation_snapshots SET generated_at = :generated, expires_at = :expires WHERE id = :id")
				.param("generated", now.minusMinutes(10)).param("expires", now.minusMinutes(5))
				.param("id", UUID.fromString(s.snapshotId())).update();

		api.post(s.dispatcherToken(), "/api/v1/dispatch/decisions/" + s.decisionId() + "/assign",
				"{\"snapshotId\":\"" + s.snapshotId() + "\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECOMMENDATION_EXPIRED"));
	}

	@Test
	void proofWithoutEvidenceIsRejectedAndNamesWhatIsMissing() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		Scenario s = upToStartedDelivery(w);
		String deliveryJson = TestApi.bodyOf(api.get(s.driverToken(), "/api/v1/deliveries/" + s.deliveryId()));
		String stopId = JsonPath.<List<String>>read(deliveryJson, "$.stops[*].id").get(0);
		stopAction(s, stopId, "START_STOP");

		String body = """
				{"gps":{"point":{"lat":%s,"lng":%s},"accuracyM":10},"recipientName":"Nguyen Van A","evidenceIds":[]}
				""".formatted(w.dropoffLat(), w.dropoffLng());
		api.post(s.driverToken(), "/api/v1/deliveries/" + s.deliveryId() + "/stops/" + stopId + "/pod", body,
				"Idempotency-Key", key())
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("POD_POLICY_NOT_SATISFIED"))
				.andExpect(jsonPath("$.details.missing").isArray());
	}

	@Test
	void anotherDriverCannotActOnTheDelivery() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		Scenario s = upToStartedDelivery(w);
		TestWorld other = TestWorld.create(jdbc, encoder, true);

		api.get(api.login(other.driverUser), "/api/v1/deliveries/" + s.deliveryId())
				.andExpect(status().isNotFound());
	}
}
