package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;
import vn.edu.finalism.logistics.modules.order.application.port.GeocodingPort;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class OrderEditCancelFlowTest {

	static class CodedGeocoder implements GeocodingPort {

		@Override
		public Optional<GeoPoint> geocode(String address) {
			if (address != null && address.startsWith("GEO:")) {
				String[] parts = address.substring(4).split(",");
				return Optional.of(new GeoPoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])));
			}
			return Optional.empty();
		}
	}

	@TestConfiguration
	static class Config {

		@Bean
		@Primary
		GeocodingPort codedGeocoder() {
			return new CodedGeocoder();
		}
	}

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

	private String body(TestWorld w, String mode, String pickup, String pickupPoint, String weight, Instant start) {
		return """
				{"saveMode":"%s",
				 "pickup":{"address":"%s"%s},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
				 "package":{"weightG":%s,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(mode, pickup, pickupPoint, w.dropoffLat(), w.dropoffLng(), weight, start,
				start.plus(Duration.ofHours(4)));
	}

	private String point(double lat, double lng) {
		return ",\"point\":{\"lat\":" + lat + ",\"lng\":" + lng + "}";
	}

	private ResultActions create(String token, TestWorld w, String mode) throws Exception {
		return api.post(token, "/api/v1/orders", body(w, mode, "12 Nguyen Hue", point(w.pickupLat(), w.pickupLng()),
				"2500", Instant.now().plus(Duration.ofHours(1))), "Idempotency-Key", key()).andExpect(status().isCreated());
	}

	private String id(ResultActions result) throws Exception {
		return JsonPath.read(TestApi.bodyOf(result), "$.data.id");
	}

	@Test
	void anOwnerEditsADraftAndAReadyOrderUntilItIsBatched() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String other = api.login(w.otherCustomer);
		String dispatcher = api.login(w.dispatcher);
		String id = id(create(customer, w, "DRAFT"));
		String path = "/api/v1/orders/" + id;
		Instant start = Instant.now().plus(Duration.ofHours(2));
		String edit = body(w, "DRAFT", "99 Pasteur", point(w.pickupLat(), w.pickupLng()), "4000", start)
				.replace("\"saveMode\":\"DRAFT\",", "");

		api.put(customer, path, edit, "If-Match", "\"9\"").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		api.put(other, path, edit, "If-Match", "\"0\"").andExpect(status().isNotFound());
		api.put(dispatcher, path, edit, "If-Match", "\"0\"").andExpect(status().isForbidden());
		api.put(customer, path, edit, "If-Match", "\"0\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.package.weightG").value(4000))
				.andExpect(jsonPath("$.data.pickup.address").value("99 Pasteur"))
				.andExpect(jsonPath("$.data.status").value("DRAFT"))
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.capabilities[?(@ == 'canSubmitOrder')]").exists());

		api.post(customer, path + "/submit", "", "Idempotency-Key", key(), "If-Match", "\"1\"").andExpect(status().isOk());
		String outside = body(w, "DRAFT", "Far away", point(w.pickupLat() + 5, w.pickupLng() + 5), "4000", start)
				.replace("\"saveMode\":\"DRAFT\",", "");
		api.put(customer, path, outside, "If-Match", "\"2\"").andExpect(status().is4xxClientError())
				.andExpect(jsonPath("$.code").value("ZONE_UNRESOLVED"));
		api.put(customer, path, edit.replace("4000", "5000"), "If-Match", "\"2\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("READY_FOR_DISPATCH"))
				.andExpect(jsonPath("$.data.zoneId").value(w.zoneId.toString()));

		String batchBody = "{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + id + "\"]}";
		api.post(dispatcher, "/api/v1/dispatch/batches", batchBody, "Idempotency-Key", key()).andExpect(status().isCreated());
		api.put(customer, path, edit, "If-Match", TestApi.etagOf(api.get(customer, path)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
		api.post(customer, path + "/cancel", "{\"reason\":\"changed my mind\"}", "Idempotency-Key", key(), "If-Match",
				TestApi.etagOf(api.get(customer, path))).andExpect(status().isConflict());
		api.get(customer, path).andExpect(jsonPath("$.capabilities[?(@ == 'canEditOrder')]").doesNotExist());
	}

	@Test
	void cancellingNeedsAReasonOnceTheOrderIsReadyAndNothingWorksAfterwards() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String other = api.login(w.otherCustomer);
		String draftId = id(create(customer, w, "DRAFT"));
		String readyId = id(create(customer, w, "SUBMIT"));

		api.post(customer, "/api/v1/orders/" + draftId + "/cancel", "", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));

		String path = "/api/v1/orders/" + readyId;
		api.post(customer, path + "/cancel", "{\"reason\":\" \"}", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isBadRequest());
		api.post(customer, path + "/cancel", "", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isBadRequest());
		api.post(other, path + "/cancel", "{\"reason\":\"not mine\"}", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isNotFound());
		api.post(customer, path + "/cancel", "{\"reason\":\"x\"}", "Idempotency-Key", key(), "If-Match", "\"4\"")
				.andExpect(status().isConflict());
		String cancelKey = key();
		api.post(customer, path + "/cancel", "{\"reason\":\"Found a cheaper option\"}", "Idempotency-Key", cancelKey,
				"If-Match", "\"0\"").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
				.andExpect(jsonPath("$.data.cancelReason").value("Found a cheaper option"))
				.andExpect(jsonPath("$.capabilities.length()").value(0));
		api.post(customer, path + "/cancel", "{\"reason\":\"Found a cheaper option\"}", "Idempotency-Key", cancelKey,
				"If-Match", "\"0\"").andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));

		api.post(customer, path + "/cancel", "{\"reason\":\"again\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isConflict());
		api.post(customer, path + "/submit", "", "Idempotency-Key", key(), "If-Match", "\"1\"").andExpect(status().isConflict());
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND action = 'ORDER_CANCELLED'")
				.param("id", readyId).query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT cancelled_from_status FROM ordering.orders WHERE id = CAST(:id AS uuid)")
				.param("id", readyId).query(String.class).single()).isEqualTo("READY_FOR_DISPATCH");
	}

	@Test
	void dispatchStaffMayCancelOrdersInTheirZoneEvenWhenBatchedButOthersCannot() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String foreign = api.login(TestWorld.create(jdbc, encoder, true).dispatcher);
		String readyId = id(create(customer, w, "SUBMIT"));
		String batchedId = id(create(customer, w, "SUBMIT"));
		api.post(dispatcher, "/api/v1/dispatch/batches", "{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + batchedId + "\"]}",
				"Idempotency-Key", key()).andExpect(status().isCreated());

		api.post(foreign, "/api/v1/orders/" + readyId + "/cancel", "{\"reason\":\"x\"}", "Idempotency-Key", key(), "If-Match",
				"\"0\"").andExpect(status().isNotFound());
		api.post(dispatcher, "/api/v1/orders/" + batchedId + "/cancel", "{\"reason\":\"x\"}", "Idempotency-Key", key(),
				"If-Match", TestApi.etagOf(api.get(dispatcher, "/api/v1/orders/" + batchedId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
		api.post(dispatcher, "/api/v1/orders/" + readyId + "/cancel", "{\"reason\":\"Address is unreachable\"}",
				"Idempotency-Key", key(), "If-Match", "\"0\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("CANCELLED"));
	}

	@Test
	void theTrackingTimelineShowsOnlyCustomerSafeMilestonesInOrder() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String other = api.login(w.otherCustomer);
		String dispatcher = api.login(w.dispatcher);
		String id = id(create(customer, w, "SUBMIT"));
		String path = "/api/v1/orders/" + id + "/tracking";

		api.get(customer, path).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].milestone").value("ORDER_RECEIVED"))
				.andExpect(jsonPath("$.items[0].note").value("Đơn hàng đã được tiếp nhận"));

		api.post(dispatcher, "/api/v1/dispatch/batches", "{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + id + "\"]}",
				"Idempotency-Key", key()).andExpect(status().isCreated());
		Thread.sleep(5);
		api.get(customer, path).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[1].milestone").value("BATCHED"));
		api.get(dispatcher, path).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
		api.get(other, path).andExpect(status().isNotFound());

		String second = id(create(customer, w, "SUBMIT"));
		String reason = "Internal secret reason " + UUID.randomUUID();
		api.post(customer, "/api/v1/orders/" + second + "/cancel", "{\"reason\":\"" + reason + "\"}", "Idempotency-Key", key(),
				"If-Match", "\"0\"").andExpect(status().isOk());
		String timeline = TestApi.bodyOf(api.get(customer, "/api/v1/orders/" + second + "/tracking"));
		assertThat(JsonPath.<List<String>>read(timeline, "$.items[*].milestone")).containsExactly("ORDER_RECEIVED", "ORDER_CANCELLED");
		assertThat(timeline).doesNotContain(reason);
	}

	@Test
	void anAddressWithoutAPointIsLookedUpThroughTheGeocodingPort() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String known = "GEO:" + w.pickupLat() + "," + w.pickupLng();

		String id = id(api.post(customer, "/api/v1/orders", body(w, "SUBMIT", known, "", "2500", Instant.now().plus(Duration.ofHours(1))),
				"Idempotency-Key", key()).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("READY_FOR_DISPATCH"))
				.andExpect(jsonPath("$.data.zoneId").value(w.zoneId.toString()))
				.andExpect(jsonPath("$.data.pickup.point.lat").value(w.pickupLat())));
		assertThat(id).isNotBlank();

		api.post(customer, "/api/v1/orders", body(w, "SUBMIT", "Nowhere in particular", "", "2500", Instant.now().plus(Duration.ofHours(1))),
				"Idempotency-Key", key()).andExpect(status().isBadRequest());
		String draft = id(api.post(customer, "/api/v1/orders", body(w, "DRAFT", "Nowhere in particular", "", "2500", Instant.now().plus(Duration.ofHours(1))),
				"Idempotency-Key", key()).andExpect(status().isCreated()).andExpect(jsonPath("$.data.pickup.point").doesNotExist()));
		api.post(customer, "/api/v1/orders/" + draft + "/submit", "", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isBadRequest());
	}

	@Test
	void theListFiltersByTimeWindow() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		Instant early = Instant.now().plus(Duration.ofHours(1));
		Instant late = Instant.now().plus(Duration.ofDays(3));
		api.post(customer, "/api/v1/orders", body(w, "SUBMIT", "A", point(w.pickupLat(), w.pickupLng()), "1000", early),
				"Idempotency-Key", key()).andExpect(status().isCreated());
		api.post(customer, "/api/v1/orders", body(w, "SUBMIT", "B", point(w.pickupLat(), w.pickupLng()), "1000", late),
				"Idempotency-Key", key()).andExpect(status().isCreated());

		api.get(customer, "/api/v1/orders").andExpect(jsonPath("$.items.length()").value(2));
		api.get(customer, "/api/v1/orders?windowFrom=" + late.minus(Duration.ofHours(1)))
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].pickup.address").value("B"));
		api.get(customer, "/api/v1/orders?windowTo=" + early.plus(Duration.ofHours(5)))
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].pickup.address").value("A"));
	}
	@Test
	void theGeocodeEndpointResolvesAnAddressForStaffAndCustomers() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		api.get(customer, "/api/v1/geo/geocode?q=GEO:10.77,106.70").andExpect(status().isOk())
				.andExpect(jsonPath("$.lat").value(10.77)).andExpect(jsonPath("$.lng").value(106.70));
		api.get(customer, "/api/v1/geo/geocode?q=ab").andExpect(status().isBadRequest());
		api.get(customer, "/api/v1/geo/geocode?q=Nowhere%20street").andExpect(status().isNotFound());
		api.get(api.login(w.driverUser), "/api/v1/geo/geocode?q=GEO:10.77,106.70").andExpect(status().isForbidden());
	}
}
