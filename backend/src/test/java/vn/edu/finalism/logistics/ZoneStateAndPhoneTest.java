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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class ZoneStateAndPhoneTest {

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

	private String submitOrder(String customer, TestWorld w) throws Exception {
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

	@Test
	void aZoneWithOpenWorkCannotBeStoppedUntilItIsClearedAndCanBeRestored() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String ops = api.login(w.ops);
		String orderId = submitOrder(customer, w);
		String path = "/api/v1/admin/zones/" + w.zoneId;

		api.post(dispatcher, path + "/deactivate", "{\"reason\":\"x\"}").andExpect(status().isForbidden());
		api.post(ops, path + "/deactivate", "{\"reason\":\" \"}").andExpect(status().isBadRequest());
		api.post(ops, path + "/deactivate", "{\"reason\":\"Stop serving this area\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.details.blockingOrders").value(1));

		api.post(dispatcher, "/api/v1/orders/" + orderId + "/cancel", "{\"reason\":\"Zone closing\"}",
				"Idempotency-Key", key(), "If-Match", "\"" + orderVersion(orderId) + "\"").andExpect(status().isOk());

		api.post(ops, path + "/deactivate", "{\"reason\":\"Stop serving this area\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
		api.get(ops, "/api/v1/admin/zone-options").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id == '" + w.zoneId + "')]").isEmpty());
		api.post(ops, path + "/deactivate", "{\"reason\":\"again\"}").andExpect(status().isConflict());
		api.post(customer, "/api/v1/orders", submitBody(w), "Idempotency-Key", key()).andExpect(status().is4xxClientError());

		api.post(ops, path + "/activate", "{\"reason\":\"Serving again\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = :id AND action IN ('ZONE_DEACTIVATED','ZONE_ACTIVATED')")
				.param("id", w.zoneId).query(Integer.class).single()).isEqualTo(2);
	}

	private String submitBody(TestWorld w) {
		Instant now = Instant.now();
		return """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
				 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(),
				now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
	}

	private long orderVersion(String id) {
		return jdbc.sql("SELECT version FROM ordering.orders WHERE id = CAST(:id AS uuid)").param("id", id)
				.query(Long.class).single();
	}

	@Test
	void usersCanHaveAValidatedPhoneNumber() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String email = "phone-" + UUID.randomUUID() + "@test.local";
		String base = "{\"email\":\"" + email + "\",\"displayName\":\"Phone User\",\"password\":\"Correct-horse-1\"";

		api.post(admin, "/api/v1/admin/users", base + ",\"phone\":\"abc\"}", "Idempotency-Key", key())
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("phone"));
		String id = JsonPath.read(TestApi.bodyOf(api.post(admin, "/api/v1/admin/users",
				base + ",\"phone\":\"0901 234 567\"}", "Idempotency-Key", key()).andExpect(status().isCreated())
				.andExpect(jsonPath("$.phone").value("0901 234 567"))), "$.id");

		String etag = TestApi.etagOf(api.get(admin, "/api/v1/admin/users/" + id));
		api.patch(admin, "/api/v1/admin/users/" + id, "{\"displayName\":\"Phone User\",\"phone\":\"+84 912 345 678\"}",
				"If-Match", etag).andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("+84 912 345 678"));
		String next = TestApi.etagOf(api.get(admin, "/api/v1/admin/users/" + id));
		api.patch(admin, "/api/v1/admin/users/" + id, "{\"displayName\":\"Phone User\"}", "If-Match", next)
				.andExpect(status().isOk()).andExpect(jsonPath("$.phone").doesNotExist());
	}

	@Test
	void aUsernameIsUniqueNormalisedAndWorksForSigningIn() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String name = "user." + UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"%s@test.local\",\"username\":\"%s\",\"displayName\":\"Named User\",\"password\":\"Correct-horse-1\"}";

		api.post(admin, "/api/v1/admin/users", body.formatted("bad-" + name, "a b"), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.post(admin, "/api/v1/admin/users", body.formatted(name, name.toUpperCase()), "Idempotency-Key", key())
				.andExpect(status().isCreated()).andExpect(jsonPath("$.username").value(name));
		api.post(admin, "/api/v1/admin/users", body.formatted("other-" + name, name), "Idempotency-Key", key())
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("username"));

		org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
				.webAppContextSetup(context).addFilters(correlationIdFilter).apply(springSecurity()).build();
		for (String identifier : new String[] {name, name.toUpperCase(), name + "@test.local"}) {
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content("{\"identifier\":\"" + identifier + "\",\"password\":\"Correct-horse-1\"}"))
					.andExpect(status().isOk());
		}
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"identifier\":\"" + name + "\",\"password\":\"wrong-password-1\"}"))
				.andExpect(status().isUnauthorized());
	}
	@Test
	void addressSearchReportsThatItIsDisabledWhenNoGeocoderIsConfigured() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		api.get(api.login(w.customer), "/api/v1/geo/geocode?q=12%20Nguyen%20Hue").andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("EXTERNAL_DEPENDENCY_UNAVAILABLE"));
	}
}
