package vn.edu.finalism.logistics.modules.notification.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import vn.edu.finalism.logistics.modules.notification.application.EmailDispatcher;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.notifications.email.enabled", havingValue = "true", matchIfMissing = true)
class EmailDispatchScheduler {

	private static final Logger log = LoggerFactory.getLogger(EmailDispatchScheduler.class);

	private final EmailDispatcher dispatcher;

	EmailDispatchScheduler(EmailDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${app.notifications.email.poll-interval-ms:5000}")
	void poll() {
		try {
			dispatcher.dispatchDue();
		}
		catch (RuntimeException e) {
			log.warn("Email dispatch run failed: {}", e.getClass().getSimpleName());
		}
	}
}
