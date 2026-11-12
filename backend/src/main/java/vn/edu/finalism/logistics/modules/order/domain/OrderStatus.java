package vn.edu.finalism.logistics.modules.order.domain;

import java.util.Set;

public enum OrderStatus {
	DRAFT, READY_FOR_DISPATCH, BATCHED, ASSIGNED, IN_DELIVERY, DELIVERED, CANCELLED;

	public Set<OrderStatus> allowedNext() {
		return switch (this) {
			case DRAFT -> Set.of(READY_FOR_DISPATCH, CANCELLED);
			case READY_FOR_DISPATCH -> Set.of(BATCHED, CANCELLED);
			case BATCHED -> Set.of(ASSIGNED, READY_FOR_DISPATCH, CANCELLED);
			case ASSIGNED -> Set.of(IN_DELIVERY, BATCHED);
			case IN_DELIVERY -> Set.of(DELIVERED);
			case DELIVERED, CANCELLED -> Set.of();
		};
	}
}
