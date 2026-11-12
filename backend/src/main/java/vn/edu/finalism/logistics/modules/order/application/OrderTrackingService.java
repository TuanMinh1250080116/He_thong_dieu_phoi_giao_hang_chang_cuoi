package vn.edu.finalism.logistics.modules.order.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.order.application.port.OrderTrackingRepository;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class OrderTrackingService {

	public enum Milestone {
		ORDER_RECEIVED("Đơn hàng đã được tiếp nhận"),
		BATCHED("Đơn hàng đã được xếp vào chuyến giao"),
		DRIVER_ASSIGNED("Đã phân công tài xế"),
		REASSIGNING("Đang sắp xếp lại tài xế"),
		OUT_FOR_DELIVERY("Tài xế đang giao hàng"),
		DELIVERY_ISSUE("Chuyến giao gặp sự cố, chúng tôi đang xử lý"),
		DELIVERED("Đã giao thành công"),
		ORDER_CANCELLED("Đơn hàng đã bị hủy");

		private final String note;

		Milestone(String note) {
			this.note = note;
		}

		public String note() {
			return note;
		}
	}

	private final OrderTrackingRepository tracking;
	private final ClockPort clock;

	OrderTrackingService(OrderTrackingRepository tracking, ClockPort clock) {
		this.tracking = tracking;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(UUID orderId, Milestone milestone) {
		record(orderId, milestone, clock.now());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(UUID orderId, Milestone milestone, Instant at) {
		tracking.insert(orderId, milestone.name(), at, UUID.randomUUID(), true, milestone.note());
	}
}
