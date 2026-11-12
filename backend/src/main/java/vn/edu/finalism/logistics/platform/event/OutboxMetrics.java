package vn.edu.finalism.logistics.platform.event;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
class OutboxMetrics {

	OutboxMetrics(MeterRegistry registry, JdbcClient jdbc) {
		Gauge.builder("event_publication_backlog", () -> backlog(jdbc)).register(registry);
	}

	private static double backlog(JdbcClient jdbc) {
		try {
			return jdbc.sql("SELECT count(*) FROM platform.event_publications WHERE status IN ('PENDING','FAILED')")
					.query(Long.class).single();
		}
		catch (RuntimeException e) {
			return Double.NaN;
		}
	}
}
