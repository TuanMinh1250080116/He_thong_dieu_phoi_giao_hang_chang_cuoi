package vn.edu.finalism.logistics.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

	@Test
	void acceptsUuidAndBoundaryLengths() {
		assertThat(new IdempotencyKey(UUID.randomUUID().toString()).value()).hasSize(36);
		assertThat(new IdempotencyKey("a".repeat(8)).value()).hasSize(8);
		assertThat(new IdempotencyKey("a".repeat(128)).value()).hasSize(128);
	}

	@Test
	void rejectsMissingKeyWithRequiredCode() {
		assertThatThrownBy(() -> IdempotencyKey.fromHeader(null))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.details()).containsEntry("field", "Idempotency-Key")
							.containsEntry("fieldCode", "REQUIRED");
				});
	}

	@Test
	void rejectsTooShortTooLongAndUnsafeCharacters() {
		assertThatThrownBy(() -> new IdempotencyKey("a".repeat(7))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> new IdempotencyKey("a".repeat(129))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> new IdempotencyKey("has space inside")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> new IdempotencyKey("line\nbreak-key")).isInstanceOf(BusinessException.class);
	}
}
