package vn.edu.finalism.logistics.modules.dispatch.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.PageCursor;

public interface BoardReadPort {

	Map<String, Panel> board(Set<UUID> zoneIds, String query, TimeRange range, Instant now, int itemLimit);

	List<Map<String, Object>> batches(Set<UUID> zoneIds, String status, String query, TimeRange range,
			PageCursor cursor, int limit);

	List<Map<String, Object>> deliveries(Set<UUID> zoneIds, List<String> statuses, String query, TimeRange range,
			PageCursor cursor, int limit);

	List<Map<String, Object>> driverDeliveries(UUID driverId, List<String> statuses, TimeRange range, PageCursor cursor,
			int limit);

	Map<String, Object> driverDeliveryOverview(UUID driverId, UUID deliveryId);

	record TimeRange(Instant from, Instant to) {

		public static final TimeRange NONE = new TimeRange(null, null);
	}

	record Panel(int count, List<Map<String, Object>> items) {
	}
}
