package vn.edu.finalism.logistics.modules.order.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderTrackingRepository {

	void insert(UUID orderId, String milestone, Instant occurredAt, UUID sourceEventId, boolean customerVisible,
			String publicNote);

	List<TrackingEvent> visibleTimeline(UUID orderId);

	record TrackingEvent(String milestone, Instant occurredAt, String note) {
	}
}
