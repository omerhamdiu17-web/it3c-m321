package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet beim Start ein, was der batch-writer bei RabbitMQ braucht: die
 * beiden Queues, genau so, wie der chat-service sie anlegt.
 */
@Configuration
public class RabbitConfig {

    /**
     * Der Schreibweg, mit GENAU denselben Eigenschaften wie im chat-service.
     *
     * Wer zuerst startet, legt die Queue an. Weicht ein Argument ab, lehnt
     * RabbitMQ die zweite Anmeldung mit PRECONDITION_FAILED ab, und der
     * Verbraucher startet nicht.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /**
     * Das Abstellgleis. Auch der batch-writer legt es an: gäbe es die Queue
     * noch nicht, würde RabbitMQ abgelehnte Nachrichten still verwerfen.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }
}
