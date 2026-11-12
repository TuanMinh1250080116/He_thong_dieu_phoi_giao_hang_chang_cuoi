package vn.edu.finalism.logistics.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.driverfleet.domain.Availability;
import vn.edu.finalism.logistics.modules.driverfleet.domain.Driver;
import vn.edu.finalism.logistics.platform.ratelimit.RateLimiter;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

class DriverAvailabilityTest {

	private static Driver driver(Availability availability, boolean active) {
		return new Driver(UUID.randomUUID(), UUID.randomUUID(), "DRV-1", "Driver", active, availability, null, 0);
	}

	@Test
	void anOfflineActiveDriverCanGoOnlineAndBackOffline() {
		Driver online = driver(Availability.OFFLINE, true).goOnline(false);
		assertThat(online.availability()).isEqualTo(Availability.AVAILABLE);
		assertThat(online.goOffline().availability()).isEqualTo(Availability.OFFLINE);
	}

	@Test
	void reservedAndBusyDriversCannotChangeAvailabilityThemselves() {
		for (Availability state : new Availability[] { Availability.RESERVED, Availability.BUSY }) {
			assertThatThrownBy(() -> driver(state, true).goOffline()).isInstanceOf(BusinessException.class);
			assertThatThrownBy(() -> driver(state, true).goOnline(false)).isInstanceOf(BusinessException.class);
		}
	}

	@Test
	void anOpenObligationBlocksGoingOnlineEvenIfTheStateLooksFree() {
		assertThatThrownBy(() -> driver(Availability.OFFLINE, true).goOnline(true)).isInstanceOf(BusinessException.class);
	}

	@Test
	void anInactiveDriverCannotGoOnline() {
		assertThatThrownBy(() -> driver(Availability.OFFLINE, false).goOnline(false)).isInstanceOf(BusinessException.class);
	}

	@Test
	void deactivatingForcesOfflineButNotWhileThereIsAnObligation() {
		Driver deactivated = driver(Availability.AVAILABLE, true).deactivate();
		assertThat(deactivated.active()).isFalse();
		assertThat(deactivated.availability()).isEqualTo(Availability.OFFLINE);
		assertThat(deactivated.activate().active()).isTrue();
		assertThatThrownBy(() -> driver(Availability.RESERVED, true).deactivate()).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> driver(Availability.BUSY, true).deactivate()).isInstanceOf(BusinessException.class);
	}

	@Test
	void theRateLimiterAllowsTheLimitThenAsksToWait() {
		Instant[] now = { Instant.parse("2026-10-27T10:00:00Z") };
		ClockPort clock = () -> now[0];
		RateLimiter limiter = new RateLimiter(clock);

		for (int i = 0; i < 6; i++) {
			limiter.check("k", 6, Duration.ofMinutes(1));
		}
		assertThatThrownBy(() -> limiter.check("k", 6, Duration.ofMinutes(1)))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
					assertThat(e.details()).containsKey("retryAfterSeconds");
				});
		limiter.check("other", 6, Duration.ofMinutes(1));

		now[0] = now[0].plusSeconds(61);
		limiter.check("k", 6, Duration.ofMinutes(1));
	}
}
