package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BatchLockPolicy {

	private BatchLockPolicy() {
	}

	public record OrderFacts(UUID id, String status, UUID zoneId, Instant windowStart, Instant windowEnd) {
	}

	public record Violation(UUID orderId, String reason) {
	}

	public record Result(List<UUID> invalidOrderIds, List<Violation> zoneOrWindowViolations,
			Instant plannedStart, Instant plannedEnd) {

		public boolean allowed() {
			return invalidOrderIds.isEmpty() && zoneOrWindowViolations.isEmpty();
		}
	}

	public static Result check(UUID batchZoneId, List<OrderFacts> orders) {
		List<UUID> invalid = new ArrayList<>();
		List<Violation> violations = new ArrayList<>();
		Instant start = null;
		Instant end = null;
		for (OrderFacts order : orders) {
			if (!"BATCHED".equals(order.status())) {
				invalid.add(order.id());
				continue;
			}
			if (!batchZoneId.equals(order.zoneId())) {
				violations.add(new Violation(order.id(), "ZONE_MISMATCH"));
			}
			start = start == null || order.windowStart().isBefore(start) ? order.windowStart() : start;
			end = end == null || order.windowEnd().isAfter(end) ? order.windowEnd() : end;
		}
		return new Result(invalid, violations, start, end);
	}
}
