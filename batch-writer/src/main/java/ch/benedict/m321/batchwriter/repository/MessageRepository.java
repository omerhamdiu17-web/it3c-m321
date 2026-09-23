package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Der einzige Weg in die Tabelle message.
 *
 * Bewusst JdbcTemplate und kein JPA: wir wollen genau EINEN INSERT für
 * einen ganzen Stapel sehen, und batchUpdate ist genau das
 * (PLANUNG.md, Abschnitt 2.1).
 */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * "ON CONFLICT (id) DO NOTHING": kommt dieselbe Nachricht ein zweites
     * Mal (At-least-once, PLANUNG.md 3.6), wird sie still verworfen. Die id
     * vergibt der chat-service, sie ist der Primärschlüssel.
     */
    private static final String INSERT_SQL = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt einen ganzen Stapel.
     *
     * Der Treiber fasst die Zeilen zu einem mehrzeiligen INSERT zusammen
     * (reWriteBatchedInserts in der application.yml). @Transactional sorgt
     * dafür, dass es genau EIN COMMIT gibt: entweder steht danach der ganze
     * Stapel in der Datenbank oder gar nichts.
     */
    @Transactional
    public void insertBatch(List<ChatMessage> messages) {
        List<Object[]> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            Object[] row = toRow(message);
            rows.add(row);
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, rows);
    }

    /**
     * Schreibt eine einzelne Nachricht. Gebraucht wird das nur, wenn ein
     * Stapel an einer kaputten Nachricht gescheitert ist und wir
     * herausfinden müssen, an welcher.
     */
    public void insertOne(ChatMessage message) {
        Object[] row = toRow(message);
        jdbcTemplate.update(INSERT_SQL, row);
    }

    /** Die Werte in der Reihenfolge der Fragezeichen im INSERT. */
    private Object[] toRow(ChatMessage message) {
        Timestamp sentAt = Timestamp.from(message.sentAt());
        return new Object[]{
                message.id(),
                message.roomId(),
                message.senderId(),
                message.senderName(),
                message.content(),
                sentAt
        };
    }
}
