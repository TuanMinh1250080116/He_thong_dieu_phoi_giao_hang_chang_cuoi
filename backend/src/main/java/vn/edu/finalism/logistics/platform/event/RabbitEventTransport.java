package vn.edu.finalism.logistics.platform.event;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.shared.CorrelationId;

@Component
class RabbitEventTransport implements EventTransport {

	private static final long CONFIRM_TIMEOUT_MS = 5000;

	private final RabbitTemplate rabbit;

	RabbitEventTransport(RabbitTemplate rabbit) {
		this.rabbit = rabbit;
	}

	@Override
	public void send(OutboundEvent event) {
		Message message = MessageBuilder.withBody(event.body())
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setMessageId(event.eventId().toString())
				.setType(event.eventType())
				.setHeader(CorrelationId.HEADER, event.correlationId())
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.build();
		rabbit.invoke(operations -> {
			operations.send(RabbitTopology.EVENTS_EXCHANGE, event.eventType(), message);
			operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
			return null;
		});
	}
}
