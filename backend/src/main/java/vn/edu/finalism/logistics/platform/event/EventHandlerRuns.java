package vn.edu.finalism.logistics.platform.event;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.shared.ClockPort;

@Component
public class EventHandlerRuns {

	private final JdbcClient jdbc;
	private final ClockPort clock;

	EventHandlerRuns(JdbcClient jdbc, ClockPort clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean claim(UUID eventId, String handlerName) {
		int rows = jdbc.sql("""
				INSERT INTO platform.event_handler_runs (event_id, handler_name, completed_at)
				VALUES (:eventId, :handler, :now)
				ON CONFLICT (event_id, handler_name) DO NOTHING
				""")
				.param("eventId", eventId)
				.param("handler", handlerName)
				.param("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
				.update();
		return rows == 1;
	}
}
