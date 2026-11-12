package vn.edu.finalism.logistics.modules.identityaccess.domain;

import java.time.Duration;

public final class LoginPolicy {

	public static final int MAX_FAILED_ATTEMPTS = 5;
	public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	private LoginPolicy() {
	}
}
