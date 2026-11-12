package vn.edu.finalism.logistics.modules.notification.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import vn.edu.finalism.logistics.modules.notification.application.port.EmailSenderPort;
import vn.edu.finalism.logistics.modules.notification.application.port.EmailSenderPort.EmailDeliveryException;
import vn.edu.finalism.logistics.modules.notification.application.port.NotificationRepository;
import vn.edu.finalism.logistics.modules.notification.application.port.NotificationRepository.DueEmail;
import vn.edu.finalism.logistics.modules.notification.domain.EmailRetryPolicy;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class EmailDispatcher {

	private final NotificationRepository notifications;
	private final EmailSenderPort sender;
	private final MeterRegistry meters;
	private final ClockPort clock;
	private final int batchSize;

	EmailDispatcher(NotificationRepository notifications, EmailSenderPort sender, MeterRegistry meters,
			ClockPort clock, @Value("${app.notifications.email.batch-size:20}") int batchSize) {
		this.notifications = notifications;
		this.sender = sender;
		this.meters = meters;
		this.clock = clock;
		this.batchSize = batchSize;
	}

	@Transactional
	public int dispatchDue() {
		Instant now = clock.now();
		List<DueEmail> due = notifications.claimDueEmails(now, batchSize);
		for (DueEmail email : due) {
			try {
				sender.send(email.to(), email.title(), email.body());
				notifications.markEmailSent(email.id(), clock.now());
				meters.counter("notification.email", "result", "sent").increment();
			}
			catch (EmailDeliveryException e) {
				int attempts = email.attempts() + 1;
				Optional<Duration> delay = EmailRetryPolicy.delayAfterFailure(attempts);
				notifications.markEmailFailed(email.id(), attempts, delay.map(clock.now()::plus).orElse(null),
						e.safeCode());
				meters.counter("notification.email", "result", delay.isPresent() ? "retry" : "gave_up").increment();
			}
		}
		return due.size();
	}
}
