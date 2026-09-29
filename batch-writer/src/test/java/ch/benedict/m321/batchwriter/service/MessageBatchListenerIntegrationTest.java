package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.TestDatabase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Schreibweg einmal ganz durch: Nachricht in chat.persist, Zeile in der
 * Tabelle message. Gegen ein ECHTES RabbitMQ und ein ECHTES PostgreSQL.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class MessageBatchListenerIntegrationTest {

    /** So lange warten wir höchstens darauf, dass der batch-writer etwas tut. */
    private static final int WAIT_MILLISECONDS = 30000;

    /** Ein beliebiger Raum: der batch-writer prüft Räume bewusst nicht. */
    private static final String ROOM_ID = "3f2b1c4e-0000-0000-0000-000000000001";

    /** Der Header, den der chat-service mitschickt und den wir ignorieren. */
    private static final String TYPE_ID_OF_CHAT_SERVICE = "ch.benedict.m321.chatservice.dto.ChatMessage";

    /** RabbitMQ wie in docker-compose.yml; @ServiceConnection setzt Host und Port. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** PostgreSQL mit dem echten Schema. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.createContainer();

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * Jeder Test beginnt mit laufendem Verbraucher, leeren Queues und leerer
     * Tabelle. Queues und Tabelle überleben die einzelne Testmethode.
     */
    @BeforeEach
    void startEmpty() {
        MessageListenerContainer container = listenerContainer();
        container.start();
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE, false);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE, false);
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Eine Nachricht, genau so geschickt wie vom chat-service (mit __TypeId__), steht danach in der Tabelle. */
    @Test
    void storesMessageSentLikeChatService() throws InterruptedException {
        UUID id = UUID.randomUUID();
        String body = json(id, "Hallo Datenbank");

        publishLikeChatService(body);

        int stored = waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", id);
        assertEquals(1, stored);
    }

    /** Eine unlesbare Nachricht landet in chat.dlq, die gute daneben wird trotzdem gespeichert (F8). */
    @Test
    void rejectsUnreadableMessageAndStoresTheRest() throws InterruptedException {
        UUID goodId = UUID.randomUUID();
        String goodBody = json(goodId, "gute Nachricht");

        publish("das ist kein JSON");
        publish(goodBody);

        int stored = waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", goodId);
        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, WAIT_MILLISECONDS);
        assertEquals(1, stored);
        assertNotNull(deadLetter, "die unlesbare Nachricht ist nicht in chat.dlq");
        byte[] deadBytes = deadLetter.getBody();
        String deadBody = new String(deadBytes, StandardCharsets.UTF_8);
        assertEquals("das ist kein JSON", deadBody);
    }

    /**
     * Szenario S5: dieselbe Nachricht zweimal direkt in chat.persist, nur mit
     * content_type. Danach eine Markierung: steht sie in der Tabelle, sind die
     * beiden davor sicher verarbeitet, denn ein Verbraucher arbeitet der Reihe nach.
     */
    @Test
    void storesDuplicateOnlyOnce() throws InterruptedException {
        UUID duplicateId = UUID.randomUUID();
        String duplicateBody = json(duplicateId, "S5 Duplikat");
        UUID markerId = UUID.randomUUID();
        String markerBody = json(markerId, "Markierung");

        publish(duplicateBody);
        publish(duplicateBody);
        publish(markerBody);

        waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", markerId);
        int duplicates = countRows("SELECT count(*) FROM message WHERE id = ?", duplicateId);
        int deadLetters = messageCount(QueueNames.DEAD_LETTER_QUEUE);
        assertEquals(1, duplicates);
        assertEquals(0, deadLetters);
    }

    /**
     * Szenario S4 im Kleinen: der Verbraucher ist aus, 1000 Nachrichten warten.
     * Nach dem Start stehen alle in der Tabelle, geschrieben in wenigen
     * Transaktionen. Jede Zeile merkt sich in der Systemspalte xmin die
     * Transaktion, die sie geschrieben hat: erwartet sind 2 (2 × 500), erlaubt
     * bis 20, damit ein langsamer Rechner den Test nicht rot macht.
     */
    @Test
    void writesThousandWaitingMessagesInFewTransactions() throws InterruptedException {
        MessageListenerContainer container = listenerContainer();
        container.stop();
        for (int i = 1; i <= 1000; i++) {
            UUID id = UUID.randomUUID();
            String body = json(id, "S4 " + i);
            publish(body);
        }

        container.start();

        int stored = waitForCount(1000, "SELECT count(*) FROM message");
        int transactions = countRows("SELECT count(DISTINCT xmin::text) FROM message");
        assertEquals(1000, stored);
        assertTrue(transactions <= 20, "zu viele Transaktionen: " + transactions);
    }

    /**
     * Eine Nachricht, die die Datenbank ablehnt (Zeichen NUL im Text), reisst
     * die anderen im selben Stapel nicht mit: sie werden gespeichert, nur die
     * abgelehnte landet in chat.dlq (F9). Der Verbraucher ist beim Senden aus,
     * damit alle drei sicher im selben Stapel ankommen.
     */
    @Test
    void rejectsOnlyTheMessageTheDatabaseRefuses() throws InterruptedException {
        UUID firstId = UUID.randomUUID();
        UUID refusedId = UUID.randomUUID();
        UUID lastId = UUID.randomUUID();
        String nul = jsonEscapedNul();
        String refusedContent = "vor " + nul + " nach";
        String firstBody = json(firstId, "davor");
        String refusedBody = json(refusedId, refusedContent);
        String lastBody = json(lastId, "danach");
        MessageListenerContainer container = listenerContainer();
        container.stop();

        publish(firstBody);
        publish(refusedBody);
        publish(lastBody);
        container.start();

        int stored = waitForCount(2, "SELECT count(*) FROM message WHERE id IN (?, ?)", firstId, lastId);
        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, WAIT_MILLISECONDS);
        int refusedRows = countRows("SELECT count(*) FROM message WHERE id = ?", refusedId);
        assertEquals(2, stored);
        assertEquals(0, refusedRows);
        assertNotNull(deadLetter, "die abgelehnte Nachricht ist nicht in chat.dlq");
        byte[] deadBytes = deadLetter.getBody();
        String deadBody = new String(deadBytes, StandardCharsets.UTF_8);
        String refusedIdText = refusedId.toString();
        assertTrue(deadBody.contains(refusedIdText), deadBody);
    }

    /** Baut den Body einer Nachricht im Format des chat-service (Spezifikation 2.2). */
    private String json(UUID id, String content) {
        String template = """
                {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster",\
                "content":"%s","sentAt":"2026-09-29T13:38:12.974043374Z"}""";
        return template.formatted(id, ROOM_ID, content);
    }

    /**
     * Die Escape-Folge für das Zeichen NUL, wie sie im JSON-Text steht:
     * Backslash, u und viermal 0. Jackson macht daraus beim Lesen das Zeichen.
     */
    private String jsonEscapedNul() {
        return "\\" + "u0000";
    }

    /** Legt einen Body in chat.persist, NUR mit content_type – wie im Szenario S5. */
    private void publish(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /** Legt einen Body so in chat.persist, wie es der chat-service tut: mit Encoding und __TypeId__. */
    private void publishLikeChatService(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setHeader("__TypeId__", TYPE_ID_OF_CHAT_SERVICE)
                .build();
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /**
     * Fragt die Datenbank alle 100 ms, bis die Zählung den erwarteten Wert hat.
     * Der batch-writer arbeitet in einem eigenen Thread, deshalb müssen wir
     * warten. Gibt den zuletzt gezählten Wert zurück.
     */
    private int waitForCount(int expected, String sql, Object... arguments) throws InterruptedException {
        int count = 0;
        int attempts = WAIT_MILLISECONDS / 100;
        for (int i = 0; i < attempts; i++) {
            count = countRows(sql, arguments);
            if (count == expected) {
                return count;
            }
            Thread.sleep(100);
        }
        return count;
    }

    /** Führt eine count-Abfrage mit Parametern aus. */
    private int countRows(String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count;
    }

    /** Wie viele Nachrichten gerade in einer Queue warten. */
    private int messageCount(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }

    /** Der Verbraucher des batch-writer, so wie Spring ihn unter seiner id führt. */
    private MessageListenerContainer listenerContainer() {
        return listenerRegistry.getListenerContainer(MessageBatchListener.LISTENER_ID);
    }
}
