package vn.edu.finalism.logistics.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.exception.domain.DeliveryException;
import vn.edu.finalism.logistics.modules.exception.domain.ExceptionStatus;
import vn.edu.finalism.logistics.shared.BusinessException;

class DeliveryExceptionTest {

	private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
	private final UUID manager = UUID.randomUUID();

	private DeliveryException open(String impact) {
		return DeliveryException.report(UUID.randomUUID(), "EXC-000001", UUID.randomUUID(), UUID.randomUUID(), null,
				"RECIPIENT_ABSENT", "MEDIUM", impact, "  Nobody opened the door  ", UUID.randomUUID(), NOW);
	}

	@Test
	void reportingStartsOpenAndTrimsTheDescription() {
		DeliveryException e = open("BLOCKS_STOP");

		assertThat(e.status()).isEqualTo(ExceptionStatus.OPEN);
		assertThat(e.description()).isEqualTo("Nobody opened the door");
		assertThat(e.version()).isZero();
	}

	@Test
	void aBlankDescriptionOrUnknownSeverityIsRejected() {
		assertThatThrownBy(() -> DeliveryException.report(UUID.randomUUID(), "EXC-1", UUID.randomUUID(), null, null,
				"X", "LOW", "NON_BLOCKING", "  ", UUID.randomUUID(), NOW)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> DeliveryException.report(UUID.randomUUID(), "EXC-1", UUID.randomUUID(), null, null,
				"X", "URGENT", "NON_BLOCKING", "text", UUID.randomUUID(), NOW)).isInstanceOf(BusinessException.class);
	}

	@Test
	void impactDecidesWhetherAndWhatItBlocks() {
		assertThat(open("BLOCKS_STOP").blockingScope()).isEqualTo("STOP");
		assertThat(open("BLOCKS_DELIVERY_COMPLETION").blockingScope()).isEqualTo("DELIVERY");
		assertThat(open("CUSTOMER_IMPACTING").blocking()).isFalse();
		assertThat(open("NON_BLOCKING").blocking()).isFalse();
	}

	@Test
	void theHappyPathGoesOpenInvestigatingResolved() {
		DeliveryException investigating = open("BLOCKS_STOP").investigate(manager, NOW.plusSeconds(60));
		DeliveryException resolved = investigating.resolve("RESUME_DELIVERY", " Called the recipient ", manager,
				NOW.plusSeconds(120));

		assertThat(investigating.status()).isEqualTo(ExceptionStatus.INVESTIGATING);
		assertThat(investigating.assigneeUserId()).isEqualTo(manager);
		assertThat(resolved.status()).isEqualTo(ExceptionStatus.RESOLVED);
		assertThat(resolved.resolutionNote()).isEqualTo("Called the recipient");
		assertThat(resolved.closedBy()).isEqualTo(manager);
		assertThat(resolved.closedAt()).isEqualTo(NOW.plusSeconds(120));
	}

	@Test
	void resolvingNeedsAnInvestigationAValidOutcomeAndANote() {
		DeliveryException open = open("BLOCKS_STOP");
		DeliveryException investigating = open.investigate(manager, NOW);

		assertThatThrownBy(() -> open.resolve("RESUME_DELIVERY", "note", manager, NOW))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> investigating.resolve("MAGIC", "note", manager, NOW))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> investigating.resolve("RESUME_DELIVERY", " ", manager, NOW))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void onlyAnOpenExceptionCanBeCancelledAndNeedsAReason() {
		DeliveryException open = open("NON_BLOCKING");

		DeliveryException cancelled = open.cancel("Duplicate report", manager, NOW);

		assertThat(cancelled.status()).isEqualTo(ExceptionStatus.CANCELLED);
		assertThat(cancelled.cancellationReason()).isEqualTo("Duplicate report");
		assertThat(cancelled.investigatingAt()).isNull();
		assertThatThrownBy(() -> open.cancel(" ", manager, NOW)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> open.investigate(manager, NOW).cancel("late", manager, NOW))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void closedExceptionsCannotChangeAgain() {
		DeliveryException resolved = open("NON_BLOCKING").investigate(manager, NOW).resolve("NO_ACTION_NEEDED",
				"Nothing to do", manager, NOW);

		assertThatThrownBy(() -> resolved.investigate(manager, NOW)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> resolved.cancel("x", manager, NOW)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> resolved.resolve("NO_ACTION_NEEDED", "again", manager, NOW))
				.isInstanceOf(BusinessException.class);
	}
}
