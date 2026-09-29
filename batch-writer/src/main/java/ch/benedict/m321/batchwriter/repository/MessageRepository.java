package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Der einzige Weg in die Tabelle message.
 *
 * Bewusst JdbcTemplate und kein JPA: batchUpdate schickt einen ganzen
 * Stapel auf einmal an die Datenbank (PLANUNG.md, Abschnitt 2.1). Der Treiber
 * macht daraus mehrzeilige INSERTs zu höchstens 128 Zeilen, und alle stehen
 * in EINER Transaktion (Spezifikation, Abschnitt 5).
 */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * "ON CONFLICT (id) DO NOTHING": kommt dieselbe Nachricht ein zweites Mal
     * (At-least-once, PLANUNG.md 3.6), verwirft die Datenbank sie still.
     */
    private static final String INSERT_SQL = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt einen ganzen Stapel in EINER Transaktion: entweder steht danach
     * der ganze Stapel in der Datenbank oder gar nichts.
     *
     * Das @Transactional ist Pflicht und nicht Zierde. Ohne Transaktion
     * entschiede der Treiber, wann er ein COMMIT schickt, und ein Stapel
     * könnte halb geschrieben sein. Der Treiber fasst die Zeilen zu
     * mehrzeiligen INSERTs zusammen (reWriteBatchedInserts in der JDBC-URL).
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

    /** Die Werte einer Nachricht in der Reihenfolge der Fragezeichen im INSERT. */
    private Object[] toRow(ChatMessage message) {
        return new Object[]{
                message.id(),
                message.roomId(),
                message.senderId(),
                message.senderName(),
                message.content(),
                message.sentAt()
        };
    }
}
