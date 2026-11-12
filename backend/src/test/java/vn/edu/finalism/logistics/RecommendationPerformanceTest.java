package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class RecommendationPerformanceTest {

	static final int DRIVERS = 500;
	static final int ORDERS = 20;
	static final int SAMPLES = 40;

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

	private void seedDrivers(TestWorld w) {
		String tag = w.zoneId.toString().substring(0, 8);
		jdbc.sql("""
				INSERT INTO iam.users (id, email, display_name, password_hash, status, created_at, updated_at)
				SELECT gen_random_uuid(), 'bench-' || :tag || '-' || g || '@test.local', 'Bench driver ' || g,
				       'x', 'ACTIVE', now(), now()
				FROM generate_series(1, :n) g
				""").param("tag", tag).param("n", DRIVERS).update();
		jdbc.sql("""
				INSERT INTO iam.user_roles (user_id, role_id, granted_at)
				SELECT u.id, r.id, now() FROM iam.users u, iam.roles r
				WHERE r.code = 'DRIVER' AND u.email LIKE 'bench-' || :tag || '-%'
				""").param("tag", tag).update();
		jdbc.sql("""
				INSERT INTO fleet.vehicles (id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3, created_at, updated_at)
				SELECT gen_random_uuid(), 'BV-' || :tag || '-' || g, 'VAN', 200000, 2000000000, now(), now()
				FROM generate_series(1, :n) g
				""").param("tag", tag).param("n", DRIVERS).update();
		jdbc.sql("""
				INSERT INTO fleet.drivers (id, user_id, code, full_name, availability, vehicle_id, created_at, updated_at)
				SELECT gen_random_uuid(), u.id, 'BD-' || :tag || '-' || u.rn, u.display_name, 'AVAILABLE', v.id, now(), now()
				FROM (SELECT id, display_name, row_number() OVER (ORDER BY email) AS rn
				      FROM iam.users WHERE email LIKE 'bench-' || :tag || '-%') u
				JOIN (SELECT id, row_number() OVER (ORDER BY code) AS rn
				      FROM fleet.vehicles WHERE code LIKE 'BV-' || :tag || '-%') v ON v.rn = u.rn
				""").param("tag", tag).update();
		jdbc.sql("""
				INSERT INTO fleet.driver_zones (driver_id, zone_id)
				SELECT id, :zone FROM fleet.drivers WHERE code LIKE 'BD-' || :tag || '-%'
				""").param("tag", tag).param("zone", w.zoneId).update();
		jdbc.sql("""
				INSERT INTO fleet.driver_locations (driver_id, location, accuracy_m, recorded_at)
				SELECT id, ST_SetSRID(ST_MakePoint(:lng + random() * 0.09, :lat + random() * 0.09), 4326)::geography,
				       5, now()
				FROM fleet.drivers WHERE code LIKE 'BD-' || :tag || '-%'
				""").param("tag", tag).param("lng", w.lng0).param("lat", w.lat0).update();
	}

	@Test
	void generatingARecommendationAmongFiveHundredDriversMeetsTheLatencyTarget() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		seedDrivers(w);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);

		Instant now = Instant.now();
		List<String> orderIds = new ArrayList<>();
		for (int i = 0; i < ORDERS; i++) {
			String body = """
					{"saveMode":"SUBMIT",
					 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
					 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
					 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
					 "package":{"weightG":1500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
					 "timeWindow":{"start":"%s","end":"%s"}}
					""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(),
					now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
			orderIds.add(JsonPath.read(TestApi.bodyOf(api.post(customer, "/api/v1/orders", body, "Idempotency-Key",
					UUID.randomUUID().toString()).andExpect(status().isCreated())), "$.data.id"));
		}
		String batchId = JsonPath.read(TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches",
				"{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + String.join("\",\"", orderIds) + "\"]}",
				"Idempotency-Key", UUID.randomUUID().toString()).andExpect(status().isCreated())), "$.data.id");
		api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"")
				.andExpect(status().isOk());

		String path = "/api/v1/dispatch/batches/" + batchId + "/recommendations";
		for (int i = 0; i < 3; i++) {
			api.post(dispatcher, path, "", "If-Match", "\"1\"").andExpect(status().isCreated());
		}
		List<Long> millis = new ArrayList<>();
		for (int i = 0; i < SAMPLES; i++) {
			long start = System.nanoTime();
			String json = TestApi.bodyOf(api.post(dispatcher, path, "", "If-Match", "\"1\"")
					.andExpect(status().isCreated()));
			millis.add((System.nanoTime() - start) / 1_000_000);
			assertThat((Integer) JsonPath.read(json, "$.data.snapshot.candidateCount")).isGreaterThanOrEqualTo(DRIVERS);
		}
		Collections.sort(millis);
		long p95 = millis.get((int) Math.ceil(SAMPLES * 0.95) - 1);
		long p99 = millis.get(SAMPLES - 1);

		java.nio.file.Path dir = java.nio.file.Path.of("target", "perf-results");
		java.nio.file.Files.createDirectories(dir);
		java.nio.file.Files.writeString(dir.resolve("recommendation.txt"), "PERF-01 recommendation: drivers=" + DRIVERS
				+ " orders=" + ORDERS + " samples=" + SAMPLES + " p50=" + millis.get(SAMPLES / 2) + "ms p95=" + p95
				+ "ms p99=" + p99 + "ms" + System.lineSeparator());

		assertThat(p95).as("p95 ms %s", millis).isLessThanOrEqualTo(2000);
		assertThat(p99).as("p99 ms %s", millis).isLessThanOrEqualTo(4000);
	}
}
