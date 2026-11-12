package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.TestWorld.TestUser;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class AdminUsersFlowTest {

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

	private static final String PASSWORD = "Fresh-start-2026";

	private String createUser(String admin, String email, String name) throws Exception {
		return JsonPath.read(TestApi.bodyOf(api.post(admin, "/api/v1/admin/users",
				"{\"email\":\"" + email + "\",\"displayName\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}",
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.id");
	}

	private String versionOf(String admin, String id) throws Exception {
		return TestApi.etagOf(api.get(admin, "/api/v1/admin/users/" + id));
	}

	private String email() {
		return "new-" + UUID.randomUUID() + "@test.local";
	}

	@Test
	void onlyTheSystemAdminTouchesUsersAndRoles() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		for (TestUser user : List.of(w.customer, w.dispatcher, w.driverUser, w.ops)) {
			String token = api.login(user);
			api.get(token, "/api/v1/admin/users").andExpect(status().isForbidden());
			api.get(token, "/api/v1/admin/roles").andExpect(status().isForbidden());
			api.post(token, "/api/v1/admin/users", "{\"email\":\"a@b.co\",\"displayName\":\"x\",\"password\":\"" + PASSWORD + "\"}",
					"Idempotency-Key", key()).andExpect(status().isForbidden());
		}
		String admin = api.login(w.admin);
		api.get(admin, "/api/v1/admin/users").andExpect(status().isOk());
		api.get(admin, "/api/v1/admin/roles").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.code == 'DISPATCH_OVERRIDER')].permissions[0]").exists());
		api.get(admin, "/api/v1/admin/zones").andExpect(status().isForbidden());
	}

	@Test
	void rolePermissionsCanBeEditedWithAReasonAndAuditedButTheAdminCannotLockItselfOut() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String dispatcher = api.login(w.dispatcher);
		String path = "/api/v1/admin/roles/OPS_MANAGER/permissions";
		List<List<String>> found = JsonPath.read(TestApi.bodyOf(api.get(admin, "/api/v1/admin/roles")
				.andExpect(jsonPath("$.allPermissions").isNotEmpty())),
				"$.items[?(@.code == 'OPS_MANAGER')].permissions");
		List<String> original = found.get(0);
		assertThat(original).hasSizeGreaterThan(3);
		String originalJson = original.stream().map(c -> "\"" + c + "\"").collect(java.util.stream.Collectors.joining(","));

		api.put(dispatcher, path, "{\"permissionCodes\":[],\"reason\":\"x\"}").andExpect(status().isForbidden());
		api.put(admin, path, "{\"permissionCodes\":[\"no.such.permission\"],\"reason\":\"x\"}")
				.andExpect(status().isBadRequest());
		api.put(admin, path, "{\"permissionCodes\":[],\"reason\":\" \"}").andExpect(status().isBadRequest());
		api.put(admin, "/api/v1/admin/roles/SYSTEM_ADMIN/permissions",
				"{\"permissionCodes\":[\"audit.view\"],\"reason\":\"lock out\"}").andExpect(status().isConflict());
		api.put(admin, "/api/v1/admin/roles/NO_ROLE/permissions", "{\"permissionCodes\":[],\"reason\":\"x\"}")
				.andExpect(status().isNotFound());

		try {
			api.put(admin, path, "{\"permissionCodes\":[\"audit.view\"],\"reason\":\"Trim down\"}")
					.andExpect(status().isOk()).andExpect(jsonPath("$.permissions.length()").value(1));
			assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE action = 'ROLE_PERMISSIONS_CHANGED' AND reason = 'Trim down'")
					.query(Integer.class).single()).isEqualTo(1);
		}
		finally {
			api.put(admin, path, "{\"permissionCodes\":[" + originalJson + "],\"reason\":\"Restore\"}")
					.andExpect(status().isOk());
		}
	}

	@Test
	void anAdminCreatesAUserWhoCanThenSignIn() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String address = email();
		String body = "{\"email\":\"" + address.toUpperCase() + "\",\"displayName\":\"  New Person \",\"password\":\"" + PASSWORD + "\"}";
		String replayKey = key();

		String created = TestApi.bodyOf(api.post(admin, "/api/v1/admin/users", body, "Idempotency-Key", replayKey)
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.displayName").value("New Person")).andExpect(jsonPath("$.roles.length()").value(0))
				.andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(header().string("ETag", "\"0\"")));
		String id = JsonPath.read(created, "$.id");
		assertThat(created).doesNotContain(PASSWORD);

		api.post(admin, "/api/v1/admin/users", body, "Idempotency-Key", replayKey).andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true")).andExpect(jsonPath("$.id").value(id));
		api.post(admin, "/api/v1/admin/users", body, "Idempotency-Key", key()).andExpect(status().isBadRequest());
		api.post(admin, "/api/v1/admin/users", "{\"email\":\"" + email() + "\",\"displayName\":\"x\",\"password\":\"short1\"}",
				"Idempotency-Key", key()).andExpect(status().isBadRequest());
		api.post(admin, "/api/v1/admin/users", "{\"email\":\"not-an-email\",\"displayName\":\"x\",\"password\":\"" + PASSWORD + "\"}",
				"Idempotency-Key", key()).andExpect(status().isBadRequest());

		String loginBody = "{\"identifier\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}";
		api.post(admin, "/api/v1/auth/login", loginBody).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE action = 'USER_CREATED' AND entity_id = CAST(:id AS uuid)")
				.param("id", id).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void theListFiltersAndPages() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String tag = "zz" + UUID.randomUUID().toString().substring(0, 8);
		for (int i = 0; i < 3; i++) {
			createUser(admin, tag + i + "@test.local", "Person " + tag);
			Thread.sleep(5);
		}

		String first = TestApi.bodyOf(api.get(admin, "/api/v1/admin/users?q=" + tag + "&limit=2")
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)));
		String second = TestApi.bodyOf(api.get(admin, "/api/v1/admin/users?q=" + tag + "&limit=2&cursor="
				+ JsonPath.read(first, "$.nextCursor")).andExpect(jsonPath("$.items.length()").value(1)));
		List<String> ids = new ArrayList<>(JsonPath.<List<String>>read(first, "$.items[*].id"));
		ids.addAll(JsonPath.<List<String>>read(second, "$.items[*].id"));
		assertThat(ids).doesNotHaveDuplicates().hasSize(3);

		api.get(admin, "/api/v1/admin/users?q=" + tag + "&status=DISABLED").andExpect(jsonPath("$.items.length()").value(0));
		api.get(admin, "/api/v1/admin/users?status=NONSENSE").andExpect(status().isBadRequest());
		api.get(admin, "/api/v1/admin/users?role=SYSTEM_ADMIN&q=" + w.admin.email().substring(0, 12))
				.andExpect(jsonPath("$.items[0].roles[0]").value("SYSTEM_ADMIN"));
	}

	@Test
	void profileChangesNeedTheCurrentVersion() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String id = createUser(admin, email(), "Before");

		api.patch(admin, "/api/v1/admin/users/" + id, "{\"displayName\":\"After\"}", "If-Match", "\"7\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		api.patch(admin, "/api/v1/admin/users/" + id, "{\"displayName\":\" \"}", "If-Match", "\"0\"")
				.andExpect(status().isBadRequest());
		api.patch(admin, "/api/v1/admin/users/" + id, "{\"displayName\":\"After\"}", "If-Match", "\"0\"")
				.andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("After"))
				.andExpect(header().string("ETag", "\"1\""));
	}

	@Test
	void changingRolesAndScopesTakesEffectAtOnceAndIsAudited() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String address = email();
		String id = createUser(admin, address, "Future dispatcher");
		String path = "/api/v1/admin/users/" + id + "/roles";
		String token = TestApi.bodyOf(api.post(admin, "/api/v1/auth/login",
				"{\"identifier\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isOk()));
		String userToken = JsonPath.read(token, "$.accessToken");
		api.get(userToken, "/api/v1/dispatch/batches/" + UUID.randomUUID()).andExpect(status().isForbidden());
		String v = versionOf(admin, id);

		api.put(admin, path, "{\"roleCodes\":[\"DISPATCHER\"],\"scopeZoneIds\":[],\"reason\":\" \"}", "If-Match", v)
				.andExpect(status().isBadRequest());
		api.put(admin, path, "{\"roleCodes\":[\"WIZARD\"],\"scopeZoneIds\":[],\"reason\":\"test\"}", "If-Match", v)
				.andExpect(status().isBadRequest());
		api.put(admin, path, "{\"roleCodes\":[\"DISPATCHER\"],\"scopeZoneIds\":[\"" + UUID.randomUUID() + "\"],\"reason\":\"test\"}",
				"If-Match", v).andExpect(status().isBadRequest());
		api.put(admin, path, "{\"roleCodes\":[\"DISPATCHER\"],\"scopeZoneIds\":[],\"reason\":\"test\"}", "If-Match", "\"9\"")
				.andExpect(status().isConflict());
		api.put(admin, path, "{\"roleCodes\":[\"DISPATCHER\",\"DISPATCH_OVERRIDER\"],\"scopeZoneIds\":[\"" + w.zoneId
				+ "\"],\"reason\":\"Promoted to dispatch\"}", "If-Match", v).andExpect(status().isOk())
				.andExpect(jsonPath("$.roles.length()").value(2)).andExpect(jsonPath("$.scopeZoneIds[0]").value(w.zoneId.toString()))
				.andExpect(header().exists("ETag"));

		api.get(userToken, "/api/v1/me").andExpect(jsonPath("$.roles[?(@ == 'DISPATCHER')]").exists());
		api.get(userToken, "/api/v1/dispatch/batches/" + UUID.randomUUID()).andExpect(status().isNotFound());

		assertThat(jdbc.sql("SELECT reason FROM audit.audit_records WHERE action = 'ROLE_CHANGED' AND entity_id = CAST(:id AS uuid)")
				.param("id", id).query(String.class).single()).isEqualTo("Promoted to dispatch");

		api.put(admin, "/api/v1/admin/users/" + w.admin.id() + "/roles",
				"{\"roleCodes\":[\"CUSTOMER\"],\"scopeZoneIds\":[],\"reason\":\"self\"}", "If-Match",
				versionOf(admin, w.admin.id().toString()))
				.andExpect(status().isConflict());
	}

	@Test
	void disablingBlocksSignInAndExistingTokensUntilTheAccountIsEnabledAgain() throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String admin = api.login(w.admin);
		String address = email();
		String id = createUser(admin, address, "Soon disabled");
		String loginBody = "{\"identifier\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}";
		String userToken = JsonPath.read(TestApi.bodyOf(api.post(admin, "/api/v1/auth/login", loginBody)), "$.accessToken");
		String path = "/api/v1/admin/users/" + id;
		String v = versionOf(admin, id);

		api.post(admin, path + "/disable", "{\"reason\":\" \"}", "If-Match", v).andExpect(status().isBadRequest());
		api.post(admin, "/api/v1/admin/users/" + w.admin.id() + "/disable", "{\"reason\":\"oops\"}", "If-Match",
				versionOf(admin, w.admin.id().toString()))
				.andExpect(status().isConflict());
		api.post(admin, path + "/disable", "{\"reason\":\"Left the company\"}", "If-Match", v)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
		api.post(admin, path + "/disable", "{\"reason\":\"again\"}", "If-Match", versionOf(admin, id))
				.andExpect(status().isConflict());

		api.post(admin, "/api/v1/auth/login", loginBody).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
		api.get(userToken, "/api/v1/me").andExpect(status().isUnauthorized());
		assertThat(jdbc.sql("SELECT count(*) FROM iam.refresh_sessions WHERE user_id = CAST(:id AS uuid) AND revoked_at IS NULL")
				.param("id", id).query(Integer.class).single()).isZero();

		api.post(admin, path + "/enable", "", "If-Match", versionOf(admin, id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		api.post(admin, "/api/v1/auth/login", loginBody).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT count(DISTINCT action) FROM audit.audit_records WHERE entity_id = CAST(:id AS uuid) AND action IN ('USER_DISABLED','USER_ENABLED')")
				.param("id", id).query(Integer.class).single()).isEqualTo(2);
		assertThat(Instant.now()).isNotNull();
	}
}
