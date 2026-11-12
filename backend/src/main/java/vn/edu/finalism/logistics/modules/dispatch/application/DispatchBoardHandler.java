package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.dispatch.application.port.BoardReadPort;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BoardReadPort.Panel;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class DispatchBoardHandler {

	private static final int PANEL_ITEMS = 10;
	private static final Set<String> BATCH_STATUSES = Set.of("DRAFT", "LOCKED", "COMPLETED", "CANCELLED");
	private static final Set<String> DELIVERY_STATUSES = Set.of("ASSIGNED", "ACCEPTED", "AWAITING_REASSIGNMENT",
			"IN_PROGRESS", "COMPLETED", "CANCELLED");
	private static final List<String> ACTIVE = List.of("ASSIGNED", "ACCEPTED", "AWAITING_REASSIGNMENT", "IN_PROGRESS");

	private final BoardReadPort reader;
	private final ClockPort clock;

	DispatchBoardHandler(BoardReadPort reader, ClockPort clock) {
		this.reader = reader;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Map<String, Panel> board(ActorContext actor, UUID zoneId, String query, Instant from, Instant to) {
		actor.requirePermission("dispatch.board.view");
		return reader.board(zones(actor, zoneId), blankToNull(query), range(from, to), clock.now(), PANEL_ITEMS);
	}

	@Transactional(readOnly = true)
	public PageResult<Map<String, Object>> batches(ActorContext actor, UUID zoneId, String status, String query,
			Instant from, Instant to, String cursor, Integer limit) {
		actor.requirePermission("dispatch.board.view");
		if (status != null && !BATCH_STATUSES.contains(status)) {
			throw BusinessException.validation("status", "INVALID", "status must be DRAFT, LOCKED, COMPLETED or CANCELLED.");
		}
		int size = PageCursor.normalizeLimit(limit);
		List<Map<String, Object>> rows = reader.batches(zones(actor, zoneId), status, blankToNull(query), range(from, to), decode(cursor),
				size + 1);
		return page(rows, size);
	}

	@Transactional(readOnly = true)
	public PageResult<Map<String, Object>> deliveries(ActorContext actor, UUID zoneId, String status, String query,
			Instant from, Instant to, String cursor, Integer limit) {
		actor.requirePermission("delivery.monitor");
		List<String> statuses;
		if (status == null || status.isBlank() || "ACTIVE".equals(status)) {
			statuses = ACTIVE;
		}
		else if (DELIVERY_STATUSES.contains(status)) {
			statuses = List.of(status);
		}
		else {
			throw BusinessException.validation("status", "INVALID", "status is not a delivery status.");
		}
		int size = PageCursor.normalizeLimit(limit);
		List<Map<String, Object>> rows = reader.deliveries(zones(actor, zoneId), statuses, blankToNull(query),
				range(from, to), decode(cursor), size + 1);
		return page(rows, size);
	}

	private static BoardReadPort.TimeRange range(Instant from, Instant to) {
		if (from != null && to != null && !to.isAfter(from)) {
			throw BusinessException.validation("to", "INVALID_RANGE", "to must be after from.");
		}
		return new BoardReadPort.TimeRange(from, to);
	}

	private PageResult<Map<String, Object>> page(List<Map<String, Object>> rows, int size) {
		boolean more = rows.size() > size;
		List<Map<String, Object>> items = more ? rows.subList(0, size) : rows;
		String next = null;
		if (more) {
			Map<String, Object> last = items.get(items.size() - 1);
			next = new PageCursor((Instant) last.get("createdAt"), (UUID) last.get("id")).encode();
		}
		return new PageResult<>(List.copyOf(items), next, clock.now());
	}

	private static Set<UUID> zones(ActorContext actor, UUID zoneId) {
		if (zoneId == null) {
			return actor.operationScopeIds();
		}
		actor.requireScope(zoneId);
		return Set.of(zoneId);
	}

	private static PageCursor decode(String cursor) {
		return cursor == null || cursor.isBlank() ? null : PageCursor.decode(cursor);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
