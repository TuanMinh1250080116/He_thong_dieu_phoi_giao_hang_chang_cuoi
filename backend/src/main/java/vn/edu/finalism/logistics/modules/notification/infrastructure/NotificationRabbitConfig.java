package vn.edu.finalism.logistics.modules.notification.infrastructure;

import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.edu.finalism.logistics.platform.event.RabbitTopology;

@Configuration
class NotificationRabbitConfig {

	static final String QUEUE = "notification.q";
	static final String DEAD_LETTER_QUEUE = "notification.dlq";

	private static final String[] EVENT_TYPES = {"OrderCreated", "DriverAssigned", "DeliveryAccepted",
			"DeliveryStarted", "DeliveryCompleted", "DriverAssignmentRejected", "DriverUnassigned", "DispatchCancelled",
			"DeliveryExceptionReported", "DeliveryExceptionInvestigating", "DeliveryExceptionResolved",
			"DeliveryExceptionCancelled", "OrderAffectedByException", "OrderCancelled"};

	@Bean
	Declarables notificationTopology(TopicExchange eventsExchange, DirectExchange deadLetterExchange) {
		Queue queue = QueueBuilder.durable(QUEUE)
				.withArguments(Map.of("x-dead-letter-exchange", RabbitTopology.DEAD_LETTER_EXCHANGE,
						"x-dead-letter-routing-key", QUEUE))
				.build();
		Queue deadLetters = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
		java.util.List<org.springframework.amqp.core.Declarable> all = new java.util.ArrayList<>();
		all.add(queue);
		all.add(deadLetters);
		for (String eventType : EVENT_TYPES) {
			Binding binding = BindingBuilder.bind(queue).to(eventsExchange).with(eventType);
			all.add(binding);
		}
		all.add(BindingBuilder.bind(deadLetters).to(deadLetterExchange).with(QUEUE));
		return new Declarables(all);
	}
}
