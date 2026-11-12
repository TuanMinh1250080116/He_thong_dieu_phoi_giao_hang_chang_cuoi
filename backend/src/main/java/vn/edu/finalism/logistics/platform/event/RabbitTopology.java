package vn.edu.finalism.logistics.platform.event;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {

	public static final String EVENTS_EXCHANGE = "logistics.events";
	public static final String DEAD_LETTER_EXCHANGE = "logistics.events.dlx";

	@Bean
	TopicExchange eventsExchange() {
		return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
	}

	@Bean
	DirectExchange deadLetterExchange() {
		return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
	}
}
