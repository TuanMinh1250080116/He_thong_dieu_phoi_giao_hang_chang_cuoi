package vn.edu.finalism.logistics.bootstrap;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.shared.ClockPort;

@Component
class SystemClockAdapter implements ClockPort {

	private final Clock clock = Clock.systemUTC();

	@Override
	public Instant now() {
		return Instant.now(clock);
	}
}
