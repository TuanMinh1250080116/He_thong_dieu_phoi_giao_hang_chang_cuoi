package vn.edu.finalism.logistics.modules.order.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository;
import vn.edu.finalism.logistics.modules.order.application.port.OrderTrackingRepository;
import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository.SearchCriteria;
import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class OrderQueryHandler {

	private final OrderRepository orders;
	private final OrderTrackingRepository tracking;
	private final ClockPort clock;

	OrderQueryHandler(OrderRepository orders, OrderTrackingRepository tracking, ClockPort clock) {
		this.orders = orders;
		this.tracking = tracking;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<OrderTrackingRepository.TrackingEvent> tracking(ActorContext actor, UUID orderId) {
		get(actor, orderId);
		return tracking.visibleTimeline(orderId);
	}

	@Transactional(readOnly = true)
	public Order get(ActorContext actor, UUID orderId) {
		Order order = orders.findById(orderId).orElseThrow(() -> BusinessException.notFound("Order"));
		boolean own = actor.hasPermission("order.view.own") && order.customerId().equals(actor.userId());
		boolean inScope = actor.hasPermission("order.view.scope") && order.zoneId() != null
				&& actor.hasScope(order.zoneId());
		if (!own && !inScope) {
			throw BusinessException.notFound("Order");
		}
		return order;
	}

	@Transactional(readOnly = true)
	public PageResult<Order> list(ActorContext actor, OrderStatus status, UUID zoneId, String codePrefix,
			Instant windowFrom, Instant windowTo, Integer limit, String cursor) {
		int pageSize = PageCursor.normalizeLimit(limit);
		PageCursor after = cursor == null ? null : PageCursor.decode(cursor);

		UUID customerId = null;
		java.util.Set<UUID> zoneIds = null;
		if (actor.hasPermission("order.view.scope")) {
			zoneIds = actor.operationScopeIds();
			if (zoneIds.isEmpty()) {
				return new PageResult<>(List.of(), null, clock.now());
			}
		}
		else if (actor.hasPermission("order.view.own")) {
			customerId = actor.userId();
		}
		else {
			actor.requirePermission("order.view.own");
		}

		List<OrderRepository.OrderRow> rows = orders.search(new SearchCriteria(customerId, zoneIds, status,
				zoneId, codePrefix, windowFrom, windowTo, after, pageSize));
		boolean hasMore = rows.size() > pageSize;
		List<OrderRepository.OrderRow> page = hasMore ? rows.subList(0, pageSize) : rows;
		String next = null;
		if (hasMore) {
			OrderRepository.OrderRow last = page.get(page.size() - 1);
			next = new PageCursor(last.createdAt(), last.order().id()).encode();
		}
		return new PageResult<>(page.stream().map(OrderRepository.OrderRow::order).toList(), next, clock.now());
	}
}
