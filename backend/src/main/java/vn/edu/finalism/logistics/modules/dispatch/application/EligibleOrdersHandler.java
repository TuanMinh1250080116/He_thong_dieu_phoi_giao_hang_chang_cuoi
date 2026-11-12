package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class EligibleOrdersHandler {

	private final OrderLifecycleService orders;
	private final ClockPort clock;

	EligibleOrdersHandler(OrderLifecycleService orders, ClockPort clock) {
		this.orders = orders;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResult<OrderView> handle(ActorContext actor, UUID zoneId, Instant from, Instant to,
			String cursor, Integer limit) {
		actor.requirePermission("dispatch.batch.manage");
		int size = PageCursor.normalizeLimit(limit);
		java.util.Set<UUID> zones = scope(actor, zoneId);
		if (zones.isEmpty()) {
			return new PageResult<>(List.of(), null, clock.now());
		}
		return orders.eligible(zones, zoneId, from, to, cursor == null ? null : PageCursor.decode(cursor), size);
	}

	private static java.util.Set<UUID> scope(ActorContext actor, UUID zoneId) {
		if (zoneId != null) {
			actor.requireScope(zoneId);
			return java.util.Set.of(zoneId);
		}
		return actor.operationScopeIds();
	}
}
