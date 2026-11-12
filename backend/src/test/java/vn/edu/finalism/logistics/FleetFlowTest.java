package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.TestWorld.TestUser;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class FleetFlowTest {

	private static final String PASSWORD = "Fresh-start-2026";

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

	private static String vehicleJson(String code, int weight) {
		return "{\"code\":\"" + code + "\",\"vehicleType\":\"VAN\",\"capacityWeightG\":" + weight
				+ ",\"capacityVolumeCm3\":2000000000,\"note\":\"test\"}";
	}

	private String vehicle(String ops, String code) throws Exception {
		return JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/vehicles", vehicleJson(code, 500000),
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.id");
	}

	private TestUser driverAccount(TestWorld w) throws Exception {
		String email = "drv-" + UUID.randomUUID() + "@test.local";
		String id = JsonPath.read(TestApi.bodyOf(api.post(api.login(w.admin), "/api/v1/admin/users",
				"{\"email\":\"" + email + "\",\"displayName\":\"New Driver\",\"password\":\"" + PASSWORD + "\"}",
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.id");
		TestUser user = new TestUser(UUID.fromString(id), email);
		w.grantRole(user, "DRIVER");
		return user;
	}

	private String loginWithPassword(TestUser user) throws Exception {
		return JsonPath.read(TestApi.bodyOf(api.post(api.login(TestWorld.create(jdbc, encoder, true).admin),
				"/api/v1/auth/login", "{\"identifier\":\"" + user.email() + "\",\"password\":\"" + PASSWORD + "\"}")
				.andExpect(status().isOk())), "$.accessToken");
	}

	private String driverProfile(String ops, TestWorld w, TestUser user, String vehicleId) throws Exception {
		String vehicleField = vehicleId == null ? "" : ",\"vehicleId\":\"" + vehicleId + "\"";
		return JsonPath.read(TestApi.bodyOf(api.post(ops, "/api/v1/admin/drivers",
				"{\"userId\":\"" + user.id() + "\",\"fullName\":\"New Driver\",\"contact\":\"0900\"" + vehicleField
						+ ",\"zoneIds\":[\"" + w.zoneId + "\"]}", "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.id");
	}

	@Test
	void onlyTheOperationsManagerManagesTheFleet() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		for (TestUser user : new TestUser[] { w.customer, w.dispatcher, w.driverUser, w.admin }) {
			String token = api.login(user);
			api.get(token, "/api/v1/admin/vehicles").andExpect(status().isForbidden());
			api.get(token, "/api/v1/admin/drivers").andExpect(status().isForbidden());
		}
		String ops = api.login(w.ops);
		api.get(ops, "/api/v1/admin/vehicles").andExpect(status().isOk());
		api.get(ops, "/api/v1/admin/drivers").andExpect(status().isOk());
	}

	@Test
	void vehiclesAreCreatedEditedAndSwitchedOnAndOff() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		String code = "veh-" + UUID.randomUUID().toString().substring(0, 8);
		String createKey = key();

		String created = TestApi.bodyOf(api.post(ops, "/api/v1/admin/vehicles", vehicleJson(code, 500000), "Idempotency-Key", createKey)
				.andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(code.toUpperCase()))
				.andExpect(jsonPath("$.active").value(true)).andExpect(header().string("ETag", "\"0\"")));
		String id = JsonPath.read(created, "$.id");
		api.post(ops, "/api/v1/admin/vehicles", vehicleJson(code, 500000), "Idempotency-Key", createKey)
				.andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
		api.post(ops, "/api/v1/admin/vehicles", vehicleJson(code, 500000), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/vehicles", vehicleJson("X" + code, 0), "Idempotency-Key", key())
				.andExpect(status().isBadRequest());

		String path = "/api/v1/admin/vehicles/" + id;
		api.patch(ops, path, vehicleJson(code, 700000), "If-Match", "\"4\"").andExpect(status().isConflict());
		api.patch(ops, path, vehicleJson(code, 700000), "If-Match", "\"0\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.capacityWeightG").value(700000)).andExpect(header().string("ETag", "\"1\""));
		api.post(ops, path + "/deactivate", "", "If-Match", "\"1\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
		api.post(ops, path + "/deactivate", "", "If-Match", "\"2\"").andExpect(status().isConflict());
		api.post(ops, path + "/activate", "", "If-Match", "\"2\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		api.get(ops, "/api/v1/admin/vehicles?q=" + code.substring(0, 8)).andExpect(jsonPath("$.items.length()").value(1));
		api.get(ops, "/api/v1/admin/vehicles?q=" + code.substring(0, 8) + "&active=false")
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void vehiclePagingWalksEveryVehicleOnce() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		String tag = "PG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		for (int i = 0; i < 3; i++) {
			vehicle(ops, tag + i);
			Thread.sleep(5);
		}
		String first = TestApi.bodyOf(api.get(ops, "/api/v1/admin/vehicles?q=" + tag + "&limit=2")
				.andExpect(jsonPath("$.items.length()").value(2)));
		String second = TestApi.bodyOf(api.get(ops, "/api/v1/admin/vehicles?q=" + tag + "&limit=2&cursor="
				+ JsonPath.read(first, "$.nextCursor")).andExpect(jsonPath("$.items.length()").value(1)));
		List<String> ids = new ArrayList<>(JsonPath.<List<String>>read(first, "$.items[*].id"));
		ids.addAll(JsonPath.<List<String>>read(second, "$.items[*].id"));
		assertThat(ids).doesNotHaveDuplicates().hasSize(3);
	}

	@Test
	void aDriverProfileNeedsADriverAccountAFreeActiveVehicleAndZonesInScope() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		TestWorld other = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		TestUser account = driverAccount(w);
		String body = "{\"userId\":\"" + account.id() + "\",\"fullName\":\"New Driver\",\"zoneIds\":[\"" + w.zoneId + "\"]}";

		api.post(ops, "/api/v1/admin/drivers", "{\"userId\":\"" + w.customer.id() + "\",\"fullName\":\"X\"}",
				"Idempotency-Key", key()).andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/drivers", "{\"userId\":\"" + w.driverUser.id() + "\",\"fullName\":\"X\"}",
				"Idempotency-Key", key()).andExpect(status().isBadRequest());
		api.post(ops, "/api/v1/admin/drivers", body.replace(w.zoneId.toString(), other.zoneId.toString()),
				"Idempotency-Key", key()).andExpect(status().isNotFound());
		api.post(ops, "/api/v1/admin/drivers", body.replace("}", ",\"vehicleId\":\"" + w.vehicleId + "\"}"),
				"Idempotency-Key", key()).andExpect(status().isBadRequest());

		String inactive = vehicle(ops, "INA" + UUID.randomUUID().toString().substring(0, 6));
		api.post(ops, "/api/v1/admin/vehicles/" + inactive + "/deactivate", "", "If-Match", "\"0\"").andExpect(status().isOk());
		api.post(ops, "/api/v1/admin/drivers", body.replace("}", ",\"vehicleId\":\"" + inactive + "\"}"),
				"Idempotency-Key", key()).andExpect(status().isBadRequest());

		String free = vehicle(ops, "FRE" + UUID.randomUUID().toString().substring(0, 6));
		api.get(ops, "/api/v1/admin/driver-candidates").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.userId == '" + account.id() + "')]").exists())
				.andExpect(jsonPath("$.items[?(@.userId == '" + w.driverUser.id() + "')]").doesNotExist())
				.andExpect(jsonPath("$.items[?(@.userId == '" + w.customer.id() + "')]").doesNotExist());
		String id = driverProfile(ops, w, account, free);
		api.get(ops, "/api/v1/admin/driver-candidates")
				.andExpect(jsonPath("$.items[?(@.userId == '" + account.id() + "')]").doesNotExist());
		api.get(ops, "/api/v1/admin/drivers/" + id).andExpect(status().isOk())
				.andExpect(jsonPath("$.availability").value("OFFLINE")).andExpect(jsonPath("$.vehicle.id").value(free))
				.andExpect(jsonPath("$.zoneIds[0]").value(w.zoneId.toString())).andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DRV-")));

		api.get(api.login(other.ops), "/api/v1/admin/drivers/" + id).andExpect(status().isNotFound());
		api.get(api.login(other.ops), "/api/v1/admin/drivers?q=New Driver").andExpect(jsonPath("$.items.length()").value(0));
		api.patch(ops, "/api/v1/admin/drivers/" + id, "{\"fullName\":\"Renamed\",\"contact\":\"0911\",\"zoneIds\":[\"" + w.zoneId + "\"]}",
				"If-Match", "\"0\"").andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Renamed"))
				.andExpect(jsonPath("$.vehicle").doesNotExist());
	}

	@Test
	void theDatabaseItselfRefusesTwoDriversOnOneVehicle() {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		UUID otherUser = UUID.randomUUID();
		jdbc.sql("INSERT INTO iam.users (id, email, display_name, password_hash, status, created_at, updated_at) VALUES (:id, :email, 'x', 'x', 'ACTIVE', now(), now())")
				.param("id", otherUser).param("email", "dup-" + otherUser + "@test.local").update();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO fleet.drivers (id, user_id, code, full_name, vehicle_id, created_at, updated_at) VALUES (gen_random_uuid(), :user, :code, 'Dup', :vehicle, now(), now())")
				.param("user", otherUser).param("code", "DUP-" + otherUser.toString().substring(0, 6))
				.param("vehicle", w.vehicleId).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aDriverWithAnOpenAssignmentCannotBeDeactivatedUntilItEnds() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String ops = api.login(w.ops);
		Instant now = Instant.now();
		String order = """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"A","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"B","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"N","contact":"+84900000000"},
				 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(), now.plus(Duration.ofHours(1)),
				now.plus(Duration.ofHours(5)));
		String orderId = JsonPath.read(TestApi.bodyOf(api.post(customer, "/api/v1/orders", order, "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
		String batchId = JsonPath.read(TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches",
				"{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + orderId + "\"]}", "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
		api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"").andExpect(status().isOk());
		ResultActions rec = api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations", "", "If-Match", "\"1\"")
				.andExpect(status().isCreated());
		String decisionId = JsonPath.read(TestApi.bodyOf(rec), "$.data.decisionId");
		ResultActions assigned = api.post(dispatcher, "/api/v1/dispatch/decisions/" + decisionId + "/assign",
				"{\"snapshotId\":\"" + JsonPath.read(TestApi.bodyOf(rec), "$.data.snapshot.id") + "\"}", "Idempotency-Key", key(),
				"If-Match", TestApi.etagOf(rec)).andExpect(status().isOk());
		String assignmentId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.id");

		String driverPath = "/api/v1/admin/drivers/" + w.driverId;
		String version = TestApi.etagOf(api.get(ops, driverPath));
		api.post(ops, driverPath + "/deactivate", "", "If-Match", version).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"))
				.andExpect(jsonPath("$.details.blockingAssignmentIds[0]").value(assignmentId));
		String vehiclePath = "/api/v1/admin/vehicles/" + w.vehicleId;
		api.post(ops, vehiclePath + "/deactivate", "", "If-Match", TestApi.etagOf(api.get(ops, vehiclePath)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.details.blockingAssignmentIds[0]").value(assignmentId));

		String driver = api.login(w.driverUser);
		String me = TestApi.etagOf(api.get(driver, "/api/v1/me/driver"));
		api.put(driver, "/api/v1/me/driver/availability", "{\"availability\":\"OFFLINE\"}", "If-Match", me)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
		api.get(driver, "/api/v1/me/driver").andExpect(jsonPath("$.availability").value("RESERVED"))
				.andExpect(jsonPath("$.pendingAssignments").value(1));

		api.post(dispatcher, "/api/v1/dispatch/decisions/" + decisionId + "/cancel", "{\"reason\":\"Plan changed\"}",
				"If-Match", TestApi.etagOf(assigned)).andExpect(status().isOk());
		api.post(ops, driverPath + "/deactivate", "", "If-Match", TestApi.etagOf(api.get(ops, driverPath)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.availability").value("OFFLINE"));
		api.put(driver, "/api/v1/me/driver/availability", "{\"availability\":\"AVAILABLE\"}", "If-Match",
				TestApi.etagOf(api.get(driver, "/api/v1/me/driver"))).andExpect(status().isConflict());
	}

	@Test
	void aDriverChangesOwnAvailabilityOnlyBetweenAvailableAndOffline() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String ops = api.login(w.ops);
		TestUser account = driverAccount(w);
		driverProfile(ops, w, account, null);
		String driver = loginWithPassword(account);

		ResultActions first = api.get(driver, "/api/v1/me/driver").andExpect(status().isOk())
				.andExpect(jsonPath("$.availability").value("OFFLINE")).andExpect(jsonPath("$.pendingAssignments").value(0))
				.andExpect(jsonPath("$.location").doesNotExist());
		String version = TestApi.etagOf(first);

		api.put(driver, "/api/v1/me/driver/availability", "{\"availability\":\"BUSY\"}", "If-Match", version)
				.andExpect(status().isBadRequest());
		api.put(driver, "/api/v1/me/driver/availability", "{\"availability\":\"AVAILABLE\"}", "If-Match", "\"9\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		ResultActions online = api.put(driver, "/api/v1/me/driver/availability", "{\"availability\":\"AVAILABLE\"}", "If-Match", version)
				.andExpect(status().isOk()).andExpect(jsonPath("$.availability").value("AVAILABLE"));
		String onlineVersion = TestApi.etagOf(online);
		assertThat(onlineVersion).isNotEqualTo(version);
		api.put(driver, "/api/v1/me/driver/availability", "{\"availability\":\"AVAILABLE\"}", "If-Match", onlineVersion)
				.andExpect(status().isOk()).andExpect(header().string("ETag", onlineVersion));
		api.put(driver, "/api/v1/me/driver/availability", "{\"availability\":\"OFFLINE\"}", "If-Match", onlineVersion)
				.andExpect(status().isOk()).andExpect(jsonPath("$.availability").value("OFFLINE"));

		api.get(api.login(w.customer), "/api/v1/me/driver").andExpect(status().isForbidden());
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE action = 'DRIVER_AVAILABILITY_CHANGED' AND actor_id = CAST(:id AS uuid)")
				.param("id", account.id()).query(Integer.class).single()).isEqualTo(2);
	}

	@Test
	void locationUpdatesAreValidatedStoredAndRateLimited() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String driver = api.login(w.driverUser);
		String otherDriver = api.login(w.addDriver().user());

		api.put(driver, "/api/v1/me/driver/location", "{\"point\":{\"lat\":95,\"lng\":10},\"accuracyM\":5}")
				.andExpect(status().isBadRequest());
		api.put(driver, "/api/v1/me/driver/location", "{\"point\":{\"lat\":10,\"lng\":10},\"accuracyM\":-1}")
				.andExpect(status().isBadRequest());
		api.put(driver, "/api/v1/me/driver/location", "{\"point\":{\"lat\":10.5,\"lng\":106.7},\"accuracyM\":8.5}")
				.andExpect(status().isNoContent());
		api.get(driver, "/api/v1/me/driver").andExpect(jsonPath("$.location.lat").value(10.5))
				.andExpect(jsonPath("$.location.lng").value(106.7)).andExpect(jsonPath("$.location.recordedAt").exists());

		for (int i = 0; i < 5; i++) {
			api.put(driver, "/api/v1/me/driver/location", "{\"point\":{\"lat\":10.5,\"lng\":106.7}}")
					.andExpect(status().isNoContent());
		}
		api.put(driver, "/api/v1/me/driver/location", "{\"point\":{\"lat\":10.5,\"lng\":106.7}}")
				.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		api.put(otherDriver, "/api/v1/me/driver/location", "{\"point\":{\"lat\":10.1,\"lng\":106.1}}")
				.andExpect(status().isNoContent());
	}
}
