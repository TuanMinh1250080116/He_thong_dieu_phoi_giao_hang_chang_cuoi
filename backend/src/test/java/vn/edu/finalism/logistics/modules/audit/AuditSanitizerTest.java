package vn.edu.finalism.logistics.modules.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditActorType;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditSanitizer;

class AuditSanitizerTest {

	@Test
	void redactsForbiddenKeysIgnoringCaseAndSeparators() {
		Map<String, Object> input = Map.of(
				"password", "p1",
				"password_hash", "p2",
				"refreshToken", "p3",
				"Code-Hmac", "p4",
				"status", "ACTIVE");

		Map<String, Object> result = AuditSanitizer.sanitize(input);

		assertThat(result).containsEntry("status", "ACTIVE");
		assertThat(result.get("password")).isEqualTo(AuditSanitizer.REDACTED);
		assertThat(result.get("password_hash")).isEqualTo(AuditSanitizer.REDACTED);
		assertThat(result.get("refreshToken")).isEqualTo(AuditSanitizer.REDACTED);
		assertThat(result.get("Code-Hmac")).isEqualTo(AuditSanitizer.REDACTED);
	}

	@Test
	void redactsInsideNestedMapsAndLists() {
		Map<String, Object> input = Map.of(
				"user", Map.of("name", "An", "secret", "s3cret"),
				"items", List.of(Map.of("otp", "123456", "id", 7)));

		Map<String, Object> result = AuditSanitizer.sanitize(input);

		assertThat(result.toString()).doesNotContain("s3cret").doesNotContain("123456");
		assertThat(result.toString()).contains("An").contains("7");
	}

	@Test
	void doesNotChangeTheInputAndKeepsNull() {
		Map<String, Object> input = new java.util.HashMap<>(Map.of("password", "keep-me"));
		AuditSanitizer.sanitize(input);
		assertThat(input.get("password")).isEqualTo("keep-me");
		assertThat(AuditSanitizer.sanitize(null)).isNull();
	}

	@Test
	void userEntryNeedsAnActor() {
		assertThatThrownBy(() -> new AuditEntry(AuditAction.ORDER_CREATED, AuditActorType.USER, null,
				"ORDER", UUID.randomUUID(), null, null, null, null, "corr-1", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void entryNeedsACorrelationId() {
		assertThatThrownBy(() -> new AuditEntry(AuditAction.ORDER_CREATED, AuditActorType.SYSTEM, null,
				"ORDER", UUID.randomUUID(), null, null, null, null, " ", null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
