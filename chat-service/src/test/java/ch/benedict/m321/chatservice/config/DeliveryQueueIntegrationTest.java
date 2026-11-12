package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.BrokerInspector;
import ch.benedict.m321.chatservice.ChatServiceStarter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die eigene Queue jeder Instanz am Fanout chat.delivery gegen einen
 * echten RabbitMQ (Spezifikation 3.1 bis 3.3, Szenarien S4 und S6).
 */
@Testcontainers
class DeliveryQueueIntegrationTest {

    /** Ein RabbitMQ für alle Tests dieser Klasse. */
    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /**
     * Eine Instanz hängt genau eine eigene Queue an den Fanout: nicht durable,
     * exclusive, auto-delete, höchstens 900 wartend, die älteste fliegt raus.
     */
    @Test
    void instanceBindsItsOwnLimitedQueue() throws Exception {
        ConfigurableApplicationContext instance = startAndDeclare();

        List<String> boundQueues = BrokerInspector.queuesBoundTo(rabbitMq, "chat.delivery");
        assertEquals(1, boundQueues.size());
        String queueName = boundQueues.get(0);
        assertTrue(queueName.startsWith("chat.delivery."), queueName);

        JsonNode queue = BrokerInspector.queueDetails(rabbitMq, queueName);
        JsonNode maxLength = BrokerInspector.argument(queue, "x-max-length");
        JsonNode overflow = BrokerInspector.argument(queue, "x-overflow");
        assertEquals(false, queue.get("durable").asBoolean());
        assertEquals(true, queue.get("auto_delete").asBoolean());
        assertEquals(true, queue.get("exclusive").asBoolean());
        assertEquals(900, maxLength.asInt());
        assertEquals("drop-head", overflow.asText());

        instance.close();
    }

    /** Stoppt die Instanz, ist ihre Queue weg (S4: nach höchstens 30 s). */
    @Test
    void queueDisappearsWithItsInstance() throws Exception {
        ConfigurableApplicationContext instance = startAndDeclare();
        List<String> boundQueues = BrokerInspector.queuesBoundTo(rabbitMq, "chat.delivery");
        String queueName = boundQueues.get(0);

        instance.close();

        JsonNode queue = BrokerInspector.queueDetails(rabbitMq, queueName);
        assertNull(queue);
    }

    /**
     * Startet eine Instanz und lässt sie alles bei RabbitMQ anlegen. initialize
     * tut das sofort; sonst passiert es erst bei der ersten Verbindung.
     */
    private ConfigurableApplicationContext startAndDeclare() {
        ConfigurableApplicationContext instance = ChatServiceStarter.start(rabbitMq);
        RabbitAdmin rabbitAdmin = instance.getBean(RabbitAdmin.class);
        rabbitAdmin.initialize();
        return instance;
    }
}
