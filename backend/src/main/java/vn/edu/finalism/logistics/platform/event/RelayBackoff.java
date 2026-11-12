package vn.edu.finalism.logistics.platform.event;

import java.time.Duration;

public final class RelayBackoff {

	private static final long MAX_SECONDS = 300;

	private RelayBackoff() {
	}

	public static Duration delayAfter(int attempts) {
		long seconds = attempts >= 9 ? MAX_SECONDS : Math.min(MAX_SECONDS, 1L << Math.max(attempts, 0));
		return Duration.ofSeconds(seconds);
	}
}
