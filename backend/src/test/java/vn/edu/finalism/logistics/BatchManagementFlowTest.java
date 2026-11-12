package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class BatchManagementFlowTest {

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

	private String order(String customer, TestWorld w, int weight) throws Exception {
		Instant now = Instant.now();
		String body = """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
				 "package":{"weightG":%d,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(), weight,
				now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
		return JsonPath.read(TestApi.bodyOf(api.post(customer, "/api/v1/orders", body, "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
	}

	private String batch(String dispatcher, TestWorld w, String... orderIds) throws Exception {
		String ids = String.join("\",\"", orderIds);
		return JsonPath.read(TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches",
				"{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + ids + "\"]}", "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
	}

	private String orderStatus(String id) {
		return jdbc.sql("SELECT status FROM ordering.orders WHERE id = CAST(:id AS uuid)").param("id", id)
				.query(String.class).single();
	}

	private String membership(String add, String remove) {
		return "{\"addOrderIds\":[" + add + "],\"removeOrderIds\":[" + remove + "]}";
	}

	private static String q(String id) {
		return "\"" + id + "\"";
	}

	@Test
	void eligibleOrdersListOnlyReadyOrdersInScopeAndShrinkAsOrdersAreBatched() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String a = order(customer, w, 1000);
		String b = order(customer, w, 2000);

		String path = "/api/v1/dispatch/eligible-orders?zoneId=" + w.zoneId + "&limit=1";
		String first = TestApi.bodyOf(api.get(dispatcher, path).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").isNotEmpty()));
		String cursor = JsonPath.read(first, "$.nextCursor");
		api.get(dispatcher, path + "&cursor=" + cursor).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").doesNotExist())
				.andExpect(jsonPath("$.items[0].id").value(JsonPath.<String>read(first, "$.items[0].id").equals(a) ? b : a));

		batch(dispatcher, w, a);
		api.get(dispatcher, "/api/v1/dispatch/eligible-orders?zoneId=" + w.zoneId).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(b));

		api.get(customer, "/api/v1/dispatch/eligible-orders").andExpect(status().isForbidden());
		TestWorld foreign = TestWorld.create(jdbc, encoder, false);
		api.get(api.login(foreign.dispatcher), "/api/v1/dispatch/eligible-orders?zoneId=" + w.zoneId)
				.andExpect(status().isNotFound());
	}

	@Test
	void membershipChangesAdjustTotalsOrderStatusesAndVersion() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String a = order(customer, w, 1000);
		String b = order(customer, w, 2000);
		String c = order(customer, w, 4000);
		String batchId = batch(dispatcher, w, a);
		String path = "/api/v1/dispatch/batches/" + batchId + "/membership";

		api.post(dispatcher, path, membership(q(b), ""), "If-Match", "\"0\"").andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.data.orderCount").value(2))
				.andExpect(jsonPath("$.data.totalWeightG").value(3000));
		assertThat(orderStatus(b)).isEqualTo("BATCHED");

		api.post(dispatcher, path, membership(q(c), ""), "If-Match", "\"0\"").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		api.post(dispatcher, path, membership(q(b), ""), "If-Match", "\"1\"").andExpect(status().isConflict());
		api.post(dispatcher, path, membership("", q(c)), "If-Match", "\"1\"").andExpect(status().isConflict());
		api.post(dispatcher, path, membership(q(c), q(c)), "If-Match", "\"1\"").andExpect(status().isBadRequest());

		api.post(dispatcher, path, membership(q(c), q(a)), "If-Match", "\"1\"").andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.data.totalWeightG").value(6000));
		assertThat(orderStatus(a)).isEqualTo("READY_FOR_DISPATCH");
		assertThat(orderStatus(c)).isEqualTo("BATCHED");

		api.post(dispatcher, path, membership("", q(b) + "," + q(c)), "If-Match", "\"2\"")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND action = 'BATCH_MEMBERSHIP_CHANGED'")
				.param("id", batchId).query(Integer.class).single()).isEqualTo(2);

		api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"2\"")
				.andExpect(status().isOk());
		api.post(dispatcher, path, membership(q(a), ""), "If-Match", "\"3\"").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
	}

	@Test
	void concurrentMembershipChangesAllowOnlyOneWinnerPerOrder() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String a = order(customer, w, 1000);
		String b = order(customer, w, 1000);
		String shared = order(customer, w, 1000);
		String first = batch(dispatcher, w, a);
		String second = batch(dispatcher, w, b);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> toFirst = () -> api.post(dispatcher, "/api/v1/dispatch/batches/" + first + "/membership",
					membership(q(shared), ""), "If-Match", "\"0\"").andReturn().getResponse().getStatus();
			Callable<Integer> toSecond = () -> api.post(dispatcher, "/api/v1/dispatch/batches/" + second + "/membership",
					membership(q(shared), ""), "If-Match", "\"0\"").andReturn().getResponse().getStatus();
			Future<Integer> f1 = pool.submit(toFirst);
			Future<Integer> f2 = pool.submit(toSecond);
			assertThat(java.util.List.of(f1.get(), f2.get())).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM dispatch.batch_orders WHERE order_id = CAST(:id AS uuid) AND released_at IS NULL")
				.param("id", shared).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void reopenReturnsALockedBatchToDraftAndInvalidatesItsRecommendation() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String a = order(customer, w, 1000);
		String batchId = batch(dispatcher, w, a);
		String base = "/api/v1/dispatch/batches/" + batchId;

		api.post(dispatcher, base + "/reopen", "{\"reason\":\"x\"}", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isConflict());
		api.post(dispatcher, base + "/lock", "", "If-Match", "\"0\"").andExpect(status().isOk());
		String snapshotJson = TestApi.bodyOf(api.post(dispatcher, base + "/recommendations", "", "If-Match", "\"1\"")
				.andExpect(status().isCreated()));
		String snapshotId = JsonPath.read(snapshotJson, "$.data.snapshot.id");

		api.post(dispatcher, base + "/reopen", "{\"reason\":\" \"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isBadRequest());
		String reopenKey = key();
		api.post(dispatcher, base + "/reopen", "{\"reason\":\"Customer changed pickup\"}", "Idempotency-Key", reopenKey,
				"If-Match", "\"1\"").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
				.andExpect(jsonPath("$.data.plannedWindow").doesNotExist())
				.andExpect(header().string("ETag", "\"2\""));
		api.post(dispatcher, base + "/reopen", "{\"reason\":\"Customer changed pickup\"}", "Idempotency-Key", reopenKey,
				"If-Match", "\"1\"").andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));

		assertThat(jdbc.sql("SELECT status FROM dispatch.recommendation_snapshots WHERE id = CAST(:id AS uuid)")
				.param("id", snapshotId).query(String.class).single()).isEqualTo("INVALIDATED");
		assertThat(orderStatus(a)).isEqualTo("BATCHED");

		api.post(dispatcher, base + "/lock", "", "If-Match", "\"2\"").andExpect(status().isOk());
		api.post(dispatcher, base + "/recommendations", "", "If-Match", "\"3\"").andExpect(status().isCreated());
	}

	@Test
	void reopenAndCancelAreRefusedOnceADriverIsAssigned() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String a = order(customer, w, 1000);
		String batchId = batch(dispatcher, w, a);
		String base = "/api/v1/dispatch/batches/" + batchId;
		api.post(dispatcher, base + "/lock", "", "If-Match", "\"0\"").andExpect(status().isOk());
		String json = TestApi.bodyOf(api.post(dispatcher, base + "/recommendations", "", "If-Match", "\"1\"")
				.andExpect(status().isCreated()));
		String decisionId = JsonPath.read(json, "$.data.decisionId");
		String snapshotId = JsonPath.read(json, "$.data.snapshot.id");
		api.post(dispatcher, "/api/v1/dispatch/decisions/" + decisionId + "/assign",
				"{\"snapshotId\":\"" + snapshotId + "\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isOk());

		api.post(dispatcher, "/api/v1/orders/" + a + "/cancel", "{\"reason\":\"Customer asked\"}",
				"Idempotency-Key", key(), "If-Match", "\"" + orderVersion(a) + "\"").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
		api.post(dispatcher, base + "/reopen", "{\"reason\":\"Change\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
		api.post(dispatcher, base + "/cancel", "{\"reason\":\"Change\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
	}

	@Test
	void cancellingABatchReleasesItsOrdersAndAllowsStaffToCancelThem() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String a = order(customer, w, 1000);
		String b = order(customer, w, 1000);
		String batchId = batch(dispatcher, w, a, b);
		String base = "/api/v1/dispatch/batches/" + batchId;
		api.post(dispatcher, base + "/lock", "", "If-Match", "\"0\"").andExpect(status().isOk());
		api.post(dispatcher, base + "/recommendations", "", "If-Match", "\"1\"").andExpect(status().isCreated());

		api.post(dispatcher, base + "/cancel", "{\"reason\":\"\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isBadRequest());
		api.post(customer, base + "/cancel", "{\"reason\":\"No\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isForbidden());
		api.post(dispatcher, base + "/cancel", "{\"reason\":\"Zone closed\"}", "Idempotency-Key", key(), "If-Match",
				"\"1\"").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
				.andExpect(jsonPath("$.data.capabilities").isEmpty());

		assertThat(orderStatus(a)).isEqualTo("READY_FOR_DISPATCH");
		assertThat(orderStatus(b)).isEqualTo("READY_FOR_DISPATCH");
		assertThat(jdbc.sql("SELECT count(*) FROM dispatch.batch_orders WHERE batch_id = CAST(:id AS uuid) AND released_at IS NULL")
				.param("id", batchId).query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT status FROM dispatch.dispatch_decisions WHERE batch_id = CAST(:id AS uuid)")
				.param("id", batchId).query(String.class).single()).isEqualTo("CANCELLED");

		api.post(dispatcher, "/api/v1/orders/" + a + "/cancel", "{\"reason\":\"Customer asked\"}",
				"Idempotency-Key", key(), "If-Match", "\"" + orderVersion(a) + "\"").andExpect(status().isOk());
		String again = batch(dispatcher, w, b);
		assertThat(again).isNotEqualTo(batchId);

		api.get(dispatcher, "/api/v1/dispatch/batches?zoneId=" + w.zoneId + "&status=CANCELLED").andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(batchId));
	}

	@Test
	void staffCancellingABatchedOrderDetachesItAndCancelsTheBatchWhenItWasTheLastOne() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String a = order(customer, w, 1000);
		String b = order(customer, w, 2000);
		String batchId = batch(dispatcher, w, a, b);
		String base = "/api/v1/dispatch/batches/" + batchId;
		api.post(dispatcher, base + "/lock", "", "If-Match", "\"0\"").andExpect(status().isOk());
		api.post(dispatcher, base + "/recommendations", "", "If-Match", "\"1\"").andExpect(status().isCreated());

		api.post(dispatcher, "/api/v1/orders/" + a + "/cancel", "{\"reason\":\"Customer asked\"}",
				"Idempotency-Key", key(), "If-Match", "\"" + orderVersion(a) + "\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("CANCELLED"));
		assertThat(orderStatus(a)).isEqualTo("CANCELLED");
		api.get(dispatcher, base).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.orderCount").value(1)).andExpect(jsonPath("$.totalWeightG").value(2000));

		api.post(dispatcher, "/api/v1/orders/" + b + "/cancel", "{\"reason\":\"Customer asked\"}",
				"Idempotency-Key", key(), "If-Match", "\"" + orderVersion(b) + "\"").andExpect(status().isOk());
		api.get(dispatcher, base).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(jdbc.sql("SELECT count(*) FROM dispatch.batch_orders WHERE batch_id = CAST(:id AS uuid) AND released_at IS NULL")
				.param("id", batchId).query(Integer.class).single()).isZero();
	}

	private long orderVersion(String id) {
		return jdbc.sql("SELECT version FROM ordering.orders WHERE id = CAST(:id AS uuid)").param("id", id)
				.query(Long.class).single();
	}
}
