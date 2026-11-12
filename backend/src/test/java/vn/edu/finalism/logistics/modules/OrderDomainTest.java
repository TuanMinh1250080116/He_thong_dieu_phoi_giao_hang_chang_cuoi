package vn.edu.finalism.logistics.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.GeoPoint;

class OrderDomainTest {

	private static final Instant START = Instant.parse("2026-10-27T10:00:00Z");
	private final UUID zone = UUID.randomUUID();

	private Order draft() {
		return Order.create(UUID.randomUUID(), "ORD-1", UUID.randomUUID(), "A", new GeoPoint(10.1, 106.1), "B",
				new GeoPoint(10.2, 106.2), "Nguyen", "a@b.co", 1000, 10, 10, 10, null, START, START.plusSeconds(3600));
	}

	private Order ready() {
		return draft().withZone(zone, UUID.randomUUID()).markReady();
	}

	private Order revised(Order order) {
		return order.revise("A2", new GeoPoint(10.1, 106.1), "B2", new GeoPoint(10.2, 106.2), "Nguyen", "a@b.co", 2000,
				10, 10, 10, null, START, START.plusSeconds(7200));
	}

	@Test
	void aDraftAndAReadyOrderCanBeEditedAndStayWhereTheyAre() {
		assertThat(revised(draft()).status()).isEqualTo(OrderStatus.DRAFT);
		assertThat(revised(draft()).weightG()).isEqualTo(2000);
		assertThat(revised(ready()).status()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
	}

	@Test
	void revisingForgetsTheOldZoneSoItMustBeResolvedAgain() {
		assertThat(revised(ready()).zoneId()).isNull();
	}

	@Test
	void anOrderInABatchOrLaterCannotBeEdited() {
		Order batched = ready().attachToBatch();
		assertThatThrownBy(() -> revised(batched)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> revised(batched.markAssigned())).isInstanceOf(BusinessException.class);
	}

	@Test
	void aDraftCancelsWithoutAReasonButLaterStagesNeedOne() {
		Order cancelled = draft().cancel(null);
		assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(cancelled.cancelledFrom()).isEqualTo(OrderStatus.DRAFT);
		assertThat(cancelled.cancelReason()).isNull();

		assertThatThrownBy(() -> ready().cancel(" ")).isInstanceOf(BusinessException.class);
		Order readyCancelled = ready().cancel("  Customer changed their mind ");
		assertThat(readyCancelled.cancelledFrom()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
		assertThat(readyCancelled.cancelReason()).isEqualTo("Customer changed their mind");
	}

	@Test
	void anAssignedOrDeliveredOrCancelledOrderCannotBeCancelled() {
		assertThatThrownBy(() -> ready().attachToBatch().markAssigned().cancel("x")).isInstanceOf(BusinessException.class);
		Order delivered = ready().attachToBatch().markAssigned().markInDelivery().markDelivered();
		assertThatThrownBy(() -> delivered.cancel("x")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> draft().cancel(null).cancel("x")).isInstanceOf(BusinessException.class);
	}

	@Test
	void aDeliveredOrderNeverGoesBack() {
		Order delivered = ready().attachToBatch().markAssigned().markInDelivery().markDelivered();
		assertThatThrownBy(delivered::attachToBatch).isInstanceOf(BusinessException.class);
		assertThatThrownBy(delivered::markInDelivery).isInstanceOf(BusinessException.class);
		assertThatThrownBy(delivered::revertToBatched).isInstanceOf(BusinessException.class);
		assertThatThrownBy(delivered::releaseFromBatch).isInstanceOf(BusinessException.class);
	}

	@Test
	void onlyABatchedOrderCanBeReleasedBackToReady() {
		assertThat(ready().attachToBatch().releaseFromBatch().status()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);
		assertThatThrownBy(() -> ready().releaseFromBatch()).isInstanceOf(BusinessException.class);
	}

	@Test
	void submittingNeedsBothPointsDimensionsAndAZone() {
		assertThatThrownBy(() -> draft().markReady()).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> draft().withZone(zone, UUID.randomUUID()).withPoints(null, new GeoPoint(1, 1))
				.markReady()).isInstanceOf(BusinessException.class);
		Order noSize = Order.create(UUID.randomUUID(), "ORD-2", UUID.randomUUID(), "A", new GeoPoint(1, 1), "B",
				new GeoPoint(2, 2), "N", null, 500, null, null, null, null, START, START.plusSeconds(60))
				.withZone(zone, UUID.randomUUID());
		assertThatThrownBy(noSize::markReady).isInstanceOf(BusinessException.class);
	}
}
