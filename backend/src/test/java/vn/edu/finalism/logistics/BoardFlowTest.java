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
import java.util.UUID;

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

import vn.edu.finalism.logistics.TestWorld.DriverRef;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class BoardFlowTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;

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

	private String order(TestWorld w, String customer) throws Exception {
		Instant now = Instant.now();
		String body = """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
				 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(),
				now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
		return JsonPath.read(TestApi.bodyOf(api.post(customer, "/api/v1/orders", body, "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
	}

	private String batch(TestWorld w, String dispatcher, String... orderIds) throws Exception {
		String ids = String.join("\",\"", orderIds);
		return JsonPath.read(TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches",
				"{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + ids + "\"]}", "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
	}

	private ResultActions board(String token, String query) throws Exception {
		return api.get(token, "/api/v1/dispatch/board" + query).andExpect(status().isOk());
	}

	private static String count(String panel) {
		return "$.panels." + panel + ".count";
	}

	@Test
	void theBoardFollowsABatchThroughItsWholeLifeWithoutLeakingOtherZones() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		DriverRef second = w.addDriver();
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String firstDriver = api.login(w.driverUser);
		String secondDriver = api.login(second.user());
		String ops = api.login(w.ops);

		String o1 = order(w, customer);
		String o2 = order(w, customer);
		order(w, customer);
		board(dispatcher, "").andExpect(jsonPath(count("readyOrders")).value(3))
				.andExpect(jsonPath(count("draftBatches")).value(0)).andExpect(jsonPath(count("activeDeliveries")).value(0))
				.andExpect(jsonPath("$.asOf").exists());

		String batchId = batch(w, dispatcher, o1, o2);
		board(dispatcher, "").andExpect(jsonPath(count("readyOrders")).value(1))
				.andExpect(jsonPath(count("draftBatches")).value(1))
				.andExpect(jsonPath("$.panels.draftBatches.items[0].id").value(batchId));

		api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"").andExpect(status().isOk());
		board(dispatcher, "").andExpect(jsonPath(count("draftBatches")).value(0))
				.andExpect(jsonPath(count("lockedWithoutRecommendation")).value(1));

		ResultActions generated = api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations", "",
				"If-Match", "\"1\"").andExpect(status().isCreated());
		String decisionId = JsonPath.read(TestApi.bodyOf(generated), "$.data.decisionId");
		String firstSnapshot = JsonPath.read(TestApi.bodyOf(generated), "$.data.snapshot.id");
		board(dispatcher, "").andExpect(jsonPath(count("lockedWithoutRecommendation")).value(0))
				.andExpect(jsonPath(count("expiredRecommendations")).value(0));

		jdbc.sql("UPDATE dispatch.recommendation_snapshots SET generated_at = generated_at - interval '10 minutes', expires_at = now() - interval '1 minute' WHERE id = CAST(:id AS uuid)")
				.param("id", firstSnapshot).update();
		board(dispatcher, "").andExpect(jsonPath(count("expiredRecommendations")).value(1))
				.andExpect(jsonPath("$.panels.expiredRecommendations.items[0].decisionId").value(decisionId));

		ResultActions regenerated = api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations", "",
				"If-Match", "\"1\"").andExpect(status().isCreated());
		String snapshot = JsonPath.read(TestApi.bodyOf(regenerated), "$.data.snapshot.id");
		board(dispatcher, "").andExpect(jsonPath(count("expiredRecommendations")).value(0));

		ResultActions assigned = api.post(dispatcher, "/api/v1/dispatch/decisions/" + decisionId + "/assign",
				"{\"snapshotId\":\"" + snapshot + "\"}", "Idempotency-Key", key(), "If-Match",
				TestApi.etagOf(regenerated)).andExpect(status().isOk());
		String assignmentId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.id");
		board(dispatcher, "").andExpect(jsonPath(count("awaitingDriverResponse")).value(1))
				.andExpect(jsonPath("$.panels.awaitingDriverResponse.items[0].driverName").value("Test Driver"))
				.andExpect(jsonPath("$.panels.awaitingDriverResponse.items[0].decidedAt").exists());

		ResultActions rejected = api.post(firstDriver, "/api/v1/assignments/" + assignmentId + "/reject",
				"{\"reasonCode\":\"SCHEDULE_CONFLICT\"}", "Idempotency-Key", key(), "If-Match", TestApi.etagOf(assigned))
				.andExpect(status().isOk());
		board(dispatcher, "").andExpect(jsonPath(count("awaitingDriverResponse")).value(0))
				.andExpect(jsonPath(count("awaitingReassignment")).value(1))
				.andExpect(jsonPath("$.panels.awaitingReassignment.items[0].rejectionReasonCode").value("SCHEDULE_CONFLICT"));

		ResultActions reassigned = api.post(dispatcher, "/api/v1/dispatch/decisions/" + decisionId + "/reassign",
				"{\"driverId\":\"" + second.driverId() + "\",\"reason\":\"First declined\"}", "Idempotency-Key", key(),
				"If-Match", TestApi.etagOf(rejected)).andExpect(status().isOk());
		String newAssignment = JsonPath.read(TestApi.bodyOf(reassigned), "$.data.id");
		String deliveryId = JsonPath.read(TestApi.bodyOf(reassigned), "$.data.deliveryId");
		board(dispatcher, "").andExpect(jsonPath(count("awaitingReassignment")).value(0))
				.andExpect(jsonPath("$.panels.awaitingDriverResponse.items[0].driverName").value("Second Driver"));

		api.post(secondDriver, "/api/v1/assignments/" + newAssignment + "/accept", "", "Idempotency-Key", key(), "If-Match",
				TestApi.etagOf(reassigned)).andExpect(status().isOk());
		api.post(secondDriver, "/api/v1/deliveries/" + deliveryId + "/start", "", "Idempotency-Key", key(), "If-Match",
				TestApi.etagOf(api.get(secondDriver, "/api/v1/deliveries/" + deliveryId))).andExpect(status().isOk());

		board(ops, "").andExpect(jsonPath(count("awaitingDriverResponse")).value(0))
				.andExpect(jsonPath(count("activeDeliveries")).value(1))
				.andExpect(jsonPath("$.panels.activeDeliveries.items[0].completedStops").value(0))
				.andExpect(jsonPath("$.panels.activeDeliveries.items[0].totalStops").value(2))
				.andExpect(jsonPath("$.panels.activeDeliveries.items[0].driverName").value("Second Driver"));

		api.post(secondDriver, "/api/v1/deliveries/" + deliveryId + "/exceptions",
				"{\"type\":\"PACKAGE_DAMAGED\",\"description\":\"Box crushed\"}", "Idempotency-Key", key())
				.andExpect(status().isCreated());
		board(dispatcher, "").andExpect(jsonPath(count("openExceptions")).value(1))
				.andExpect(jsonPath("$.panels.openExceptions.items[0].impact").value("CUSTOMER_IMPACTING"));

		api.get(dispatcher, "/api/v1/dispatch/deliveries").andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(deliveryId))
				.andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.items[0].openExceptionCount").value(1))
				.andExpect(jsonPath("$.items[0].customerImpacting").value(true))
				.andExpect(jsonPath("$.items[0].driverName").value("Second Driver"))
				.andExpect(jsonPath("$.items[0].lastUpdateAt").exists());
		api.get(dispatcher, "/api/v1/dispatch/deliveries?status=COMPLETED").andExpect(jsonPath("$.items.length()").value(0));
		api.get(dispatcher, "/api/v1/dispatch/deliveries?q=second").andExpect(jsonPath("$.items.length()").value(1));
		api.get(dispatcher, "/api/v1/dispatch/deliveries?q=zzzz-nothing").andExpect(jsonPath("$.items.length()").value(0));
		api.get(dispatcher, "/api/v1/dispatch/deliveries?status=NONSENSE").andExpect(status().isBadRequest());

		TestWorld other = TestWorld.create(jdbc, encoder, true);
		String foreign = api.login(other.dispatcher);
		board(foreign, "").andExpect(jsonPath(count("readyOrders")).value(0))
				.andExpect(jsonPath(count("activeDeliveries")).value(0)).andExpect(jsonPath(count("openExceptions")).value(0));
		api.get(foreign, "/api/v1/dispatch/board?zoneId=" + w.zoneId).andExpect(status().isNotFound());
		api.get(foreign, "/api/v1/dispatch/deliveries").andExpect(jsonPath("$.items.length()").value(0));
		api.get(foreign, "/api/v1/dispatch/batches").andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void onlyPeopleWhoMonitorDispatchCanSeeTheBoard() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		for (String token : List.of(api.login(w.customer), api.login(w.driverUser), api.login(w.admin))) {
			api.get(token, "/api/v1/dispatch/board").andExpect(status().isForbidden());
			api.get(token, "/api/v1/dispatch/batches").andExpect(status().isForbidden());
			api.get(token, "/api/v1/dispatch/deliveries").andExpect(status().isForbidden());
		}
		api.get(api.login(w.dispatcher), "/api/v1/dispatch/board").andExpect(status().isOk());
		api.get(api.login(w.ops), "/api/v1/dispatch/board").andExpect(status().isOk());
	}

	@Test
	void pollingWithTheEtagAnswers304UntilSomethingChanges() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		order(w, customer);

		ResultActions first = board(dispatcher, "").andExpect(header().exists("ETag"));
		String etag = TestApi.etagOf(first);
		String body = TestApi.bodyOf(first);
		assertThat(body).contains("readyOrders");

		var unchanged = api.getWithHeaders(dispatcher, "/api/v1/dispatch/board", "If-None-Match", etag)
				.andExpect(status().isNotModified()).andExpect(header().string("ETag", etag)).andReturn().getResponse();
		assertThat(unchanged.getContentAsString()).isEmpty();
		api.getWithHeaders(dispatcher, "/api/v1/dispatch/board", "If-None-Match", "W/" + etag)
				.andExpect(status().isNotModified());

		order(w, customer);
		ResultActions changed = api.getWithHeaders(dispatcher, "/api/v1/dispatch/board", "If-None-Match", etag)
				.andExpect(status().isOk()).andExpect(jsonPath(count("readyOrders")).value(2));
		assertThat(TestApi.etagOf(changed)).isNotEqualTo(etag);
	}

	@Test
	void theBoardCanBeFilteredBySearchTextAndTheBatchListPages() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String o1 = order(w, customer);
		String o2 = order(w, customer);
		String o3 = order(w, customer);
		String batchOne = batch(w, dispatcher, o1);
		Thread.sleep(5);
		String batchTwo = batch(w, dispatcher, o2, o3);
		api.post(dispatcher, "/api/v1/dispatch/batches/" + batchTwo + "/lock", "", "If-Match", "\"0\"").andExpect(status().isOk());
		String codeOne = jdbc.sql("SELECT code FROM dispatch.delivery_batches WHERE id = CAST(:id AS uuid)")
				.param("id", batchOne).query(String.class).single();

		board(dispatcher, "?q=" + codeOne).andExpect(jsonPath(count("draftBatches")).value(1))
				.andExpect(jsonPath(count("lockedWithoutRecommendation")).value(0));
		board(dispatcher, "?q=NOPE-NOPE").andExpect(jsonPath(count("draftBatches")).value(0));
		board(dispatcher, "?zoneId=" + w.zoneId).andExpect(jsonPath(count("draftBatches")).value(1));

		String first = TestApi.bodyOf(api.get(dispatcher, "/api/v1/dispatch/batches?limit=1").andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(batchTwo))
				.andExpect(jsonPath("$.items[0].status").value("LOCKED")).andExpect(jsonPath("$.items[0].orderCount").value(2)));
		String second = TestApi.bodyOf(api.get(dispatcher, "/api/v1/dispatch/batches?limit=1&cursor="
				+ JsonPath.read(first, "$.nextCursor")).andExpect(jsonPath("$.items[0].id").value(batchOne)));
		assertThat(JsonPath.<Object>read(second, "$.nextCursor")).isNull();
		List<String> ids = new ArrayList<>(JsonPath.<List<String>>read(first, "$.items[*].id"));
		ids.addAll(JsonPath.<List<String>>read(second, "$.items[*].id"));
		assertThat(ids).doesNotHaveDuplicates();

		api.get(dispatcher, "/api/v1/dispatch/batches?status=DRAFT").andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(batchOne));
		api.get(dispatcher, "/api/v1/dispatch/batches?status=BOGUS").andExpect(status().isBadRequest());
		api.get(dispatcher, "/api/v1/dispatch/batches?q=" + codeOne).andExpect(jsonPath("$.items.length()").value(1));
	}
	@Test
	void theBoardAndListsCanBeNarrowedToATimeRange() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String o1 = order(w, customer);
		order(w, customer);
		batch(w, dispatcher, o1);
		Instant now = Instant.now();

		board(dispatcher, "?from=" + now.plus(Duration.ofHours(2)) + "&to=" + now.plus(Duration.ofHours(3)))
				.andExpect(jsonPath(count("readyOrders")).value(1));
		board(dispatcher, "?from=" + now.plus(Duration.ofHours(6))).andExpect(jsonPath(count("readyOrders")).value(0));
		board(dispatcher, "?to=" + now.plus(Duration.ofMinutes(30))).andExpect(jsonPath(count("readyOrders")).value(0));
		api.get(dispatcher, "/api/v1/dispatch/board?from=" + now.plus(Duration.ofHours(3)) + "&to=" + now)
				.andExpect(status().isBadRequest());

		api.get(dispatcher, "/api/v1/dispatch/batches?from=" + now.minus(Duration.ofHours(1)))
				.andExpect(jsonPath("$.items.length()").value(1));
		api.get(dispatcher, "/api/v1/dispatch/batches?from=" + now.plus(Duration.ofDays(1)))
				.andExpect(jsonPath("$.items.length()").value(0));
	}
}
