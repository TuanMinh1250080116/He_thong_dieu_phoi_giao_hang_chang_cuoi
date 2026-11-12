package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
import vn.edu.finalism.logistics.modules.proof.application.port.OtpSenderPort;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "app.events.relay.enabled=false", "app.proof.evidence-url-ttl=3s" })
class PodOtpFlowTest {

	static class CapturingOtpSender implements OtpSenderPort {

		final List<String> codes = new ArrayList<>();
		volatile String lastEmail;

		@Override
		public void send(String recipientEmail, String recipientName, String code, Instant expiresAt) {
			codes.add(code);
			lastEmail = recipientEmail;
		}

		String lastCode() {
			return codes.get(codes.size() - 1);
		}
	}

	@TestConfiguration
	static class Config {

		@Bean
		@Primary
		CapturingOtpSender capturingOtpSender() {
			return new CapturingOtpSender();
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
	@Autowired
	CapturingOtpSender sender;

	TestApi api;

	@BeforeEach
	void setUp() {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		api = new TestApi(mvc);
		sender.codes.clear();
	}

	private record Started(TestWorld world, String customer, String otherCustomer, String dispatcher, String driver,
			String orderId, String deliveryId, String stopId) {
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private Started start(String contact) throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String otherCustomer = api.login(w.otherCustomer);
		String dispatcher = api.login(w.dispatcher);
		String driver = api.login(w.driverUser);

		Instant now = Instant.now();
		String order = """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"Nguyen Van A","contact":"%s"},
				 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(), contact,
				now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
		String orderId = JsonPath.read(TestApi.bodyOf(api.post(customer, "/api/v1/orders", order, "Idempotency-Key", key())
				.andExpect(status().isCreated())), "$.data.id");
		String batchBody = "{\"zoneId\":\"" + w.zoneId + "\",\"orderIds\":[\"" + orderId + "\"]}";
		String batchId = JsonPath.read(TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches", batchBody,
				"Idempotency-Key", key()).andExpect(status().isCreated())), "$.data.id");
		api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/lock", "", "If-Match", "\"0\"")
				.andExpect(status().isOk());
		String rec = TestApi.bodyOf(api.post(dispatcher, "/api/v1/dispatch/batches/" + batchId + "/recommendations", "",
				"If-Match", "\"1\"").andExpect(status().isCreated()));
		String decisionId = JsonPath.read(rec, "$.data.decisionId");
		String snapshotId = JsonPath.read(rec, "$.data.snapshot.id");
		ResultActions assigned = api.post(dispatcher, "/api/v1/dispatch/decisions/" + decisionId + "/assign",
				"{\"snapshotId\":\"" + snapshotId + "\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isOk());
		String assignmentId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.id");
		String deliveryId = JsonPath.read(TestApi.bodyOf(assigned), "$.data.deliveryId");
		api.post(driver, "/api/v1/assignments/" + assignmentId + "/accept", "", "Idempotency-Key", key(), "If-Match",
				"\"2\"").andExpect(status().isOk());
		api.post(driver, "/api/v1/deliveries/" + deliveryId + "/start", "", "Idempotency-Key", key(), "If-Match",
				etag(driver, deliveryId)).andExpect(status().isOk());
		String stopId = JsonPath.read(TestApi.bodyOf(api.get(driver, "/api/v1/deliveries/" + deliveryId)),
				"$.stops[0].id");
		for (String action : List.of("START_STOP", "MARK_ARRIVED")) {
			api.post(driver, "/api/v1/deliveries/" + deliveryId + "/stops/" + stopId + "/actions",
					"{\"action\":\"" + action + "\"}", "If-Match", etag(driver, deliveryId)).andExpect(status().isOk());
		}
		return new Started(w, customer, otherCustomer, dispatcher, driver, orderId, deliveryId, stopId);
	}

	private String etag(String token, String deliveryId) throws Exception {
		return TestApi.etagOf(api.get(token, "/api/v1/deliveries/" + deliveryId));
	}

	private ResultActions issue(Started s, String idempotencyKey) throws Exception {
		return api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/stops/" + s.stopId() + "/otp-challenges",
				"", "Idempotency-Key", idempotencyKey);
	}

	private ResultActions verify(Started s, String challengeId, String code) throws Exception {
		return api.post(s.driver(), "/api/v1/otp-challenges/" + challengeId + "/verify", "{\"code\":\"" + code + "\"}");
	}

	private String wrongCode(String right) {
		return right.equals("000000") ? "111111" : "000000";
	}

	private ResultActions submit(Started s, String body) throws Exception {
		return api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/stops/" + s.stopId() + "/pod", body,
				"Idempotency-Key", key());
	}

	private String podBody(Started s, String extra) {
		return """
				{"gps":{"point":{"lat":%s,"lng":%s},"accuracyM":12.5},
				 "isDirectHandoff":true,"recipientName":"Nguyen Van A"%s}
				""".formatted(s.world().dropoffLat(), s.world().dropoffLng(), extra);
	}

	private String uploadPhoto(Started s) throws Exception {
		byte[] jpeg = new byte[2048];
		jpeg[0] = (byte) 0xFF;
		jpeg[1] = (byte) 0xD8;
		jpeg[2] = (byte) 0xFF;
		for (int i = 3; i < jpeg.length; i++) {
			jpeg[i] = (byte) (i % 251);
		}
		ResultActions ticket = api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/evidence-uploads",
				"{\"purpose\":\"POD\",\"kind\":\"PHOTO\",\"stopId\":\"" + s.stopId() + "\",\"mimeType\":\"image/jpeg\","
						+ "\"sizeBytes\":" + jpeg.length + "}").andExpect(status().isCreated());
		String evidenceId = JsonPath.read(TestApi.bodyOf(ticket), "$.evidenceId");
		String uploadUrl = JsonPath.read(TestApi.bodyOf(ticket), "$.uploadUrl");
		api.putBytes(s.driver(), uploadUrl, jpeg).andExpect(status().isNoContent());
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
		api.post(s.driver(), "/api/v1/evidence/" + evidenceId + "/complete", "{\"sha256\":\"" + sha + "\"}")
				.andExpect(status().isOk());
		return evidenceId;
	}

	@Test
	void otpAloneSatisfiesThePolicyOnceVerified() throws Exception {
		Started s = start("recipient@test.local");
		String issueKey = key();

		ResultActions issued = issue(s, issueKey).andExpect(status().isCreated())
				.andExpect(jsonPath("$.channel").value("EMAIL")).andExpect(jsonPath("$.maxAttempts").value(5))
				.andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.codeHmac").doesNotExist());
		String challengeId = JsonPath.read(TestApi.bodyOf(issued), "$.challengeId");
		String code = sender.lastCode();
		assertThat(code).matches("\\d{6}");
		assertThat(sender.lastEmail).isEqualTo("recipient@test.local");
		assertThat(TestApi.bodyOf(issued)).doesNotContain(code);

		issue(s, issueKey).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.challengeId").value(challengeId));
		assertThat(sender.codes).hasSize(1);

		submit(s, podBody(s, ",\"otpChallengeId\":\"" + challengeId + "\""))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("OTP_INVALID_OR_EXPIRED"));

		verify(s, challengeId, wrongCode(code)).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("OTP_INVALID_OR_EXPIRED"));
		assertThat(jdbc.sql("SELECT attempts FROM proof.otp_challenges WHERE id = CAST(:id AS uuid)")
				.param("id", challengeId).query(Integer.class).single()).isEqualTo(1);

		verify(s, challengeId, code).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
		byte[] stored = jdbc.sql("SELECT code_hmac FROM proof.otp_challenges WHERE id = CAST(:id AS uuid)")
				.param("id", challengeId).query(byte[].class).single();
		assertThat(stored).hasSize(32);
		assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(code);

		String pod = TestApi.bodyOf(submit(s, podBody(s, ",\"otpChallengeId\":\"" + challengeId + "\""))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.otpStatus").value("VERIFIED"))
				.andExpect(jsonPath("$.data.evidenceTypes.length()").value(0)));
		assertThat(pod).doesNotContain(code);

		api.get(s.customer(), "/api/v1/orders/" + s.orderId() + "/pod").andExpect(status().isOk())
				.andExpect(jsonPath("$.otpStatus").value("VERIFIED"));
		api.get(s.otherCustomer(), "/api/v1/orders/" + s.orderId() + "/pod").andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT otp_verified FROM proof.proofs_of_delivery WHERE order_id = CAST(:id AS uuid)")
				.param("id", s.orderId()).query(Boolean.class).single()).isTrue();
	}

	@Test
	void fiveWrongCodesLockTheChallengeAndTheRightCodeNoLongerWorks() throws Exception {
		Started s = start("recipient@test.local");
		String challengeId = JsonPath.read(TestApi.bodyOf(issue(s, key()).andExpect(status().isCreated())),
				"$.challengeId");
		String code = sender.lastCode();

		for (int i = 0; i < 4; i++) {
			verify(s, challengeId, wrongCode(code)).andExpect(status().isUnprocessableContent());
		}
		verify(s, challengeId, wrongCode(code)).andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("OTP_LOCKED"));
		verify(s, challengeId, code).andExpect(status().isLocked());
		assertThat(jdbc.sql("SELECT status FROM proof.otp_challenges WHERE id = CAST(:id AS uuid)")
				.param("id", challengeId).query(String.class).single()).isEqualTo("LOCKED");
		submit(s, podBody(s, ",\"otpChallengeId\":\"" + challengeId + "\"")).andExpect(status().isUnprocessableContent());
	}

	@Test
	void issuingIsRateLimitedAndANewCodeReplacesTheOldOne() throws Exception {
		Started s = start("recipient@test.local");
		String first = JsonPath.read(TestApi.bodyOf(issue(s, key()).andExpect(status().isCreated())), "$.challengeId");
		String firstCode = sender.lastCode();
		issue(s, key()).andExpect(status().isCreated());
		issue(s, key()).andExpect(status().isCreated());
		issue(s, key()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));

		verify(s, first, firstCode).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("OTP_INVALID_OR_EXPIRED"));
		assertThat(jdbc.sql("SELECT count(*) FROM proof.otp_challenges WHERE stop_id = CAST(:id AS uuid) AND status = 'ISSUED'")
				.param("id", s.stopId()).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void aRecipientWithoutEmailCannotReceiveACode() throws Exception {
		Started s = start("+84900000000");
		issue(s, key()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(sender.codes).isEmpty();
	}

	@Test
	void anotherDriverCannotVerifyTheCode() throws Exception {
		Started s = start("recipient@test.local");
		String challengeId = JsonPath.read(TestApi.bodyOf(issue(s, key()).andExpect(status().isCreated())),
				"$.challengeId");
		TestWorld.DriverRef other = s.world().addDriver();
		String otherToken = api.login(other.user());
		api.post(otherToken, "/api/v1/otp-challenges/" + challengeId + "/verify",
				"{\"code\":\"" + sender.lastCode() + "\"}").andExpect(status().isNotFound());
	}

	@Test
	void evidenceIsViewedThroughAShortLivedSignedLinkOnlyByAuthorizedPeople() throws Exception {
		Started s = start("recipient@test.local");
		String evidenceId = uploadPhoto(s);
		String podId = JsonPath.read(TestApi.bodyOf(submit(s, podBody(s, ",\"evidenceIds\":[\"" + evidenceId + "\"]"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.otpStatus").value("NOT_VERIFIED"))
				.andExpect(jsonPath("$.data.evidence[0].id").value(evidenceId))), "$.data.id");

		api.get(s.customer(), "/api/v1/pods/" + podId).andExpect(status().isOk())
				.andExpect(jsonPath("$.evidence[0].kind").value("PHOTO"));

		api.post(s.otherCustomer(), "/api/v1/pods/" + podId + "/evidence/" + evidenceId + "/access", "")
				.andExpect(status().isNotFound());
		api.post(s.customer(), "/api/v1/pods/" + podId + "/evidence/" + UUID.randomUUID() + "/access", "")
				.andExpect(status().isNotFound());

		ResultActions granted = api.post(s.customer(), "/api/v1/pods/" + podId + "/evidence/" + evidenceId + "/access", "")
				.andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").exists());
		String url = JsonPath.read(TestApi.bodyOf(granted), "$.url");
		assertThat(url).startsWith("/api/v1/evidence/view/");

		byte[] viewed = anonymousGet(url).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/jpeg")).andExpect(header().string("Cache-Control", "no-store"))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(viewed).hasSize(2048);

		anonymousGet(url.substring(0, url.length() - 3) + "abc").andExpect(status().isNotFound());
		anonymousGet("/api/v1/evidence/view/not-a-token").andExpect(status().isNotFound());

		Thread.sleep(3500);
		anonymousGet(url).andExpect(status().isNotFound());
	}

	private ResultActions anonymousGet(String path) throws Exception {
		return api.getAnonymous(path);
	}
}
