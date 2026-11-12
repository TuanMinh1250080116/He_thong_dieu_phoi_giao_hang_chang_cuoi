package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuthFlowTest {

	private static final String PASSWORD = "Correct-horse-1";

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
	}

	private String createUser(String status, String roleCode) {
		String email = "user-" + UUID.randomUUID() + "@test.local";
		UUID id = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbc.sql("""
				INSERT INTO iam.users (id, email, display_name, password_hash, status, created_at, updated_at)
				VALUES (:id, :email, 'Test User', :hash, :status, :now, :now)
				""")
				.param("id", id).param("email", email).param("hash", encoder.encode(PASSWORD))
				.param("status", status).param("now", now).update();
		jdbc.sql("""
				INSERT INTO iam.user_roles (user_id, role_id, granted_at)
				SELECT :id, r.id, :now FROM iam.roles r WHERE r.code = :role
				""")
				.param("id", id).param("role", roleCode).param("now", now).update();
		return email;
	}

	private ResultActions login(String email, String password) throws Exception {
		String body = "{\"identifier\":\"" + email + "\",\"password\":\"" + password + "\"}";
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh")
				.header("X-Requested-With", "fetch")
				.cookie(new Cookie("refresh_token", refreshToken)));
	}

	private static String refreshCookie(ResultActions result) {
		return result.andReturn().getResponse().getCookie("refresh_token").getValue();
	}

	@Test
	void loginReturnsAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
		String email = createUser("ACTIVE", "DISPATCHER");

		ResultActions result = login(email, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.user.roles[0]").value("DISPATCHER"))
				.andExpect(header().string("Cache-Control", "no-store"));

		String setCookie = result.andReturn().getResponse().getHeader("Set-Cookie");
		assertThat(setCookie).contains("refresh_token=", "HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");
		assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("password");
	}

	@Test
	void wrongPasswordAndUnknownAccountLookIdentical() throws Exception {
		String email = createUser("ACTIVE", "CUSTOMER");

		login(email, "wrong-password").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
		login("nobody@test.local", "whatever").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
	}

	@Test
	void accountLocksAfterFiveWrongPasswordsEvenForCorrectPassword() throws Exception {
		String email = createUser("ACTIVE", "CUSTOMER");
		for (int i = 0; i < 5; i++) {
			login(email, "wrong-password").andExpect(status().isUnauthorized());
		}

		login(email, PASSWORD).andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	void disabledAccountIsRejectedOnlyAfterCorrectPassword() throws Exception {
		String email = createUser("DISABLED", "CUSTOMER");

		login(email, PASSWORD).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
		login(email, "wrong-password").andExpect(status().isUnauthorized());
	}

	@Test
	void meReturnsRolesAndPermissionsFromDatabase() throws Exception {
		String email = createUser("ACTIVE", "DISPATCHER");
		String accessToken = JsonPath.read(
				login(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.accessToken");

		mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.roles[0]").value("DISPATCHER"))
				.andExpect(jsonPath("$.permissions[?(@ == 'dispatch.assign')]").isNotEmpty())
				.andExpect(jsonPath("$.permissions[?(@ == 'dispatch.recommendation.override')]").isEmpty());
	}

	@Test
	void meWithoutTokenIsProblemJsonWithCorrelationId() throws Exception {
		mvc.perform(get("/api/v1/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty())
				.andExpect(header().exists("X-Correlation-Id"));
	}

	@Test
	void refreshRotatesTokenAndReuseOfOldTokenRevokesEverything() throws Exception {
		String email = createUser("ACTIVE", "DRIVER");
		String first = refreshCookie(login(email, PASSWORD));

		ResultActions second = refresh(first).andExpect(status().isOk());
		String secondToken = refreshCookie(second);
		assertThat(secondToken).isNotEqualTo(first);

		refresh(first).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		refresh(secondToken).andExpect(status().isUnauthorized());
	}

	@Test
	void refreshWithoutCsrfHeaderIsForbidden() throws Exception {
		String email = createUser("ACTIVE", "DRIVER");
		String token = refreshCookie(login(email, PASSWORD));

		mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", token)))
				.andExpect(status().isForbidden());
	}

	@Test
	void logoutRevokesRefreshToken() throws Exception {
		String email = createUser("ACTIVE", "CUSTOMER");
		ResultActions login = login(email, PASSWORD);
		String accessToken = JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.accessToken");
		String token = refreshCookie(login);

		mvc.perform(post("/api/v1/auth/logout")
				.header("Authorization", "Bearer " + accessToken)
				.cookie(new Cookie("refresh_token", token)))
				.andExpect(status().isNoContent());

		refresh(token).andExpect(status().isUnauthorized());
	}
}
