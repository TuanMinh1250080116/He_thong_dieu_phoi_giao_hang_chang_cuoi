package vn.edu.finalism.logistics.modules.order.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.order.application.port.OrderRepository;
import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.GeoPoint;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class OrderLifecycleService {

	private final OrderRepository orders;
	private final OrderTrackingService tracking;
	private final ClockPort clock;

	OrderLifecycleService(OrderRepository orders, OrderTrackingService tracking, ClockPort clock) {
		this.orders = orders;
		this.tracking = tracking;
		this.clock = clock;
	}

	public enum Transition {
		ATTACH_TO_BATCH(Order::attachToBatch, OrderTrackingService.Milestone.BATCHED),
		RELEASE_FROM_BATCH(Order::releaseFromBatch, null),
		MARK_ASSIGNED(Order::markAssigned, OrderTrackingService.Milestone.DRIVER_ASSIGNED),
		REVERT_TO_BATCHED(Order::revertToBatched, OrderTrackingService.Milestone.REASSIGNING),
		MARK_IN_DELIVERY(Order::markInDelivery, OrderTrackingService.Milestone.OUT_FOR_DELIVERY),
		MARK_DELIVERED(Order::markDelivered, OrderTrackingService.Milestone.DELIVERED);

		private final UnaryOperator<Order> action;
		private final OrderTrackingService.Milestone milestone;

		Transition(UnaryOperator<Order> action, OrderTrackingService.Milestone milestone) {
			this.action = action;
			this.milestone = milestone;
		}
	}

	public record OrderView(UUID id, String code, UUID customerId, OrderStatus status, UUID zoneId,
			int weightG, Long volumeCm3, Instant windowStart, Instant windowEnd,
			String pickupAddress, GeoPoint pickup, String dropoffAddress, GeoPoint dropoff, String recipientName,
			String recipientContact) {
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public List<OrderView> lockAndGet(Collection<UUID> ids) {
		List<Order> found = orders.findAllForUpdate(ids);
		if (found.size() != ids.size()) {
			throw BusinessException.notFound("Order");
		}
		return found.stream().map(OrderLifecycleService::view).toList();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void apply(Collection<UUID> ids, Transition transition) {
		Instant now = clock.now();
		List<Order> found = orders.findAllForUpdate(ids);
		if (found.size() != ids.size()) {
			throw BusinessException.notFound("Order");
		}
		for (Order order : found) {
			orders.updateState(transition.action.apply(order), now);
			if (transition.milestone != null) {
				tracking.record(order.id(), transition.milestone, now);
			}
		}
	}

	@Transactional(readOnly = true)
	public PageResult<OrderView> eligible(Set<UUID> zoneIds, UUID zoneId, Instant windowFrom, Instant windowTo,
			PageCursor after, int limit) {
		List<OrderRepository.OrderRow> rows = orders.search(new OrderRepository.SearchCriteria(null, zoneIds,
				OrderStatus.READY_FOR_DISPATCH, zoneId, null, windowFrom, windowTo, after, limit));
		boolean more = rows.size() > limit;
		List<OrderRepository.OrderRow> page = more ? rows.subList(0, limit) : rows;
		String next = more ? new PageCursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).order().id())
				.encode() : null;
		return new PageResult<>(page.stream().map(r -> view(r.order())).toList(), next, clock.now());
	}

	@Transactional(readOnly = true)
	public List<OrderView> get(Collection<UUID> ids) {
		return ids.stream()
				.map(id -> orders.findById(id).orElseThrow(() -> BusinessException.notFound("Order")))
				.map(OrderLifecycleService::view).toList();
	}

	private static OrderView view(Order o) {
		return new OrderView(o.id(), o.code(), o.customerId(), o.status(), o.zoneId(), o.weightG(), o.volumeCm3(),
				o.windowStart(), o.windowEnd(), o.pickupAddress(), o.pickup(), o.dropoffAddress(), o.dropoff(),
				o.recipientName(), o.recipientContact());
	}
}
