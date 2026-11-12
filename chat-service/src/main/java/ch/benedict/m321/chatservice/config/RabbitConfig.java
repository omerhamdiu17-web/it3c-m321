package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    /** So viele Nachrichten warten höchstens in der eigenen Queue (Spezifikation 3.3). */
    private static final int DELIVERY_QUEUE_MAX_LENGTH = 900;

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede web-gateway-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer WebSocket-Clients
     * im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die eigene Queue DIESER Instanz am Zustellweg (Spezifikation 3).
     *
     * - Eigene Queue statt einer gemeinsamen: an einer gemeinsamen Queue würden
     *   sich die Instanzen die Nachrichten teilen, und jeder Client sähe nur
     *   einen Teil. So bekommt jede Instanz eine Kopie von jeder Nachricht.
     * - Zufälliger Name: jede Instanz braucht einen anderen.
     * - nonDurable, exclusive, autoDelete: die Queue gehört nur der Verbindung
     *   dieser Instanz. Ist die Verbindung zu, löscht RabbitMQ die Queue (S4).
     * - maxLength 900 und dropHead: hängt die Instanz, warten höchstens 900
     *   Nachrichten, bei der nächsten fliegt die älteste raus. Dazu kommen
     *   höchstens 100, die schon unterwegs zur Instanz sind (prefetch). Zusammen
     *   nie mehr als 1000 (S6).
     */
    @Bean
    public Queue deliveryQueue() {
        UUID instanceId = UUID.randomUUID();
        String queueName = QueueNames.DELIVERY_QUEUE_PREFIX + instanceId;
        return QueueBuilder.nonDurable(queueName)
                .exclusive()
                .autoDelete()
                .maxLength(DELIVERY_QUEUE_MAX_LENGTH)
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    /**
     * Hängt die eigene Queue an den Fanout. Erst ab jetzt legt RabbitMQ eine
     * Kopie jeder Nachricht hinein. Ohne Binding verwirft der Fanout sie.
     *
     * Spring findet die beiden Beans über die Namen der Parameter.
     */
    @Bean
    public Binding deliveryBinding(Queue deliveryQueue, FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange);
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
