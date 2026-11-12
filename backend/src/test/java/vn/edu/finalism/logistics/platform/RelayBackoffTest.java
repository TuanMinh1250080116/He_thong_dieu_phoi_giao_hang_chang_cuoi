package vn.edu.finalism.logistics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.platform.event.RelayBackoff;

class RelayBackoffTest {

	@Test
	void growsExponentiallyFromTwoSeconds() {
		assertThat(RelayBackoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
		assertThat(RelayBackoff.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
		assertThat(RelayBackoff.delayAfter(5)).isEqualTo(Duration.ofSeconds(32));
	}

	@Test
	void neverWaitsMoreThanFiveMinutes() {
		assertThat(RelayBackoff.delayAfter(9)).isEqualTo(Duration.ofMinutes(5));
		assertThat(RelayBackoff.delayAfter(50)).isEqualTo(Duration.ofMinutes(5));
	}
}
