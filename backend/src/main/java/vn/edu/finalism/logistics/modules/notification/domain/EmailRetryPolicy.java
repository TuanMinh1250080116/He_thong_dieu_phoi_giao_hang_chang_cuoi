package vn.edu.finalism.logistics.modules.notification.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class EmailRetryPolicy {

	public static final int MAX_ATTEMPTS = 5;

	private static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1));

	private EmailRetryPolicy() {
	}

	public static Optional<Duration> delayAfterFailure(int attemptsMade) {
		if (attemptsMade < 1 || attemptsMade >= MAX_ATTEMPTS) {
			return Optional.empty();
		}
		return Optional.of(DELAYS.get(attemptsMade - 1));
	}
}
