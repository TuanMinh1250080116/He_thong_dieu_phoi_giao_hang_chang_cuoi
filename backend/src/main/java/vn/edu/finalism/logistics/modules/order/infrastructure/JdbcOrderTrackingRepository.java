package vn.edu.finalism.logistics.modules.order.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.order.application.port.OrderTrackingRepository;

@Repository
class JdbcOrderTrackingRepository implements OrderTrackingRepository {

	private final JdbcClient jdbc;

	JdbcOrderTrackingRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(UUID orderId, String milestone, Instant occurredAt, UUID sourceEventId, boolean customerVisible,
			String publicNote) {
		jdbc.sql("""
				INSERT INTO ordering.order_tracking_events
				  (id, order_id, milestone, occurred_at, source_event_id, customer_visible, public_note)
				VALUES (:id, :order, :milestone, :at, :source, :visible, :note)
				ON CONFLICT ON CONSTRAINT uq_tracking_source DO NOTHING
				""")
				.param("id", UUID.randomUUID()).param("order", orderId).param("milestone", milestone)
				.param("at", OffsetDateTime.ofInstant(occurredAt.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC))
				.param("source", sourceEventId).param("visible", customerVisible)
				.param("note", publicNote, java.sql.Types.VARCHAR).update();
	}

	@Override
	public List<TrackingEvent> visibleTimeline(UUID orderId) {
		return jdbc.sql("""
				SELECT milestone, occurred_at, public_note FROM ordering.order_tracking_events
				WHERE order_id = :id AND customer_visible ORDER BY occurred_at, id
				""")
				.param("id", orderId)
				.query((rs, i) -> new TrackingEvent(rs.getString("milestone"),
						rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("public_note")))
				.list();
	}
}
