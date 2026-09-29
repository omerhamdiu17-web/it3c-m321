package ch.benedict.m321.batchwriter.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft gegen ein ECHTES RabbitMQ, dass der batch-writer die Queues genau so
 * anlegt wie der chat-service. Ein Mock bewiese hier nichts: ob zwei
 * Deklarationen zusammenpassen, entscheidet allein der Broker.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class RabbitConfigIntegrationTest {

    /** RabbitMQ wie in docker-compose.yml; @ServiceConnection setzt Host und Port. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /** Beide Queues entstehen beim Start, auch wenn der chat-service noch nie gesendet hat. */
    @Test
    void declaresBothQueues() {
        Properties persistQueue = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);
        Properties deadLetterQueue = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(persistQueue);
        assertNotNull(deadLetterQueue);
    }

    /**
     * Meldet chat.persist so an, wie es der chat-service tut (seine
     * RabbitConfig im Stand f8ea557e). Passten die Argumente nicht zusammen,
     * würfe RabbitMQ hier 406 PRECONDITION_FAILED.
     */
    @Test
    void acceptsDeclarationOfChatService() {
        rabbitAdmin.initialize();
        Queue queueOfChatService = QueueBuilder.durable("chat.persist")
                .deadLetterExchange("")
                .deadLetterRoutingKey("chat.dlq")
                .build();

        String declaredName = rabbitAdmin.declareQueue(queueOfChatService);

        assertEquals("chat.persist", declaredName);
    }

    /** Gegenprobe: eine Anmeldung ohne die Dead-Letter-Argumente lehnt RabbitMQ wirklich ab. */
    @Test
    void refusesDeclarationWithOtherArguments() {
        rabbitAdmin.initialize();
        Queue queueWithoutDeadLetter = QueueBuilder.durable("chat.persist").build();

        AmqpException thrown = null;
        try {
            rabbitAdmin.declareQueue(queueWithoutDeadLetter);
        } catch (AmqpException exception) {
            thrown = exception;
        }

        assertNotNull(thrown, "RabbitMQ hätte die abweichende Anmeldung ablehnen müssen");
    }
}
