package ch.benedict.m321.webgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hängt das Gateway an den Zustellweg (PLANUNG.md, Abschnitt 3.5).
 *
 * Jede Gateway-Instanz bekommt eine EIGENE Queue am Fanout-Exchange
 * chat.delivery. So sieht jede Instanz jede Nachricht. Das muss so sein,
 * weil eine WebSocket-Verbindung an genau einer Instanz hängt.
 */
@Configuration
public class RabbitConfig {

    /** Derselbe Name wie im chat-service. Der Vertrag ist der Name, keine gemeinsame Klasse. */
    public static final String DELIVERY_EXCHANGE = "chat.delivery";

    /**
     * Der Exchange gehört eigentlich dem chat-service. Das Gateway meldet
     * ihn trotzdem an, mit denselben Eigenschaften: startet das Gateway
     * zuerst, gäbe es sonst noch nichts, woran es seine Queue binden kann.
     * Existiert der Exchange schon, passiert nichts.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die eigene Queue dieser Instanz. AnonymousQueue heisst: zufälliger
     * Name, exklusiv für diese Verbindung, und RabbitMQ löscht sie, sobald
     * das Gateway weg ist. Nachrichten für ein Gateway, das nicht läuft,
     * braucht niemand: seine Browser sind ja auch weg.
     */
    @Bean
    public Queue deliveryQueue() {
        return new AnonymousQueue();
    }

    /** Verbindet die eigene Queue mit dem Fanout: ab jetzt kommt von jeder Nachricht eine Kopie an. */
    @Bean
    public Binding deliveryBinding(Queue deliveryQueue, FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange);
    }

    /** Auf der Leitung liegt JSON, kein serialisiertes Java-Objekt. */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
