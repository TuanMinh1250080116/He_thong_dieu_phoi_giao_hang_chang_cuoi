package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
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

import vn.edu.finalism.logistics.bootstrap.CorrelationIdFilter;
import vn.edu.finalism.logistics.platform.event.OutboxRelay;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class ExceptionFlowTest {

	@Autowired
	WebApplicationContext context;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	CorrelationIdFilter correlationIdFilter;
	@Autowired
	OutboxRelay relay;

	TestApi api;

	@BeforeEach
	void setUp() {
		jdbc.sql("UPDATE platform.event_publications SET status = 'DISPATCHED', dispatched_at = now() WHERE status IN ('PENDING','FAILED')")
				.update();
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(correlationIdFilter)
				.apply(springSecurity())
				.build();
		api = new TestApi(mvc);
	}

	private record Started(TestWorld world, String customer, String dispatcher, String ops, String driver,
			String orderId, String deliveryId, String stopId) {
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private Started start(boolean startStop) throws Exception {
		TestWorld w = TestWorld.create(jdbc, encoder, true);
		String customer = api.login(w.customer);
		String dispatcher = api.login(w.dispatcher);
		String ops = api.login(w.ops);
		String driver = api.login(w.driverUser);

		Instant now = Instant.now();
		String order = """
				{"saveMode":"SUBMIT",
				 "pickup":{"address":"12 Nguyen Hue","point":{"lat":%s,"lng":%s}},
				 "dropoff":{"address":"45 Le Loi","point":{"lat":%s,"lng":%s}},
				 "recipient":{"name":"Nguyen Van A","contact":"+84900000000"},
				 "package":{"weightG":2500,"lengthCm":30,"widthCm":20,"heightCm":15,"note":null},
				 "timeWindow":{"start":"%s","end":"%s"}}
				""".formatted(w.pickupLat(), w.pickupLng(), w.dropoffLat(), w.dropoffLng(),
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
		if (startStop) {
			stopAction(driver, deliveryId, stopId, "START_STOP");
		}
		return new Started(w, customer, dispatcher, ops, driver, orderId, deliveryId, stopId);
	}

	private String etag(String token, String deliveryId) throws Exception {
		return TestApi.etagOf(api.get(token, "/api/v1/deliveries/" + deliveryId));
	}

	private ResultActions stopActionResult(String driver, String deliveryId, String stopId, String action)
			throws Exception {
		return api.post(driver, "/api/v1/deliveries/" + deliveryId + "/stops/" + stopId + "/actions",
				"{\"action\":\"" + action + "\"}", "If-Match", etag(driver, deliveryId));
	}

	private void stopAction(String driver, String deliveryId, String stopId, String action) throws Exception {
		stopActionResult(driver, deliveryId, stopId, action).andExpect(status().isOk());
	}

	private ResultActions report(Started s, String body) throws Exception {
		return api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/exceptions", body, "Idempotency-Key",
				key());
	}

	private String reportBlocking(Started s) throws Exception {
		return JsonPath.read(TestApi.bodyOf(report(s, """
				{"type":"RECIPIENT_ABSENT","description":"Nobody answered the door","stopId":"%s"}
				""".formatted(s.stopId())).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("OPEN"))
				.andExpect(jsonPath("$.data.impact").value("BLOCKS_STOP"))
				.andExpect(jsonPath("$.data.blocking").value(true))
				.andExpect(header().string("ETag", "\"0\""))), "$.data.id");
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
		api.putBytes(s.driver(), JsonPath.read(TestApi.bodyOf(ticket), "$.uploadUrl"), jpeg)
				.andExpect(status().isNoContent());
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
		api.post(s.driver(), "/api/v1/evidence/" + evidenceId + "/complete", "{\"sha256\":\"" + sha + "\"}")
				.andExpect(status().isOk());
		return evidenceId;
	}

	@Test
	void aBlockingProblemStopsTheStopUntilOpsResolvesItAndTheDriverResumes() throws Exception {
		Started s = start(true);
		stopAction(s.driver(), s.deliveryId(), s.stopId(), "MARK_ARRIVED");
		String exceptionId = reportBlocking(s);

		assertThat(jdbc.sql("SELECT status FROM delivery.delivery_stops WHERE id = CAST(:id AS uuid)")
				.param("id", s.stopId()).query(String.class).single()).isEqualTo("BLOCKED");
		api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/complete", "", "Idempotency-Key", key(),
				"If-Match", etag(s.driver(), s.deliveryId())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMPLETION_PREREQUISITES_MISSING"))
				.andExpect(jsonPath("$.details.blockingExceptionIds[0]").value(exceptionId));
		stopActionResult(s.driver(), s.deliveryId(), s.stopId(), "RESUME_AFTER_EXCEPTION")
				.andExpect(status().isConflict());

		api.post(s.dispatcher(), "/api/v1/exceptions/" + exceptionId + "/investigate",
				"{\"assigneeId\":\"" + s.world().ops.id() + "\",\"note\":\"looking\"}", "If-Match", "\"0\"")
				.andExpect(status().isForbidden());
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/resolve",
				"{\"outcome\":\"RESUME_DELIVERY\",\"note\":\"too early\"}", "Idempotency-Key", key(), "If-Match", "\"0\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_TRANSITION_NOT_ALLOWED"));
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/investigate",
				"{\"assigneeId\":\"" + s.world().ops.id() + "\",\"note\":\"looking\"}", "If-Match", "\"5\"")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/investigate",
				"{\"assigneeId\":\"" + s.world().dispatcher.id() + "\",\"note\":\"looking\"}", "If-Match", "\"0\"")
				.andExpect(status().isBadRequest());
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/investigate",
				"{\"assigneeId\":\"" + s.world().ops.id() + "\",\"note\":\"calling the recipient\"}", "If-Match", "\"0\"")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
				.andExpect(jsonPath("$.data.assignee.id").value(s.world().ops.id().toString()))
				.andExpect(header().string("ETag", "\"1\""));

		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/cancel", "{\"reason\":\"late\"}", "Idempotency-Key",
				key(), "If-Match", "\"1\"").andExpect(status().isConflict());
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/resolve",
				"{\"outcome\":\"MAGIC\",\"note\":\"n\"}", "Idempotency-Key", key(), "If-Match", "\"1\"")
				.andExpect(status().isBadRequest());
		String resolveKey = key();
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/resolve",
				"{\"outcome\":\"RESUME_DELIVERY\",\"note\":\"Recipient is home now\"}", "Idempotency-Key", resolveKey,
				"If-Match", "\"1\"").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"))
				.andExpect(jsonPath("$.data.notes.length()").value(2));
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/resolve",
				"{\"outcome\":\"RESUME_DELIVERY\",\"note\":\"Recipient is home now\"}", "Idempotency-Key", resolveKey,
				"If-Match", "\"1\"").andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));

		assertThat(jdbc.sql("SELECT count(*) FROM delivery.delivery_blocking_refs WHERE exception_id = CAST(:id AS uuid) AND cleared_at IS NULL")
				.param("id", exceptionId).query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT status FROM delivery.delivery_stops WHERE id = CAST(:id AS uuid)")
				.param("id", s.stopId()).query(String.class).single()).isEqualTo("BLOCKED");

		api.get(s.driver(), "/api/v1/exceptions/" + exceptionId).andExpect(status().isOk())
				.andExpect(jsonPath("$.capabilities[?(@ == 'canResumeStop')]").exists())
				.andExpect(jsonPath("$.capabilities[?(@ == 'canResolveException')]").doesNotExist());
		stopAction(s.driver(), s.deliveryId(), s.stopId(), "RESUME_AFTER_EXCEPTION");
		assertThat(jdbc.sql("SELECT status FROM delivery.delivery_stops WHERE id = CAST(:id AS uuid)")
				.param("id", s.stopId()).query(String.class).single()).isEqualTo("IN_PROGRESS");

		String evidenceId = uploadPhoto(s);
		api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/stops/" + s.stopId() + "/pod", """
				{"gps":{"point":{"lat":%s,"lng":%s},"accuracyM":12.5},"isDirectHandoff":true,
				 "recipientName":"Nguyen Van A","evidenceIds":["%s"]}
				""".formatted(s.world().dropoffLat(), s.world().dropoffLng(), evidenceId), "Idempotency-Key", key())
				.andExpect(status().isCreated());
		api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/complete", "", "Idempotency-Key", key(),
				"If-Match", etag(s.driver(), s.deliveryId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("COMPLETED"));

		relay.dispatchBatch();
		Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			List<String> opsTitles = JsonPath.read(TestApi.bodyOf(api.get(s.ops(), "/api/v1/notifications")),
					"$.items[*].title");
			assertThat(opsTitles).contains("Có sự cố giao hàng mới");
			List<String> driverTitles = JsonPath.read(TestApi.bodyOf(api.get(s.driver(), "/api/v1/notifications")),
					"$.items[*].title");
			assertThat(driverTitles).contains("Sự cố đang được xử lý", "Sự cố đã được giải quyết");
		});
		assertThat(jdbc.sql("SELECT count(DISTINCT action) FROM audit.audit_records WHERE action IN ('EXCEPTION_REPORTED','EXCEPTION_INVESTIGATING','EXCEPTION_RESOLVED')")
				.query(Integer.class).single()).isGreaterThanOrEqualTo(3);
	}

	@Test
	void aNonBlockingReportCanBeAnnotatedThenCancelledAndNeverBlocksCompletion() throws Exception {
		Started s = start(false);
		String exceptionId = JsonPath.read(TestApi.bodyOf(report(s, """
				{"type":"ACCESS_DENIED","description":"Guard refused entry at first"}
				""").andExpect(status().isCreated()).andExpect(jsonPath("$.data.impact").value("NON_BLOCKING"))
				.andExpect(jsonPath("$.data.severity").value("LOW"))), "$.data.id");

		api.post(s.dispatcher(), "/api/v1/exceptions/" + exceptionId + "/notes", "{\"body\":\"Called the guard\"}")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.notes[0].type").value("CONTEXT"));
		api.post(s.customer(), "/api/v1/exceptions/" + exceptionId + "/notes", "{\"body\":\"hi\"}")
				.andExpect(status().isForbidden());
		api.post(s.dispatcher(), "/api/v1/exceptions/" + exceptionId + "/notes", "{\"body\":\"  \"}")
				.andExpect(status().isBadRequest());

		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/cancel", "{\"reason\":\" \"}", "Idempotency-Key",
				key(), "If-Match", "\"0\"").andExpect(status().isBadRequest());
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/cancel", "{\"reason\":\"Duplicate report\"}",
				"Idempotency-Key", key(), "If-Match", "\"0\"").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("CANCELLED"))
				.andExpect(jsonPath("$.data.cancellationReason").value("Duplicate report"));
		api.post(s.ops(), "/api/v1/exceptions/" + exceptionId + "/investigate",
				"{\"assigneeId\":\"" + s.world().ops.id() + "\",\"note\":\"x\"}", "If-Match", "\"1\"")
				.andExpect(status().isConflict());
		api.post(s.dispatcher(), "/api/v1/exceptions/" + exceptionId + "/notes", "{\"body\":\"too late\"}")
				.andExpect(status().isConflict());
	}

	@Test
	void reportingIsValidatedAndOnlyThePeopleOnTheDeliveryCanSeeIt() throws Exception {
		Started s = start(true);

		report(s, "{\"type\":\"NOPE\",\"description\":\"x\"}").andExpect(status().isBadRequest());
		report(s, "{\"type\":\"RECIPIENT_ABSENT\",\"description\":\"no stop given\"}")
				.andExpect(status().isBadRequest());
		report(s, "{\"type\":\"ACCESS_DENIED\",\"description\":\"x\",\"stopId\":\"" + UUID.randomUUID() + "\"}")
				.andExpect(status().isNotFound());
		report(s, "{\"type\":\"ACCESS_DENIED\",\"description\":\" \"}").andExpect(status().isBadRequest());
		report(s, "{\"type\":\"ACCESS_DENIED\",\"description\":\"x\",\"severity\":\"CRITICAL\"}")
				.andExpect(status().isBadRequest());

		String body = "{\"type\":\"PACKAGE_DAMAGED\",\"description\":\"Box is crushed\",\"stopId\":\"" + s.stopId() + "\"}";
		String replayKey = key();
		String id = JsonPath.read(TestApi.bodyOf(api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/exceptions",
				body, "Idempotency-Key", replayKey).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.impact").value("CUSTOMER_IMPACTING"))), "$.data.id");
		api.post(s.driver(), "/api/v1/deliveries/" + s.deliveryId() + "/exceptions", body, "Idempotency-Key", replayKey)
				.andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.data.id").value(id));
		assertThat(jdbc.sql("SELECT count(*) FROM exc.delivery_exceptions WHERE delivery_id = CAST(:id AS uuid)")
				.param("id", s.deliveryId()).query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM delivery.delivery_stops WHERE id = CAST(:id AS uuid)")
				.param("id", s.stopId()).query(String.class).single()).isEqualTo("IN_PROGRESS");

		TestWorld.DriverRef other = s.world().addDriver();
		String otherDriver = api.login(other.user());
		api.post(otherDriver, "/api/v1/deliveries/" + s.deliveryId() + "/exceptions",
				"{\"type\":\"ACCESS_DENIED\",\"description\":\"not mine\"}", "Idempotency-Key", key())
				.andExpect(status().isNotFound());
		api.get(otherDriver, "/api/v1/exceptions/" + id).andExpect(status().isNotFound());
		api.get(s.customer(), "/api/v1/exceptions/" + id).andExpect(status().isForbidden());

		api.get(s.ops(), "/api/v1/exceptions?status=OPEN").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].code").exists());
		api.get(s.ops(), "/api/v1/exceptions?status=RESOLVED").andExpect(jsonPath("$.items.length()").value(0));
		api.get(s.driver(), "/api/v1/exceptions").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(id));
		api.get(otherDriver, "/api/v1/exceptions").andExpect(jsonPath("$.items.length()").value(0));
		api.get(s.dispatcher(), "/api/v1/exceptions/" + id).andExpect(status().isOk())
				.andExpect(jsonPath("$.capabilities[?(@ == 'canInvestigateException')]").doesNotExist())
				.andExpect(jsonPath("$.capabilities[?(@ == 'canAddContext')]").exists());
		api.get(s.driver(), "/api/v1/catalogs/exception-types").andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(5));

		relay.dispatchBatch();
		Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			List<String> customerTitles = JsonPath.read(TestApi.bodyOf(api.get(s.customer(), "/api/v1/notifications")),
					"$.items[*].title");
			assertThat(customerTitles).contains("Đơn hàng của bạn gặp sự cố khi giao");
		});
	}

	@Test
	void pagingWalksTheQueueNewestFirstWithoutRepeats() throws Exception {
		Started s = start(false);
		for (int i = 0; i < 3; i++) {
			report(s, "{\"type\":\"ACCESS_DENIED\",\"description\":\"report " + i + "\"}").andExpect(status().isCreated());
			Thread.sleep(5);
		}
		String first = TestApi.bodyOf(api.get(s.ops(), "/api/v1/exceptions?deliveryId=" + s.deliveryId() + "&limit=2")
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)));
		String cursor = JsonPath.read(first, "$.nextCursor");
		String second = TestApi.bodyOf(api.get(s.ops(),
				"/api/v1/exceptions?deliveryId=" + s.deliveryId() + "&limit=2&cursor=" + cursor)
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)));

		List<String> ids = JsonPath.read(first, "$.items[*].id");
		ids = new java.util.ArrayList<>(ids);
		ids.addAll(JsonPath.<List<String>>read(second, "$.items[*].id"));
		assertThat(ids).doesNotHaveDuplicates().hasSize(3);
		assertThat(JsonPath.<Object>read(second, "$.nextCursor")).isNull();
	}
}
