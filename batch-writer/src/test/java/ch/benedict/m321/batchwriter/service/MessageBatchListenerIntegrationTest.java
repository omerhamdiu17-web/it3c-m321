package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.ChatDatabase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Schreibweg einmal ganz durch: Nachricht in chat.persist, Zeile in
 * der Tabelle message. Gegen ein ECHTES RabbitMQ und ein ECHTES Postgres.
 */
@SpringBootTest
@Testcontainers
class MessageBatchListenerIntegrationTest {

    private static final UUID LOBBY_ROOM_ID = UUID.fromString(ChatDatabase.LOBBY_ROOM_ID);

    /** So lange warten wir höchstens darauf, dass der batch-writer etwas tut. */
    private static final int WAIT_MILLISECONDS = 10000;

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    static PostgreSQLContainer<?> postgres = ChatDatabase.createContainer();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        ChatDatabase.registerProperties(postgres, registry);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** Jeder Test beginnt mit einer leeren Dead-Letter-Queue. */
    @BeforeEach
    void emptyDeadLetterQueue() {
        amqpAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE, false);
    }

    @Test
    void storesMessagesFromQueue() throws Exception {
        ChatMessage first = messageInRoom(LOBBY_ROOM_ID, "Hallo Datenbank");
        ChatMessage second = messageInRoom(LOBBY_ROOM_ID, "Noch eine");

        sendToPersistQueue(first);
        sendToPersistQueue(second);

        assertTrue(waitUntilStored(first.id()), "erste Nachricht wurde nicht gespeichert");
        assertTrue(waitUntilStored(second.id()), "zweite Nachricht wurde nicht gespeichert");
    }

    @Test
    void sendsBrokenJsonToDeadLetterQueue() {
        String brokenJson = "das ist kein JSON";
        sendRawToPersistQueue(brokenJson);

        Message deadMessage = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, WAIT_MILLISECONDS);

        assertNotNull(deadMessage, "kaputte Nachricht ist nicht in der Dead-Letter-Queue");
        String deadBody = new String(deadMessage.getBody(), StandardCharsets.UTF_8);
        assertEquals(brokenJson, deadBody);
    }

    @Test
    void storesGoodMessagesAndRejectsUnknownRoom() throws Exception {
        ChatMessage goodMessage = messageInRoom(LOBBY_ROOM_ID, "gute Nachricht");
        UUID unknownRoomId = UUID.randomUUID();
        ChatMessage badMessage = messageInRoom(unknownRoomId, "Raum gibt es nicht");

        // Beide kurz nacheinander: sie landen sehr wahrscheinlich im selben Stapel.
        sendToPersistQueue(goodMessage);
        sendToPersistQueue(badMessage);

        assertTrue(waitUntilStored(goodMessage.id()), "gute Nachricht wurde nicht gespeichert");

        Message deadMessage = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, WAIT_MILLISECONDS);
        assertNotNull(deadMessage, "schlechte Nachricht ist nicht in der Dead-Letter-Queue");
        String deadBody = new String(deadMessage.getBody(), StandardCharsets.UTF_8);
        assertTrue(deadBody.contains(badMessage.id().toString()));
    }

    /** Baut eine Nachricht, wie sie der chat-service in die Queue legt. */
    private ChatMessage messageInRoom(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "sub-alice", "Alice Muster", content, sentAt);
    }

    /** Legt eine Nachricht als JSON in chat.persist, so wie es der chat-service tut. */
    private void sendToPersistQueue(ChatMessage chatMessage) throws Exception {
        String json = objectMapper.writeValueAsString(chatMessage);
        sendRawToPersistQueue(json);
    }

    /** Legt beliebigen Text in chat.persist. Damit lassen sich auch kaputte Nachrichten schicken. */
    private void sendRawToPersistQueue(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType("application/json")
                .build();
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /**
     * Fragt die Datenbank alle 100 ms, ob die Nachricht angekommen ist.
     * Der batch-writer arbeitet in einem eigenen Thread, deshalb müssen wir warten.
     */
    private boolean waitUntilStored(UUID messageId) throws InterruptedException {
        int attempts = WAIT_MILLISECONDS / 100;
        for (int i = 0; i < attempts; i++) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM message WHERE id = ?", Integer.class, messageId);
            if (count == 1) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
