package vn.edu.finalism.logistics.bootstrap;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.edu.finalism.logistics.shared.ErrorCode;

final class ConstraintErrorMapper {

	private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([^\"]+)\"");

	private static final Map<String, ErrorCode> BY_CONSTRAINT = Map.ofEntries(
			Map.entry("uq_batch_orders_active_order", ErrorCode.ORDER_ALREADY_IN_ACTIVE_BATCH),
			Map.entry("uq_batch_orders_active_pair", ErrorCode.ORDER_ALREADY_IN_ACTIVE_BATCH),
			Map.entry("ex_driver_commitments_no_overlap", ErrorCode.ASSIGNMENT_CONFLICT),
			Map.entry("uq_driver_assignments_current", ErrorCode.STATE_TRANSITION_NOT_ALLOWED),
			Map.entry("uq_decisions_open_per_batch", ErrorCode.STATE_TRANSITION_NOT_ALLOWED),
			Map.entry("uq_deliveries_open_per_batch", ErrorCode.STATE_TRANSITION_NOT_ALLOWED),
			Map.entry("uq_pod_per_stop", ErrorCode.STATE_TRANSITION_NOT_ALLOWED),
			Map.entry("uq_snapshots_active_per_decision", ErrorCode.VERSION_CONFLICT),
			Map.entry("uq_processed_commands_actor_key", ErrorCode.IDEMPOTENCY_IN_PROGRESS));

	private ConstraintErrorMapper() {
	}

	static Optional<ErrorCode> map(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			String message = cause.getMessage();
			if (message == null) {
				continue;
			}
			Matcher matcher = CONSTRAINT.matcher(message);
			if (matcher.find()) {
				return Optional.ofNullable(BY_CONSTRAINT.get(matcher.group(1)));
			}
		}
		return Optional.empty();
	}
}
