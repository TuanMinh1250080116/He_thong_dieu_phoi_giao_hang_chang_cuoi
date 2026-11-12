package vn.edu.finalism.logistics.platform.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Component
public class RateLimiter {

	private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
	private final ClockPort clock;

	public RateLimiter(ClockPort clock) {
		this.clock = clock;
	}

	public void check(String key, int limit, Duration window) {
		Instant now = clock.now();
		Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		long retryAfterSeconds = 0;
		synchronized (deque) {
			while (!deque.isEmpty() && !deque.peekFirst().isAfter(now.minus(window))) {
				deque.pollFirst();
			}
			if (deque.size() >= limit) {
				retryAfterSeconds = Math.max(1, Duration.between(now, deque.peekFirst().plus(window)).toSeconds() + 1);
			}
			else {
				deque.addLast(now);
			}
		}
		if (retryAfterSeconds > 0) {
			throw new BusinessException(ErrorCode.RATE_LIMITED, "Too many requests. Try again shortly.",
					Map.of("retryAfterSeconds", retryAfterSeconds));
		}
		if (hits.size() > 10_000) {
			hits.entrySet().removeIf(entry -> {
				synchronized (entry.getValue()) {
					return entry.getValue().isEmpty() || entry.getValue().peekLast().isBefore(now.minus(window));
				}
			});
		}
	}
}
