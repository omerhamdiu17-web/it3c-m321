package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet beim Start ein, was der batch-writer bei RabbitMQ braucht: die
 * beiden Queues und die Einstellungen für das Lesen in Stapeln.
 */
@Configuration
public class RabbitConfig {

    /** Unter diesem Namen hängt sich der Listener an die Einstellungen unten. */
    public static final String BATCH_LISTENER_FACTORY = "batchListenerFactory";

    /** So viele Nachrichten höchstens in einem Stapel (PLANUNG.md 3.6). */
    private static final int BATCH_SIZE = 500;

    /** So lange sammelt der Listener höchstens an einem Stapel (PLANUNG.md 3.6). */
    private static final long BATCH_TIMEOUT_MILLISECONDS = 200;

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
     * Das Abstellgleis. Auch der batch-writer legt es an, denn er legt selbst
     * Nachrichten hinein: gäbe es die Queue noch nicht, würde RabbitMQ sie
     * still verwerfen.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Die Einstellungen des Stapel-Listeners. Sie stehen alle hier im Code,
     * weil eine selbst gebaute Factory die Listener-Einstellungen aus der
     * application.yml nicht liest.
     *
     * - consumerBatchEnabled: der Listener bekommt eine Liste statt einer Nachricht.
     * - batchSize und batchReceiveTimeout: ein Stapel ist fertig bei 500 Stück
     *   oder 200 ms nach Beginn des Sammelns.
     * - receiveTimeout: kommt 200 ms lang nichts, geht der Stapel sofort los.
     * - prefetchCount: RabbitMQ schickt genau einen Stapel auf Vorrat.
     * - concurrentConsumers: ein Verbraucher pro Instanz, skaliert wird mit --scale.
     * - AUTO: Spring bestätigt für uns. Kehrt der Listener ohne Fehler zurück,
     *   also nach dem COMMIT, schickt Spring EIN ACK für den ganzen Stapel.
     *   Wirft er einen Fehler, schickt Spring ein NACK für den ganzen Stapel.
     * - defaultRequeueRejected: dieses NACK heisst "zurück in die Queue" und
     *   nicht "in die DLQ". true ist auch die Vorgabe von Spring; es steht
     *   trotzdem hier, weil der Datenbank-Ausfall (S7) genau davon abhängt.
     */
    @Bean(name = BATCH_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory batchListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchListener(true);
        factory.setBatchSize(BATCH_SIZE);
        factory.setBatchReceiveTimeout(BATCH_TIMEOUT_MILLISECONDS);
        factory.setReceiveTimeout(BATCH_TIMEOUT_MILLISECONDS);
        factory.setPrefetchCount(BATCH_SIZE);
        factory.setConcurrentConsumers(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(true);
        return factory;
    }
}
