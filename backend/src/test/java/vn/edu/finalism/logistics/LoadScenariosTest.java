package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
class LoadScenariosTest {

	static final AtomicInteger REQUESTS = new AtomicInteger();
	static final AtomicInteger SERVER_ERRORS = new AtomicInteger();

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

	private record Flow(TestWorld world, String customer, String dispatcher, String orderId, String batchId,
			String decisionId, String snapshotId, DriverRef spare) {
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private static long percentile(List<Long> sorted, double p) {
		return sorted.get((int) Math.ceil(sorted.size() * p) - 1);
	}

	private int call(ResultActions result) throws Exception {
		int code = result.andReturn().getResponse().getStatus();
		REQUESTS.incrementAndGet();
		if (code >= 500) {
			SERVER_ERRORS.incrementAndGet();
		}
		return code;
	}

	private String newOrder(String customer, TestWorld w) throws Exception {
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

	private String lockedBatch(String dispatcher, TestWorld w, String orderId) throws Exception {
		String batchId = JsonPath.read(TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches",
				"{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + orderId + "\"]}", "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
		api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"")
				.andExpect(status().isOk());
		return batchId;
	}

	private Flow prepareFlow() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		DriverRef spare = w.addDriver();
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String orderId = newOrder(customer, w);
		String batchId = lockedBatch(dispatcher, w, orderId);
		String json = TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations", "",
				"If-Match", "\"1\"").andExpect(status().isCreated()));
		return new Flow(w, customer, dispatcher, orderId, batchId, JsonPath.read(json, "$.data.decisionId"),
				JsonPath.read(json, "$.data.snapshot.id"), spare);
	}

	private <T> List<T> parallel(int threads, List<Callable<T>> jobs) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> job : jobs) {
				futures.add(pool.submit(job));
			}
			List<T> out = new ArrayList<>();
			for (Future<T> f : futures) {
				out.add(f.get());
			}
			return out;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void record(String line) throws Exception {
		Path dir = Path.of("target", "perf-results");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("load.txt"), line + System.lineSeparator(), StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
	}

	@Test
	void assignReassignAndReadDetailsStayWithinTheirLatencyTargetsAndTheErrorRateStaysLow() throws Exception {
		int flows = 20;
		List<Flow> prepared = new ArrayList<>();
		for (int i = 0; i < flows; i++) {
			prepared.add(prepareFlow());
		}

		List<Callable<long[]>> assignJobs = new ArrayList<>();
		for (Flow f : prepared) {
			assignJobs.add(() -> {
				long start = System.nanoTime();
				ResultActions result = api.post(f.dispatcher(), "/api/v1/dispatch/decisions/" + f.decisionId() + "/assign",
						"{\"snapshotId\":\"" + f.snapshotId() + "\"}", "Idempotency-Key", key(), "If-Match", "\"1\"");
				long ms = (System.nanoTime() - start) / 1_000_000;
				assertThat(call(result)).isEqualTo(200);
				return new long[] {ms, Long.parseLong(TestApi.etagOf(result).replace("\"", ""))};
			});
		}
		List<long[]> assigned = parallel(10, assignJobs);
		List<Long> assignMillis = new ArrayList<>(assigned.stream().map(a -> a[0]).toList());
		Collections.sort(assignMillis);

		List<Callable<Long>> reassignJobs = new ArrayList<>();
		for (int i = 0; i < flows; i++) {
			Flow f = prepared.get(i);
			long version = assigned.get(i)[1];
			reassignJobs.add(() -> {
				long start = System.nanoTime();
				int code = call(api.post(f.dispatcher(), "/api/v1/dispatch/decisions/" + f.decisionId() + "/reassign",
						"{\"driverId\":\"" + f.spare().driverId() + "\",\"reason\":\"Load test\"}", "Idempotency-Key", key(),
						"If-Match", "\"" + version + "\""));
				assertThat(code).isEqualTo(200);
				return (System.nanoTime() - start) / 1_000_000;
			});
		}
		List<Long> reassignMillis = new ArrayList<>(parallel(10, reassignJobs));
		Collections.sort(reassignMillis);

		List<Callable<Long>> reads = new ArrayList<>();
		for (int r = 0; r < 200; r++) {
			Flow f = prepared.get(r % flows);
			String path = switch (r % 3) {
				case 0 -> "/api/v1/orders/" + f.orderId();
				case 1 -> "/api/v1/dispatch/batches/" + f.batchId();
				default -> "/api/v1/dispatch/batches/" + f.batchId() + "/decision";
			};
			String token = r % 3 == 0 ? f.customer() : f.dispatcher();
			reads.add(() -> {
				long start = System.nanoTime();
				int code = call(api.get(token, path));
				assertThat(code).isEqualTo(200);
				return (System.nanoTime() - start) / 1_000_000;
			});
		}
		List<Long> readMillis = new ArrayList<>(parallel(20, reads));
		Collections.sort(readMillis);

		double errorRate = (double) SERVER_ERRORS.get() / Math.max(1, REQUESTS.get());
		record("PERF-03 assign n=" + flows + " p95=" + percentile(assignMillis, 0.95) + "ms; reassign p95="
				+ percentile(reassignMillis, 0.95) + "ms");
		record("PERF-04 read detail n=200 p95=" + percentile(readMillis, 0.95) + "ms");
		record("PERF-05 requests=" + REQUESTS.get() + " serverErrors=" + SERVER_ERRORS.get());

		assertThat(percentile(assignMillis, 0.95)).as("assign p95").isLessThanOrEqualTo(1000);
		assertThat(percentile(reassignMillis, 0.95)).as("reassign p95").isLessThanOrEqualTo(1000);
		assertThat(percentile(readMillis, 0.95)).as("read p95").isLessThanOrEqualTo(1000);
		assertThat(errorRate).as("server error rate").isLessThan(0.01);
	}

	@Test
	void stressWithAThousandDriversAndFiveThousandReadyOrdersKeepsDataConsistent() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String tag = w.zoneId.toString().substring(0, 8);
		jdbc.sql("""
				INSERT INTO iam.users (id, email, display_name, password_hash, status, created_at, updated_at)
				SELECT gen_random_uuid(), 'stress-' || :tag || '-' || g || '@test.local', 'Stress driver ' || g,
				       'x', 'ACTIVE', now(), now()
				FROM generate_series(1, 1000) g
				""").param("tag", tag).update();
		jdbc.sql("""
				INSERT INTO fleet.vehicles (id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3, created_at, updated_at)
				SELECT gen_random_uuid(), 'SV-' || :tag || '-' || g, 'VAN', 200000, 2000000000, now(), now()
				FROM generate_series(1, 1000) g
				""").param("tag", tag).update();
		jdbc.sql("""
				INSERT INTO fleet.drivers (id, user_id, code, full_name, availability, vehicle_id, created_at, updated_at)
				SELECT gen_random_uuid(), u.id, 'SD-' || :tag || '-' || u.rn, u.display_name, 'AVAILABLE', v.id, now(), now()
				FROM (SELECT id, display_name, row_number() OVER (ORDER BY email) AS rn
				      FROM iam.users WHERE email LIKE 'stress-' || :tag || '-%') u
				JOIN (SELECT id, row_number() OVER (ORDER BY code) AS rn
				      FROM fleet.vehicles WHERE code LIKE 'SV-' || :tag || '-%') v ON v.rn = u.rn
				""").param("tag", tag).update();
		jdbc.sql("INSERT INTO fleet.driver_zones (driver_id, zone_id) SELECT id, :zone FROM fleet.drivers WHERE code LIKE 'SD-' || :tag || '-%'")
				.param("tag", tag).param("zone", w.zoneId).update();
		jdbc.sql("""
				INSERT INTO fleet.driver_locations (driver_id, location, accuracy_m, recorded_at)
				SELECT id, ST_SetSRID(ST_MakePoint(:lng + random() * 0.09, :lat + random() * 0.09), 4326)::geography, 5, now()
				FROM fleet.drivers WHERE code LIKE 'SD-' || :tag || '-%'
				""").param("tag", tag).param("lng", w.lng0).param("lat", w.lat0).update();
		jdbc.sql("""
				INSERT INTO ordering.orders
				  (id, code, customer_id, status, pickup_address, pickup_point, dropoff_address, dropoff_point,
				   recipient_name, weight_g, volume_cm3, window_start, window_end, zone_id, created_at, updated_at)
				SELECT gen_random_uuid(), 'SR-' || :tag || '-' || g, :customer, 'READY_FOR_DISPATCH', 'Pickup ' || g,
				       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 'Dropoff ' || g,
				       ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 'Recipient', 1500, 20000,
				       now() + interval '1 hour', now() + interval '5 hours', :zone, now(), now()
				FROM generate_series(1, 5000) g
				""").param("tag", tag).param("customer", w.customer.id()).param("lng", w.lng0 + 0.01)
				.param("lat", w.lat0 + 0.01).param("zone", w.zoneId).update();
		jdbc.sql("ANALYZE").update();

		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		List<String> batches = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			batches.add(lockedBatch(dispatcher, w, newOrder(customer, w)));
		}

		List<Callable<long[]>> jobs = new ArrayList<>();
		for (String batchId : batches) {
			jobs.add(() -> {
				long start = System.nanoTime();
				int code = call(api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations", "",
						"If-Match", "\"1\""));
				return new long[] {(System.nanoTime() - start) / 1_000_000, code};
			});
		}
		for (int s = 0; s < 60; s++) {
			final int session = s;
			jobs.add(() -> {
				long start = System.nanoTime();
				int code = call(api.get(dispatcher, session % 2 == 0 ? "/api/v1/dispatch/board"
						: "/api/v1/dispatch/eligible-orders?limit=50"));
				return new long[] {(System.nanoTime() - start) / 1_000_000, code};
			});
		}
		List<long[]> results = parallel(80, jobs);
		List<Long> recommendationMillis = new ArrayList<>(results.subList(0, 20).stream().map(r -> r[0]).toList());
		Collections.sort(recommendationMillis);
		List<Long> boardMillis = new ArrayList<>(results.subList(20, results.size()).stream().map(r -> r[0]).toList());
		Collections.sort(boardMillis);
		record("PERF-06 stress drivers=1000 ready=5000 recommendations=20 concurrent, sessions=60: recommendation p95="
				+ percentile(recommendationMillis, 0.95) + "ms p99=" + percentile(recommendationMillis, 0.99)
				+ "ms; board/eligible p95=" + percentile(boardMillis, 0.95) + "ms");

		assertThat(results.stream().filter(r -> r[1] >= 500)).isEmpty();
		assertThat(results.subList(0, 20).stream().filter(r -> r[1] == 201).count()).isEqualTo(20);
		assertThat(jdbc.sql("SELECT count(*) FROM (SELECT order_id FROM dispatch.batch_orders WHERE released_at IS NULL GROUP BY order_id HAVING count(*) > 1) t")
				.query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM (SELECT decision_id FROM dispatch.recommendation_snapshots WHERE status = 'ACTIVE' GROUP BY decision_id HAVING count(*) > 1) t")
				.query(Integer.class).single()).isZero();
	}
}
