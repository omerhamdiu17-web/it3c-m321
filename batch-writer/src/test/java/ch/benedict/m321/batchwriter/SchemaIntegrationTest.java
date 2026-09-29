package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Schema aus postgres/init/01-schema.sql gegen ein ECHTES
 * PostgreSQL. Die Abnahme misst an der Tabelle message mit den Spalten aus
 * PLANUNG.md 3.7, deshalb halten wir genau das hier fest.
 *
 * Ohne Spring: für das Schema braucht es nur eine JDBC-Verbindung.
 */
@Testcontainers
class SchemaIntegrationTest {

    /** PostgreSQL mit dem echten Schema, einmal für alle Tests dieser Klasse. */
    @Container
    static PostgreSQLContainer<?> postgres = TestDatabase.createContainer();

    /** Die sechs Spalten in ihrer Reihenfolge, mit Typ und "darf nicht leer sein". */
    @Test
    void createsMessageTableWithColumnsFromPlanning() throws SQLException {
        List<String> columns = queryFirstColumn("""
                SELECT column_name || ' ' || data_type || ' ' || is_nullable
                FROM information_schema.columns
                WHERE table_name = 'message'
                ORDER BY ordinal_position
                """);

        List<String> expected = List.of(
                "id uuid NO",
                "room_id uuid NO",
                "sender_id character varying NO",
                "sender_name character varying NO",
                "content text NO",
                "sent_at timestamp with time zone NO");
        assertEquals(expected, columns);
    }

    /** id ist der Primärschlüssel: nur so kann ON CONFLICT (id) Duplikate erkennen. */
    @Test
    void usesIdAsPrimaryKey() throws SQLException {
        List<String> keyColumns = queryFirstColumn("""
                SELECT key_column.column_name
                FROM information_schema.table_constraints table_constraint
                JOIN information_schema.key_column_usage key_column
                  ON key_column.constraint_name = table_constraint.constraint_name
                WHERE table_constraint.table_name = 'message'
                  AND table_constraint.constraint_type = 'PRIMARY KEY'
                """);

        List<String> expected = List.of("id");
        assertEquals(expected, keyColumns);
    }

    /** Kein Fremdschlüssel: Räume sind nicht Teil dieser Aufgabe (Spezifikation 4.1). */
    @Test
    void hasNoForeignKey() throws SQLException {
        List<String> foreignKeys = queryFirstColumn("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_name = 'message'
                  AND constraint_type = 'FOREIGN KEY'
                """);

        assertTrue(foreignKeys.isEmpty(), "unerwarteter Fremdschlüssel: " + foreignKeys);
    }

    /** Der Index für die Abfrage "die letzten 50 Nachrichten eines Raums". */
    @Test
    void hasIndexOnRoomAndTime() throws SQLException {
        List<String> definitions = queryFirstColumn("""
                SELECT indexdef
                FROM pg_indexes
                WHERE tablename = 'message'
                  AND indexname = 'message_room_id_sent_at_idx'
                """);

        assertEquals(1, definitions.size());
        String definition = definitions.get(0);
        assertTrue(definition.contains("(room_id, sent_at DESC)"), definition);
    }

    /** Führt eine Abfrage aus und liefert die erste Spalte jeder Zeile als Text. */
    private List<String> queryFirstColumn(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String password = postgres.getPassword();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                values.add(value);
            }
        }
        return values;
    }
}
