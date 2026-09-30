package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.TestDatabase;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Bulk-INSERT gegen ein ECHTES PostgreSQL mit dem echten Schema.
 *
 * RabbitMQ läuft hier nicht. Der Listener sucht im Hintergrund vergeblich
 * nach einem Broker; das stört diese Tests nicht.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class MessageRepositoryIntegrationTest {

    /** Die Server-Zeit aus dem Mitschnitt vom 29.09.2026, mit Nanosekunden. */
    private static final String CAPTURED_SENT_AT = "2026-09-29T13:38:12.974043374Z";

    /** Das Zeichen NUL (Code 0): JSON erlaubt es, PostgreSQL kann es in text nicht speichern. */
    private static final String NUL = String.valueOf((char) 0);

    /** PostgreSQL mit dem echten Schema; @ServiceConnection setzt die Datenbank-Einstellungen. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.createContainer();

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test beginnt mit einer leeren Tabelle. */
    @BeforeEach
    void emptyMessageTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Drei Nachrichten, ein Aufruf, drei Zeilen. */
    @Test
    void storesWholeBatch() {
        ChatMessage first = messageWithContent("eins");
        ChatMessage second = messageWithContent("zwei");
        ChatMessage third = messageWithContent("drei");
        List<ChatMessage> batch = List.of(first, second, third);

        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(3, rows);
    }

    /**
     * Jedes Feld kommt unverändert an, auch Umlaute und Emoji. sent_at wird
     * auf Mikrosekunden gerundet, genauer speichert PostgreSQL nicht.
     */
    @Test
    void storesAllFieldsUnchanged() {
        ChatMessage message = messageWithContent("Grüezi mitenand 👋");
        List<ChatMessage> batch = List.of(message);

        messageRepository.insertBatch(batch);

        String row = jdbcTemplate.queryForObject(
                "SELECT room_id || '|' || sender_id || '|' || sender_name || '|' || content FROM message WHERE id = ?",
                String.class, message.id());
        OffsetDateTime storedSentAt = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", OffsetDateTime.class, message.id());
        String expectedRow = message.roomId() + "|anna|Anna Muster|Grüezi mitenand 👋";
        OffsetDateTime expectedSentAt = OffsetDateTime.parse("2026-09-29T13:38:12.974043Z");
        assertEquals(expectedRow, row);
        assertNotNull(storedSentAt);
        assertTrue(expectedSentAt.isEqual(storedSentAt), "gespeichert: " + storedSentAt);
    }

    /** RabbitMQ liefert denselben Stapel ein zweites Mal (At-least-once): eine Zeile. */
    @Test
    void ignoresDuplicateInLaterBatch() {
        ChatMessage message = messageWithContent("kommt zweimal");
        List<ChatMessage> batch = List.of(message);

        messageRepository.insertBatch(batch);
        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(1, rows);
    }

    /** Dieselbe Nachricht zweimal im selben Stapel: ebenfalls eine Zeile, kein Fehler. */
    @Test
    void ignoresDuplicateWithinSameBatch() {
        ChatMessage message = messageWithContent("zweimal im Stapel");
        List<ChatMessage> batch = List.of(message, message);

        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(1, rows);
    }

    /**
     * 500 Nachrichten in einem Aufruf ergeben EINE Transaktion. Jede Zeile
     * merkt sich in der Systemspalte xmin, welche Transaktion sie geschrieben
     * hat; hier tragen alle 500 dieselbe Nummer.
     */
    @Test
    void writesWholeBatchInOneTransaction() {
        List<ChatMessage> batch = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            ChatMessage message = messageWithContent("Nachricht " + i);
            batch.add(message);
        }

        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        int transactions = countRows("SELECT count(DISTINCT CAST(xmin AS text)) FROM message");
        assertEquals(500, rows);
        assertEquals(1, transactions);
    }

    /**
     * Eine Zeile, die PostgreSQL nicht speichern kann, meldet Spring als
     * DataIntegrityViolationException: ein Datenfehler, kein Ausfall. Genau
     * daran unterscheidet der Listener später die beiden Fehlerklassen.
     */
    @Test
    void reportsNulCharacterAsDataIntegrityViolation() {
        ChatMessage message = messageWithContent("vor" + NUL + "nach");
        List<ChatMessage> batch = List.of(message);

        DataIntegrityViolationException thrown = null;
        try {
            messageRepository.insertBatch(batch);
        } catch (DataIntegrityViolationException exception) {
            thrown = exception;
        }

        assertNotNull(thrown, "NUL muss als Datenfehler gemeldet werden");
        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(0, rows);
    }

    /** Baut eine Nachricht, wie sie der chat-service in die Queue legt. */
    private ChatMessage messageWithContent(String content) {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        OffsetDateTime sentAt = OffsetDateTime.parse(CAPTURED_SENT_AT);
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", content, sentAt);
    }

    /** Führt eine count-Abfrage aus. */
    private int countRows(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count;
    }
}
