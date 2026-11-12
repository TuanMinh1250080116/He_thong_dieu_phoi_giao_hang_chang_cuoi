package vn.edu.finalism.logistics.modules.administration.application;

import java.time.Duration;
import java.time.Instant;

import vn.edu.finalism.logistics.shared.BusinessException;

final class ScheduleRules {

	static final Duration MIN_LEAD = Duration.ofMinutes(1);
	static final Duration MAX_LEAD = Duration.ofDays(365);

	private ScheduleRules() {
	}

	static void requireValid(Instant effectiveFrom, Instant now) {
		if (effectiveFrom.isBefore(now.plus(MIN_LEAD))) {
			throw BusinessException.validation("effectiveFrom", "TOO_SOON",
					"The effective time must be at least one minute from now.");
		}
		if (effectiveFrom.isAfter(now.plus(MAX_LEAD))) {
			throw BusinessException.validation("effectiveFrom", "TOO_FAR",
					"The effective time must be within one year.");
		}
	}
}
