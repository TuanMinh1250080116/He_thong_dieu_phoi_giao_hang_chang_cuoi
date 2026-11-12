package vn.edu.finalism.logistics.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class RequestFingerprinterTest {

	private final RequestFingerprinter fingerprinter = new RequestFingerprinter(JsonMapper.builder().build());

	record Body(String reason, int quantity) {
	}

	@Test
	void sameContentInDifferentKeyOrderGivesSameFingerprint() {
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("reason", "late");
		first.put("items", List.of(Map.of("id", 1, "qty", 2)));
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("items", List.of(Map.of("qty", 2, "id", 1)));
		second.put("reason", "late");

		assertThat(fingerprinter.fingerprint("/orders", null, first))
				.isEqualTo(fingerprinter.fingerprint("/orders", null, second));
	}

	@Test
	void worksWithRecordsToo() {
		assertThat(fingerprinter.fingerprint("/x", "\"3\"", new Body("a", 1)))
				.isEqualTo(fingerprinter.fingerprint("/x", "\"3\"", new Body("a", 1)));
	}

	@Test
	void anyDifferenceChangesTheFingerprint() {
		byte[] base = fingerprinter.fingerprint("/orders", "\"1\"", Map.of("reason", "a"));

		assertThat(fingerprinter.fingerprint("/orders", "\"1\"", Map.of("reason", "b"))).isNotEqualTo(base);
		assertThat(fingerprinter.fingerprint("/orders", "\"2\"", Map.of("reason", "a"))).isNotEqualTo(base);
		assertThat(fingerprinter.fingerprint("/other", "\"1\"", Map.of("reason", "a"))).isNotEqualTo(base);
	}

	@Test
	void acceptsMissingBody() {
		assertThat(fingerprinter.fingerprint("/orders/1/cancel", "\"1\"", null)).hasSize(32);
	}
}
