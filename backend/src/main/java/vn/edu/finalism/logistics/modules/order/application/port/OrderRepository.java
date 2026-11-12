package vn.edu.finalism.logistics.modules.order.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.PageCursor;

public interface OrderRepository {

	String nextCode();

	void insert(Order order, Instant now);

	Optional<Order> findById(UUID id);

	Optional<Order> findByIdForUpdate(UUID id);

	List<Order> findAllForUpdate(Collection<UUID> ids);

	void updateState(Order order, Instant now);

	List<OrderRow> search(SearchCriteria criteria);

	record OrderRow(Order order, Instant createdAt) {
	}

	record SearchCriteria(UUID customerId, Set<UUID> zoneIds, OrderStatus status, UUID zoneId, String codePrefix,
			Instant windowFrom, Instant windowTo, PageCursor after, int limit) {
	}
}
