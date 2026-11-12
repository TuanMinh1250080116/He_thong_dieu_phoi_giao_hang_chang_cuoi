package vn.edu.finalism.logistics;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.events.relay.enabled=false", "app.rate-limit.login-per-minute=4",
		"app.rate-limit.otp-verify-per-minute=2", "app.rate-limit.user-per-minute=15"})
class RateLimitFlowTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;

	@Test
	void loginOtpAndPerUserLimitsReturn429WithRetryAfterAndDoNotAffectOtherUsers() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		TestApi api = new TestApi(mvc);
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String dispatcher = api.login(w.dispatcher);
		String customer = api.login(w.customer);
		String driver = api.login(w.driverUser);

		String bad = "{\"identifier\":\"" + w.customer.email() + "\",\"password\":\"wrong-password-1\"}";
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(bad)).andExpect(status().isUnauthorized());
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(bad)).andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.retryable").value(true));

		String verify = "/api/v1/otp-challenges/" + UUID.randomUUID() + "/verify";
		api.post(driver, verify, "{\"code\":\"123456\"}").andExpect(status().is4xxClientError());
		api.post(driver, verify, "{\"code\":\"123456\"}").andExpect(status().is4xxClientError());
		api.post(driver, verify, "{\"code\":\"123456\"}").andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"));

		for (int i = 0; i < 15; i++) {
			api.get(customer, "/api/v1/me").andExpect(status().isOk());
		}
		api.get(customer, "/api/v1/me").andExpect(status().isTooManyRequests());
		api.get(dispatcher, "/api/v1/me").andExpect(status().isOk());
	}
}
