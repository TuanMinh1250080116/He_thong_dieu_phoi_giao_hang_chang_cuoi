package vn.edu.finalism.logistics.platform.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.events.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

	private final OutboxRelay relay;

	OutboxRelayScheduler(OutboxRelay relay) {
		this.relay = relay;
	}

	@Scheduled(fixedDelayString = "${app.events.relay.poll-interval-ms:1000}")
	void poll() {
		try {
			relay.dispatchBatch();
		}
		catch (RuntimeException e) {
			log.warn("Outbox relay run failed: {}", e.getClass().getSimpleName());
		}
	}
}
