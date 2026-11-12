package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
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

import vn.edu.finalism.logistics.TestWorld.DriverRef;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class RecommendationScoringFlowTest {

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

	private String order(String customer, TestWorld w, double dropLat, double dropLng, Duration from, Duration to)
			throws Exception {
		Instant now = Instant.now();
		String body = """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
				 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), dropLat, dropLng, now.plus(from), now.plus(to));
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

	private String recommend(String dispatcher, String batchId) throws Exception {
		return TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations", "",
				"If-Match", "\"1\"").andExpect(status().isCreated()));
	}

	private double score(String json, UUID driverId, String criterion) {
		List<Number> found = JsonPath.read(json,
				"$.data.snapshot.candidates[?(@.driverId=='" + driverId + "')].breakdown." + criterion);
		assertThat(found).hasSize(1);
		return found.get(0).doubleValue();
	}

	@Test
	void zoneCompatibilityDropsWhenADropoffLiesOutsideTheDriversZone() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);

		String inside = order(customer, w, w.dropoffLat(), w.dropoffLng(), Duration.ofHours(1), Duration.ofHours(5));
		String insideJson = recommend(dispatcher, lockedBatch(dispatcher, w, inside));
		assertThat(score(insideJson, w.driverId, "zoneCompatibility")).isEqualTo(100.0);

		String outside = order(customer, w, w.lat0 + 0.5, w.lng0 + 0.5, Duration.ofHours(6), Duration.ofHours(9));
		String outsideJson = recommend(dispatcher, lockedBatch(dispatcher, w, outside));
		assertThat(score(outsideJson, w.driverId, "zoneCompatibility")).isEqualTo(50.0);
	}

	@Test
	void aDriverWithRecentAssignmentsScoresLowerOnAvailability() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String driver = api.login(w.driverUser);

		String first = order(customer, w, w.dropoffLat(), w.dropoffLng(), Duration.ofHours(1), Duration.ofHours(5));
		String firstBatch = lockedBatch(dispatcher, w, first);
		String json = recommend(dispatcher, firstBatch);
		String assignmentId = JsonPath.read(TestApi.bodyOf(api.post(dispatcher,
				"/api/v1/dispatch/decisions/" + JsonPath.read(json, "$.data.decisionId") + "/assign",
				"{\"snapshotId\":\"" + JsonPath.read(json, "$.data.snapshot.id") + "\"}", "Idempotency-Key", key(),
				"If-Match", "\"1\"").andExpect(status().isOk())), "$.data.id");
		api.post(driver, "/api/v1/assignments/" + assignmentId + "/accept", "", "Idempotency-Key", key(), "If-Match",
				"\"2\"").andExpect(status().isOk());
		jdbc.sql("UPDATE fleet.drivers SET availability = 'AVAILABLE' WHERE id = :id").param("id", w.driverId).update();

		DriverRef fresh = w.addDriver();
		String second = order(customer, w, w.dropoffLat(), w.dropoffLng(), Duration.ofHours(6), Duration.ofHours(9));
		String secondJson = recommend(dispatcher, lockedBatch(dispatcher, w, second));

		assertThat(score(secondJson, w.driverId, "availability")).isEqualTo(75.0);
		assertThat(score(secondJson, fresh.driverId(), "availability")).isEqualTo(100.0);
	}

	@Test
	void aStaleDriverLocationLeavesNoEligibleDriverAndStillSucceeds() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, false);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String id = order(customer, w, w.dropoffLat(), w.dropoffLng(), Duration.ofHours(1), Duration.ofHours(5));

		api.post(dispatcher, "/api/v1/dispatch/batches/" + lockedBatch(dispatcher, w, id) + "/recommendations", "",
				"If-Match", "\"1\"").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.snapshot.outcome").value("NO_ELIGIBLE_DRIVER"))
				.andExpect(jsonPath("$.data.snapshot.candidates").isEmpty())
				.andExpect(jsonPath("$.data.snapshot.exclusionSummary.LOCATION_STALE").value(1))
				.andExpect(jsonPath("$.capabilities").isEmpty());
	}
}
