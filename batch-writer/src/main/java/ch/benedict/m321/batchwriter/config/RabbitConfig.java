package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hängt den batch-writer an den Schreibweg und stellt das Stapeln ein.
 *
 * Das Sammeln zu Stapeln übernimmt Spring AMQP selbst. Wir sagen nur, wie
 * gross ein Stapel höchstens wird und wie lange auf den nächsten Teil
 * gewartet wird (PLANUNG.md, Abschnitt 3.6).
 */
@Configuration
public class RabbitConfig {

    /** Unter diesem Namen hängt sich der Listener an die Einstellungen unten. */
    public static final String BATCH_LISTENER_FACTORY = "batchListenerFactory";

    /**
     * Der Schreibweg, mit GENAU denselben Eigenschaften wie im chat-service.
     *
     * Wer zuerst startet, legt die Queue an. Meldet der zweite sie mit
     * anderen Eigenschaften an, lehnt RabbitMQ das ab (PRECONDITION_FAILED).
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die sich nicht speichern lassen. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Die Einstellungen für den Stapel-Listener.
     *
     * Zuerst übernimmt der configurer alles aus der application.yml
     * (Verbindung, "auto-startup" im Test). Danach stellen wir vier Dinge um:
     * - Batch: der Listener bekommt eine Liste statt einer einzelnen Nachricht.
     * - batchSize/receiveTimeout: Stapel ist voll ODER es kam eine Weile nichts.
     * - prefetch: RabbitMQ schickt genau einen Stapel auf Vorrat.
     * - MANUAL: bestätigt wird von Hand, und zwar erst nach dem COMMIT.
     */
    @Bean(name = BATCH_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory batchListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${batch-writer.batch-size}") int batchSize,
            @Value("${batch-writer.batch-timeout-milliseconds}") long batchTimeoutMilliseconds) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);
        factory.setReceiveTimeout(batchTimeoutMilliseconds);
        factory.setPrefetchCount(batchSize);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
