package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.ChatDatabase;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft den Bulk-INSERT gegen ein ECHTES Postgres mit dem echten Schema.
 *
 * RabbitMQ läuft hier nicht, deshalb bleibt der Listener aus.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers
class MessageRepositoryIntegrationTest {

    private static final UUID LOBBY_ROOM_ID = UUID.fromString(ChatDatabase.LOBBY_ROOM_ID);

    @Container
    static PostgreSQLContainer<?> postgres = ChatDatabase.createContainer();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        ChatDatabase.registerProperties(postgres, registry);
    }

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test beginnt mit einer leeren Tabelle. Die Räume bleiben stehen. */
    @BeforeEach
    void emptyMessageTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    @Test
    void storesWholeBatch() {
        ChatMessage first = messageInRoom(LOBBY_ROOM_ID, "eins");
        ChatMessage second = messageInRoom(LOBBY_ROOM_ID, "zwei");
        ChatMessage third = messageInRoom(LOBBY_ROOM_ID, "drei");
        List<ChatMessage> batch = List.of(first, second, third);

        messageRepository.insertBatch(batch);

        assertEquals(3, countMessages());
    }

    @Test
    void ignoresDuplicate() {
        ChatMessage message = messageInRoom(LOBBY_ROOM_ID, "kommt zweimal");
        List<ChatMessage> batch = List.of(message);

        // RabbitMQ liefert denselben Stapel ein zweites Mal (At-least-once).
        messageRepository.insertBatch(batch);
        messageRepository.insertBatch(batch);

        assertEquals(1, countMessages());
    }

    @Test
    void rejectsUnknownRoom() {
        UUID unknownRoomId = UUID.randomUUID();
        ChatMessage message = messageInRoom(unknownRoomId, "Raum gibt es nicht");
        List<ChatMessage> batch = List.of(message);

        assertThrows(DataIntegrityViolationException.class, () -> messageRepository.insertBatch(batch));
        assertEquals(0, countMessages());
    }

    /** Baut eine Nachricht, wie sie der chat-service in die Queue legt. */
    private ChatMessage messageInRoom(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "sub-alice", "Alice Muster", content, sentAt);
    }

    /** Zählt die Zeilen in der Tabelle message. */
    private int countMessages() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }
}
