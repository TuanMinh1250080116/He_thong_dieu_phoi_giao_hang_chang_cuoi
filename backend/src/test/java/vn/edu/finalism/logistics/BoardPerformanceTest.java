package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class BoardPerformanceTest {

	static final int HISTORICAL_ORDERS = 100_000;
	static final int READY_ORDERS = 2_000;
	static final int SESSIONS = 30;
	static final int REQUESTS_PER_SESSION = 10;

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

	private void seedOrders(TestWorld w, String status, int count, String prefix) {
		jdbc.sql("""
				INSERT INTO ordering.orders
				  (id, code, customer_id, status, pickup_address, pickup_point, dropoff_address, dropoff_point,
				   recipient_name, weight_g, volume_cm3, window_start, window_end, zone_id, cancelled_from_status,
				   created_at, updated_at)
				SELECT gen_random_uuid(), :prefix || g, :customer, :status, 'Pickup ' || g,
				       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 'Dropoff ' || g,
				       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 'Recipient', 1500, 20000,
				       now() + (g % 600) * interval '1 minute', now() + (g % 600) * interval '1 minute' + interval '4 hours',
				       :zone, NULL, now() - (g % 100000) * interval '1 second', now()
				FROM generate_series(1, :n) g
				""")
				.param("prefix", prefix).param("customer", w.customer.id()).param("status", status)
				.param("lng", w.lng0 + 0.01).param("lat", w.lat0 + 0.01).param("zone", w.zoneId).param("n", count)
				.update();
	}

	private void seedBatches(TestWorld w, String status, int count, String prefix) {
		String lockFields = "LOCKED".equals(status) || "COMPLETED".equals(status)
				? "now(), tstzrange(now(), now() + interval '4 hours', '[)')" : "NULL, NULL";
		jdbc.sql("""
				INSERT INTO dispatch.delivery_batches
				  (id, code, zone_id, status, locked_at, planned_window, order_count, total_weight_g, total_volume_cm3,
				   created_by, created_at, updated_at)
				SELECT gen_random_uuid(), :prefix || g, :zone, :status, %s, 5, 7500, 100000, :by,
				       now() - g * interval '1 second', now()
				FROM generate_series(1, :n) g
				""".formatted(lockFields))
				.param("prefix", prefix).param("zone", w.zoneId).param("status", status).param("by", w.dispatcher.id())
				.param("n", count).update();
	}

	private static long percentile(List<Long> sorted, double p) {
		return sorted.get((int) Math.ceil(sorted.size() * p) - 1);
	}

	@Test
	void dispatchBoardMeetsTheLatencyTargetUnderThirtyConcurrentSessions() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String tag = w.zoneId.toString().substring(0, 8);
		seedOrders(w, "DELIVERED", HISTORICAL_ORDERS, "PH-" + tag + "-");
		seedOrders(w, "READY_FOR_DISPATCH", READY_ORDERS, "PR-" + tag + "-");
		seedBatches(w, "DRAFT", 300, "BD-" + tag + "-");
		seedBatches(w, "LOCKED", 300, "BL-" + tag + "-");
		seedBatches(w, "COMPLETED", 2000, "BC-" + tag + "-");
		jdbc.sql("ANALYZE").update();
		String token = api.login(w.dispatcher);

		String[] paths = {
				"/api/v1/dispatch/board",
				"/api/v1/dispatch/board?q=PR-" + tag + "-15",
				"/api/v1/dispatch/batches?status=LOCKED&limit=25",
				"/api/v1/dispatch/batches?q=BC-" + tag + "-19&limit=25",
				"/api/v1/dispatch/eligible-orders?limit=50"};
		for (String path : paths) {
			api.get(token, path).andExpect(status().isOk());
		}

		ExecutorService pool = Executors.newFixedThreadPool(SESSIONS);
		List<Future<List<Long>>> futures = new ArrayList<>();
		try {
			for (int s = 0; s < SESSIONS; s++) {
				final int session = s;
				Callable<List<Long>> run = () -> {
					List<Long> times = new ArrayList<>();
					for (int r = 0; r < REQUESTS_PER_SESSION; r++) {
						String path = paths[(session + r) % paths.length];
						long start = System.nanoTime();
						int code = api.get(token, path).andReturn().getResponse().getStatus();
						times.add((System.nanoTime() - start) / 1_000_000);
						assertThat(code).isEqualTo(200);
					}
					return times;
				};
				futures.add(pool.submit(run));
			}
			List<Long> all = new ArrayList<>();
			for (Future<List<Long>> f : futures) {
				all.addAll(f.get());
			}
			Collections.sort(all);
			writeExplain(w, percentile(all, 0.5), percentile(all, 0.95), percentile(all, 0.99));
			assertThat(percentile(all, 0.95)).as("p95 ms").isLessThanOrEqualTo(1500);
		}
		finally {
			pool.shutdownNow();
		}
	}

	private void writeExplain(TestWorld w, long p50, long p95, long p99) throws Exception {
		StringBuilder out = new StringBuilder();
		out.append("PERF-02 dispatch board: orders=").append(HISTORICAL_ORDERS + READY_ORDERS)
				.append(" batches=2600 sessions=").append(SESSIONS).append(" p50=").append(p50).append("ms p95=")
				.append(p95).append("ms p99=").append(p99).append("ms\n\n");
		String[] queries = {
				"SELECT o.id, o.code FROM ordering.orders o WHERE o.status = 'READY_FOR_DISPATCH' AND o.zone_id = '"
						+ w.zoneId + "' ORDER BY o.window_start, o.id LIMIT 20",
				"SELECT count(*) FROM ordering.orders o WHERE o.status = 'READY_FOR_DISPATCH' AND o.zone_id = '"
						+ w.zoneId + "'",
				"SELECT b.id FROM dispatch.delivery_batches b WHERE b.zone_id = '" + w.zoneId
						+ "' AND b.status = 'LOCKED' ORDER BY b.created_at DESC, b.id DESC LIMIT 26"};
		for (String query : queries) {
			out.append("EXPLAIN (ANALYZE, BUFFERS) ").append(query).append('\n');
			jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + query).query(String.class).list()
					.forEach(line -> out.append(line).append('\n'));
			out.append('\n');
		}
		Path dir = Path.of("target", "perf-results");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("board-explain.txt"), out.toString());
	}

	@Test
	void contendingAssignmentsOfTheSameDriverProduceExactlyOneWinner() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		int contenders = 8;
		List<String[]> prepared = new ArrayList<>();
		Instant now = Instant.now();
		for (int i = 0; i < contenders; i++) {
			String body = """
					{"saveMode":"SUBMIT",
					 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
					 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
					 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
					 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
					 "timeWindow":{"start":"%s","end":"%s"}}
					""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(),
					now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
			String orderId = JsonPath.read(TestApi.bodyOf(api.post(customer, "/api/v1/orders", body,
					"Idempotency-Key", UUID.randomUUID().toString()).andExpect(status().isCreated())), "$.data.id");
			String batchId = JsonPath.read(TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches",
					"{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + orderId + "\"]}", "Idempotency-Key",
					UUID.randomUUID().toString()).andExpect(status().isCreated())), "$.data.id");
			api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"")
					.andExpect(status().isOk());
			String json = TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations",
					"", "If-Match", "\"1\"").andExpect(status().isCreated()));
			prepared.add(new String[] {JsonPath.read(json, "$.data.decisionId"),
					JsonPath.read(json, "$.data.snapshot.id")});
		}

		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		List<Integer> codes = new ArrayList<>();
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			CountDownLatch gate = new CountDownLatch(1);
			for (String[] p : prepared) {
				futures.add(pool.submit(() -> {
					gate.await();
					return api.post(dispatcher, "/api/v1/dispatch/decisions/" + p[0] + "/assign",
							"{\"snapshotId\":\"" + p[1] + "\"}", "Idempotency-Key", UUID.randomUUID().toString(),
							"If-Match", "\"1\"").andReturn().getResponse().getStatus();
				}));
			}
			gate.countDown();
			for (Future<Integer> f : futures) {
				codes.add(f.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
		assertThat(codes.stream().filter(c -> c >= 500)).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM dispatch.driver_commitments WHERE driver_id = :id AND status = 'ACTIVE'")
				.param("id", w.driverId).query(Integer.class).single()).isEqualTo(1);
	}
}
