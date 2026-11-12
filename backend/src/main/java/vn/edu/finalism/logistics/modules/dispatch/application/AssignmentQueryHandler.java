package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.dispatch.application.port.BoardReadPort;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository.AssignmentRow;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class AssignmentQueryHandler {

	private final DispatchRepository dispatch;
	private static final Set<String> DELIVERY_STATUSES = Set.of("ASSIGNED", "ACCEPTED", "AWAITING_REASSIGNMENT",
			"IN_PROGRESS", "COMPLETED", "CANCELLED");

	private final DriverAvailabilityService drivers;
	private final BoardReadPort reader;
	private final ClockPort clock;

	AssignmentQueryHandler(DispatchRepository dispatch, DriverAvailabilityService drivers, BoardReadPort reader,
			ClockPort clock) {
		this.dispatch = dispatch;
		this.drivers = drivers;
		this.reader = reader;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> myDeliveryOverview(ActorContext actor, java.util.UUID deliveryId) {
		actor.requirePermission("delivery.execute");
		var driver = drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
		Map<String, Object> overview = reader.driverDeliveryOverview(driver.id(), deliveryId);
		if (overview == null) {
			throw BusinessException.notFound("Delivery");
		}
		return overview;
	}

	@Transactional(readOnly = true)
	public PageResult<Map<String, Object>> listMyDeliveries(ActorContext actor, List<String> statuses, Instant from,
			Instant to, String cursor, Integer limit) {
		actor.requirePermission("delivery.execute");
		var driver = drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
		if (statuses != null && !DELIVERY_STATUSES.containsAll(statuses)) {
			throw BusinessException.validation("status", "INVALID", "status is not a delivery status.");
		}
		if (from != null && to != null && !to.isAfter(from)) {
			throw BusinessException.validation("to", "INVALID_RANGE", "to must be after from.");
		}
		int size = PageCursor.normalizeLimit(limit);
		List<Map<String, Object>> rows = reader.driverDeliveries(driver.id(), statuses,
				new BoardReadPort.TimeRange(from, to), cursor == null ? null : PageCursor.decode(cursor), size + 1);
		boolean more = rows.size() > size;
		List<Map<String, Object>> page = more ? rows.subList(0, size) : rows;
		String next = null;
		if (more) {
			Map<String, Object> last = page.get(page.size() - 1);
			next = new PageCursor(((Instant) last.get("createdAt")).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
					(java.util.UUID) last.get("id")).encode();
		}
		return new PageResult<>(page, next, clock.now());
	}

	@Transactional(readOnly = true)
	public List<DispatchRepository.RejectionReason> rejectionReasons(ActorContext actor) {
		actor.requirePermission("delivery.execute");
		return dispatch.activeRejectionReasons();
	}

	@Transactional(readOnly = true)
	public List<AssignmentRow> listMine(ActorContext actor, Integer limit) {
		actor.requirePermission("delivery.execute");
		var driver = drivers.findByUserId(actor.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "This account is not a driver."));
		return dispatch.listAssignmentsForDriver(driver.id(), PageCursor.normalizeLimit(limit));
	}
}
