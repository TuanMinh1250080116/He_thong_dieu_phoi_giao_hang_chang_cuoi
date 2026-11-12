package vn.edu.finalism.logistics.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.notification.domain.EmailRetryPolicy;
import vn.edu.finalism.logistics.modules.notification.domain.NotificationTemplates;

class EmailRetryPolicyTest {

	@Test
	void delaysGrowAfterEachFailure() {
		assertThat(EmailRetryPolicy.delayAfterFailure(1)).contains(Duration.ofMinutes(1));
		assertThat(EmailRetryPolicy.delayAfterFailure(2)).contains(Duration.ofMinutes(5));
		assertThat(EmailRetryPolicy.delayAfterFailure(3)).contains(Duration.ofMinutes(15));
		assertThat(EmailRetryPolicy.delayAfterFailure(4)).contains(Duration.ofHours(1));
	}

	@Test
	void theLastAllowedAttemptGivesUp() {
		assertThat(EmailRetryPolicy.delayAfterFailure(EmailRetryPolicy.MAX_ATTEMPTS)).isEmpty();
		assertThat(EmailRetryPolicy.delayAfterFailure(EmailRetryPolicy.MAX_ATTEMPTS + 3)).isEmpty();
	}

	@Test
	void anInvalidAttemptCountNeverSchedulesARetry() {
		assertThat(EmailRetryPolicy.delayAfterFailure(0)).isEmpty();
		assertThat(EmailRetryPolicy.delayAfterFailure(-1)).isEmpty();
	}

	@Test
	void onlyCustomerFacingAndAssignmentTemplatesAreEmailed() {
		assertThat(NotificationTemplates.sendsEmail("ORDER_CREATED")).isTrue();
		assertThat(NotificationTemplates.sendsEmail("DRIVER_ASSIGNED")).isTrue();
		assertThat(NotificationTemplates.sendsEmail("DELIVERY_COMPLETED")).isTrue();
		assertThat(NotificationTemplates.sendsEmail("DRIVER_ASSIGNMENT_REJECTED")).isFalse();
		assertThat(NotificationTemplates.sendsEmail("EXCEPTION_REPORTED")).isFalse();
	}
}
