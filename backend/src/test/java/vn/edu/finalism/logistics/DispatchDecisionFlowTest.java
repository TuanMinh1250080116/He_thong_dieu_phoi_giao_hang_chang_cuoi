package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.TestWorld.DriverRef;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class DispatchDecisionFlowTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	vn.edu.finalism.logistics.platform.event.OutboxRelay relay;

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

	private record Prepared(String orderId, String batchId, String decisionId, String snapshotId) {
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private Prepared prepare(TestWorld w, String customerToken, String dispatcherToken) throws Exception {
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
		String orderId = JsonPath.read(TestApi.bodyOf(api.post(customerToken, "/api/v1/orders", body,
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.data.id");
		String batchBody = "{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + orderId + "\"]}";
		String batchId = JsonPath.read(TestApi.bodyOf(api.post(dispatcherToken, "/api/v1/dispatch/batches", batchBody,
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.data.id");
		api.post(dispatcherToken, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"")
				.andExpect(status().isOk());
		String json = TestApi.bodyOf(api.post(dispatcherToken, "/api/v1/dispatch/batches/" + batchId + "/recommendations",
				"", "If-Match", "\"1\"").andExpect(status().isCreated()));
		return new Prepared(orderId, batchId, JsonPath.read(json, "$.data.decisionId"),
				JsonPath.read(json, "$.data.snapshot.id"));
	}

	private ResultActions assign(String token, Prepared p, String extra, String version) throws Exception {
		return api.post(token, "/api/v1/dispatch/decisions/" + p.decisionId() + "/assign",
				"{\"snapshotId\":\"" + p.snapshotId() + "\"" + extra + "}", "Idempotency-Key", key(), "If-Match",
				"\"" + version + "\"");
	}

	private String text(String sql, Object id) {
		return jdbc.sql(sql).param("id", id).query(String.class).single();
	}

	@Test
	void driverRejectsThenDispatcherReassignsToAnotherDriver() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		DriverRef second = w.addDriver();
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String firstDriver = api.login(w.driverUser);
		String secondDriver = api.login(second.user());
		Prepared p = prepare(w, customer, dispatcher);

		String assignmentId = JsonPath.read(TestApi.bodyOf(assign(dispatcher, p, "", "1").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.driverId").value(w.driverId.toString()))), "$.data.id");

		api.post(firstDriver, "/api/v1/assignments/" + assignmentId + "/reject", "{\"reasonCode\":\"OTHER\"}",
				"Idempotency-Key", key(), "If-Match", "\"2\"")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		api.post(firstDriver, "/api/v1/assignments/" + assignmentId + "/reject",
				"{\"reasonCode\":\"SCHEDULE_CONFLICT\"}", "Idempotency-Key", key(), "If-Match", "\"2\"")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"))
				.andExpect(header().string("ETag", "\"3\""));

		assertThat(text("SELECT status FROM delivery.deliveries WHERE decision_id = CAST(:id AS uuid)", p.decisionId()))
				.isEqualTo("AWAITING_REASSIGNMENT");
		assertThat(text("SELECT status FROM dispatch.dispatch_decisions WHERE id = CAST(:id AS uuid)", p.decisionId()))
				.isEqualTo("AWAITING_REASSIGNMENT");
		assertThat(text("SELECT availability FROM fleet.drivers WHERE id = :id", w.driverId)).isEqualTo("AVAILABLE");
		assertThat(jdbc.sql("SELECT count(*) FROM dispatch.driver_commitments WHERE status = 'ACTIVE' AND driver_id = :id")
				.param("id", w.driverId).query(Integer.class).single()).isZero();

		api.get(dispatcher, "/api/v1/dispatch/batches/" + p.batchId() + "/decision")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("AWAITING_REASSIGNMENT"))
				.andExpect(jsonPath("$.data.assignment.status").value("REJECTED"))
				.andExpect(jsonPath("$.data.assignment.rejectionReasonCode").value("SCHEDULE_CONFLICT"))
				.andExpect(jsonPath("$.capabilities[0]").value("canReassign"))
				.andExpect(header().string("ETag", "\"3\""));

		api.post(dispatcher, "/api/v1/dispatch/decisions/" + p.decisionId() + "/reassign",
				"{\"driverId\":\"" + second.driverId() + "\",\"reason\":\" \"}", "Idempotency-Key", key(), "If-Match",
				"\"3\"").andExpect(status().isBadRequest());
		String newAssignment = JsonPath.read(TestApi.bodyOf(api.post(dispatcher,
				"/api/v1/dispatch/decisions/" + p.decisionId() + "/reassign",
				"{\"driverId\":\"" + second.driverId() + "\",\"reason\":\"First driver declined\"}",
				"Idempotency-Key", key(), "If-Match", "\"3\"")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.kind").value("REASSIGN"))
				.andExpect(jsonPath("$.data.status").value("PENDING"))
				.andExpect(header().string("ETag", "\"4\""))), "$.data.id");

		assertThat(text("SELECT status FROM dispatch.driver_assignments WHERE id = CAST(:id AS uuid)", assignmentId))
				.isEqualTo("REJECTED");
		assertThat(text("SELECT availability FROM fleet.drivers WHERE id = :id", second.driverId()))
				.isEqualTo("RESERVED");
		assertThat(text("SELECT assigned_driver_id::text FROM delivery.deliveries WHERE decision_id = CAST(:id AS uuid)",
				p.decisionId())).isEqualTo(second.driverId().toString());

		api.post(firstDriver, "/api/v1/assignments/" + newAssignment + "/accept", "", "Idempotency-Key", key(),
				"If-Match", "\"4\"").andExpect(status().isNotFound());
		api.post(secondDriver, "/api/v1/assignments/" + newAssignment + "/accept", "", "Idempotency-Key", key(),
				"If-Match", "\"4\"").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));

		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND action = 'DRIVER_REASSIGNED'")
				.param("id", p.decisionId()).query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND action = 'DRIVER_ASSIGNMENT_REJECTED'")
				.param("id", assignmentId).query(Integer.class).single()).isEqualTo(1);

		relay.dispatchBatch();
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			List<String> dispatcherTitles = com.jayway.jsonpath.JsonPath
					.read(TestApi.bodyOf(api.get(dispatcher, "/api/v1/notifications")), "$.items[*].title");
			assertThat(dispatcherTitles).contains("Tài xế đã từ chối chuyến", "Tài xế đã nhận chuyến");
		});
	}

	@Test
	void reassignBeforeStartSupersedesTheCurrentAssignment() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		DriverRef second = w.addDriver();
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		Prepared p = prepare(w, customer, dispatcher);
		String first = JsonPath.read(TestApi.bodyOf(assign(dispatcher, p, "", "1").andExpect(status().isOk())),
				"$.data.id");

		api.post(dispatcher, "/api/v1/dispatch/decisions/" + p.decisionId() + "/reassign",
				"{\"driverId\":\"" + w.driverId + "\",\"reason\":\"Same driver\"}", "Idempotency-Key", key(),
				"If-Match", "\"2\"").andExpect(status().isBadRequest());
		api.post(dispatcher, "/api/v1/dispatch/decisions/" + p.decisionId() + "/reassign",
				"{\"driverId\":\"" + second.driverId() + "\",\"reason\":\"Vehicle breakdown\"}", "Idempotency-Key",
				key(), "If-Match", "\"2\"").andExpect(status().isOk());

		assertThat(text("SELECT status FROM dispatch.driver_assignments WHERE id = CAST(:id AS uuid)", first))
				.isEqualTo("SUPERSEDED");
		assertThat(text("SELECT availability FROM fleet.drivers WHERE id = :id", w.driverId)).isEqualTo("AVAILABLE");
		assertThat(text("SELECT availability FROM fleet.drivers WHERE id = :id", second.driverId()))
				.isEqualTo("RESERVED");
	}

	@Test
	void overrideNeedsThePermissionAReasonAndAnEligibleDriver() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		DriverRef second = w.addDriver();
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		Prepared p = prepare(w, customer, dispatcher);
		String pick = ",\"driverId\":\"" + second.driverId() + "\"";

		assign(dispatcher, p, pick + ",\"overrideReason\":\"Closer to the customer\"", "1")
				.andExpect(status().isForbidden());

		w.grantRole(w.dispatcher, "DISPATCH_OVERRIDER");
		assign(dispatcher, p, pick, "1").andExpect(status().isBadRequest());
		assign(dispatcher, p, pick + ",\"overrideReason\":\"  \"", "1").andExpect(status().isBadRequest());

		jdbc.sql("UPDATE fleet.drivers SET availability = 'OFFLINE' WHERE id = :id").param("id", second.driverId())
				.update();
		assign(dispatcher, p, pick + ",\"overrideReason\":\"Closer to the customer\"", "1")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_INELIGIBLE"));
		jdbc.sql("UPDATE fleet.drivers SET availability = 'AVAILABLE' WHERE id = :id").param("id", second.driverId())
				.update();

		String assignmentId = JsonPath.read(TestApi.bodyOf(assign(dispatcher, p,
				pick + ",\"overrideReason\":\"Closer to the customer\"", "1")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.kind").value("OVERRIDE"))
				.andExpect(jsonPath("$.data.driverId").value(second.driverId().toString()))), "$.data.id");

		assertThat(text("SELECT override_reason FROM dispatch.driver_assignments WHERE id = CAST(:id AS uuid)",
				assignmentId)).isEqualTo("Closer to the customer");
		assertThat(text("SELECT recommended_driver_id::text FROM dispatch.driver_assignments WHERE id = CAST(:id AS uuid)",
				assignmentId)).isEqualTo(w.driverId.toString());
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND action = 'DRIVER_ASSIGNMENT_OVERRIDDEN'")
				.param("id", p.decisionId()).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void choosingTheTopDriverWithoutAReasonIsATrivialAssign() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		Prepared p = prepare(w, customer, dispatcher);

		assign(dispatcher, p, ",\"driverId\":\"" + w.driverId + "\"", "1")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.kind").value("ACCEPT_RECOMMENDATION"));
	}

	@Test
	void cancellingADispatchReturnsEverythingToThePool() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		Prepared p = prepare(w, customer, dispatcher);
		assign(dispatcher, p, "", "1").andExpect(status().isOk());

		api.post(dispatcher, "/api/v1/dispatch/decisions/" + p.decisionId() + "/cancel", "{\"reason\":\" \"}",
				"If-Match", "\"2\"").andExpect(status().isBadRequest());
		api.post(dispatcher, "/api/v1/dispatch/decisions/" + p.decisionId() + "/cancel",
				"{\"reason\":\"Customer changed the plan\"}", "If-Match", "\"2\"")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));

		assertThat(text("SELECT status FROM delivery.deliveries WHERE decision_id = CAST(:id AS uuid)", p.decisionId()))
				.isEqualTo("CANCELLED");
		assertThat(text("SELECT status FROM ordering.orders WHERE id = CAST(:id AS uuid)", p.orderId()))
				.isEqualTo("BATCHED");
		assertThat(text("SELECT availability FROM fleet.drivers WHERE id = :id", w.driverId)).isEqualTo("AVAILABLE");
		assertThat(text("SELECT status FROM dispatch.dispatch_decisions WHERE id = CAST(:id AS uuid)", p.decisionId()))
				.isEqualTo("CANCELLED");

		api.post(dispatcher, "/api/v1/dispatch/batches/" + p.batchId() + "/recommendations", "", "If-Match", "\"1\"")
				.andExpect(status().isCreated());
	}

	@Test
	void twoDispatchersCannotBookTheSameDriverTwice() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		Prepared one = prepare(w, customer, dispatcher);
		Prepared two = prepare(w, customer, dispatcher);

		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> results = List.of(
					pool.submit(race(go, dispatcher, one)),
					pool.submit(race(go, dispatcher, two)));
			go.countDown();
			int ok = 0;
			int conflict = 0;
			for (Future<Integer> result : results) {
				int code = result.get();
				if (code == 200) {
					ok++;
				}
				else if (code == 409) {
					conflict++;
				}
			}
			assertThat(ok).isEqualTo(1);
			assertThat(conflict).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM dispatch.driver_commitments WHERE status = 'ACTIVE' AND driver_id = :id")
				.param("id", w.driverId).query(Integer.class).single()).isEqualTo(1);
	}

	private Callable<Integer> race(CountDownLatch go, String token, Prepared p) {
		return () -> {
			go.await();
			return assign(token, p, "", "1").andReturn().getResponse().getStatus();
		};
	}
	@Test
	void aDriverListsOwnDeliveriesWithFiltersAndNoOneElseDoes() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String driver = api.login(w.driverUser);
		Prepared p = prepare(w, customer, dispatcher);
		assign(dispatcher, p, "", "1").andExpect(status().isOk());

		api.get(driver, "/api/v1/me/deliveries").andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].status").value("ASSIGNED"))
				.andExpect(jsonPath("$.items[0].assignmentStatus").value("PENDING"))
				.andExpect(jsonPath("$.items[0].stopCount").value(1))
				.andExpect(jsonPath("$.items[0].batchCode").isNotEmpty());
		api.get(driver, "/api/v1/me/deliveries?status=COMPLETED&status=CANCELLED").andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
		api.get(driver, "/api/v1/me/deliveries?status=BOGUS").andExpect(status().isBadRequest());
		api.get(driver, "/api/v1/me/deliveries?from=" + Instant.now().plus(Duration.ofDays(1)))
				.andExpect(jsonPath("$.items.length()").value(0));
		String deliveryId = JsonPath.read(TestApi.bodyOf(api.get(driver, "/api/v1/me/deliveries")), "$.items[0].id");
		api.get(driver, "/api/v1/me/deliveries/" + deliveryId).andExpect(status().isOk())
				.andExpect(jsonPath("$.stops.length()").value(1))
				.andExpect(jsonPath("$.stops[0].orderCode").isNotEmpty())
				.andExpect(jsonPath("$.vehicleCode").isNotEmpty())
				.andExpect(jsonPath("$.assignmentStatus").value("PENDING"));
		api.get(api.login(w.addDriver().user()), "/api/v1/me/deliveries/" + deliveryId).andExpect(status().isNotFound());
		api.get(dispatcher, "/api/v1/me/deliveries").andExpect(status().isForbidden());
		api.get(api.login(w.addDriver().user()), "/api/v1/me/deliveries").andExpect(jsonPath("$.items.length()").value(0));
	}
}
