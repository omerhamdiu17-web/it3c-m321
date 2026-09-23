package ch.benedict.m321.chatservice.repository;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.Room;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Der Lesepfad in die Datenbank: Räume und Verlauf.
 *
 * Der chat-service schreibt NIE in die Datenbank, das tut nur der
 * batch-writer. Hier stehen ausschliesslich SELECTs.
 */
@Repository
@RequiredArgsConstructor
public class RoomRepository {

    /** Die drei Räume werden gemeinsam angelegt; die ID entscheidet dann über die Reihenfolge. */
    private static final String SELECT_ROOMS_SQL = """
            SELECT id, name
            FROM room
            ORDER BY created_at, id
            """;

    /**
     * Die einzige Abfrage auf message (PLANUNG.md, Abschnitt 3.7). Sie
     * passt genau auf den Index message(room_id, sent_at DESC).
     */
    private static final String SELECT_LATEST_MESSAGES_SQL = """
            SELECT id, room_id, sender_id, sender_name, content, sent_at
            FROM message
            WHERE room_id = ?
            ORDER BY sent_at DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /** Liefert alle Räume. */
    public List<Room> findAllRooms() {
        return jdbcTemplate.query(SELECT_ROOMS_SQL, this::mapRoom);
    }

    /** Liefert die neuesten Nachrichten eines Raums, die NEUESTE zuerst. */
    public List<ChatMessage> findLatestMessages(UUID roomId, int limit) {
        return jdbcTemplate.query(SELECT_LATEST_MESSAGES_SQL, this::mapMessage, roomId, limit);
    }

    /** Macht aus einer Zeile der Tabelle room einen Room. */
    private Room mapRoom(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        String name = resultSet.getString("name");
        return new Room(id, name);
    }

    /** Macht aus einer Zeile der Tabelle message eine ChatMessage — dieselbe Form wie in der Queue. */
    private ChatMessage mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        UUID roomId = resultSet.getObject("room_id", UUID.class);
        String senderId = resultSet.getString("sender_id");
        String senderName = resultSet.getString("sender_name");
        String content = resultSet.getString("content");
        Timestamp sentAtTimestamp = resultSet.getTimestamp("sent_at");
        Instant sentAt = sentAtTimestamp.toInstant();
        return new ChatMessage(id, roomId, senderId, senderName, content, sentAt);
    }
}
