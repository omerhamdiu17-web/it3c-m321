package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.TestDatabase;
import ch.benedict.m321.batchwriter.config.QueueNames;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S7 im Kleinen: die Datenbank fällt aus, während Nachrichten
 * ankommen. Danach muss jede Nachricht in der Tabelle stehen, keine in der
 * Dead-Letter-Queue, und der Verbraucher muss noch laufen – ohne Neustart.
 *
 * So entsteht der Ausfall: Wir sperren die Datenbank für neue Verbindungen
 * und trennen alle bestehenden. Für den batch-writer sieht das aus wie ein
 * gestoppter Server (Spezifikation 3.3, F4). Den Container selbst anzuhalten
 * ginge nicht: er käme mit einem anderen Port zurück, und der batch-writer
 * fände ihn nicht mehr.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class DatabaseOutageIntegrationTest {

    /** So viele Nachrichten kommen während des Ausfalls an. */
    private static final int MESSAGE_COUNT = 50;

    /**
     * So lange ist die Datenbank weg: länger als die 30 s, die der
     * Verbindungspool ohne unsere Einstellung höchstens auf eine Verbindung
     * wartet. So scheitert das Schreiben sicher mindestens einmal.
     */
    private static final int OUTAGE_MILLISECONDS = 35000;

    /** So lange warten wir höchstens auf Zeilen in der Tabelle. */
    private static final int WAIT_MILLISECONDS = 30000;

    /** Ein beliebiger Raum: der batch-writer prüft Räume bewusst nicht. */
    private static final String ROOM_ID = "3f2b1c4e-0000-0000-0000-000000000001";

    /** RabbitMQ wie in docker-compose.yml. */
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
     * Nachrichten während des Ausfalls: alle kommen an, keine in der DLQ, der
     * Verbraucher lebt. Wie in der Abnahme läuft vorher schon etwas durch,
     * damit der Verbindungspool Verbindungen hält, die der Ausfall dann trennt.
     */
    @Test
    void storesEverythingOnceTheDatabaseIsBack() throws Exception {
        UUID warmUpId = UUID.randomUUID();
        String warmUpBody = json(warmUpId, "vorher");
        publish(warmUpBody);
        int warmedUp = waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", warmUpId);
        assertEquals(1, warmedUp);

        blockDatabase();
        try {
            for (int i = 1; i <= MESSAGE_COUNT; i++) {
                UUID id = UUID.randomUUID();
                String body = json(id, "S7 " + i);
                publish(body);
            }
            Thread.sleep(OUTAGE_MILLISECONDS);
        } finally {
            unblockDatabase();
        }

        int stored = waitForCount(MESSAGE_COUNT, "SELECT count(*) FROM message WHERE content LIKE 'S7 %'");
        int deadLetters = messageCount(QueueNames.DEAD_LETTER_QUEUE);
        MessageListenerContainer container = listenerRegistry.getListenerContainer(MessageBatchListener.LISTENER_ID);
        assertEquals(MESSAGE_COUNT, stored);
        assertEquals(0, deadLetters);
        assertTrue(container.isRunning(), "der Verbraucher läuft nicht mehr");
    }

    /**
     * Sperrt die Test-Datenbank für neue Verbindungen und trennt alle
     * bestehenden. Das geht nur von einer ANDEREN Datenbank aus, deshalb
     * arbeiten wir in der Verwaltungsdatenbank "postgres".
     */
    private void blockDatabase() throws SQLException {
        String databaseName = postgres.getDatabaseName();
        executeInMaintenanceDatabase("ALTER DATABASE " + databaseName + " WITH ALLOW_CONNECTIONS false");
        executeInMaintenanceDatabase(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + databaseName + "'");
    }

    /** Lässt wieder Verbindungen zu: die Datenbank ist "zurück". */
    private void unblockDatabase() throws SQLException {
        String databaseName = postgres.getDatabaseName();
        executeInMaintenanceDatabase("ALTER DATABASE " + databaseName + " WITH ALLOW_CONNECTIONS true");
    }

    /** Führt einen Befehl als Superuser in der Verwaltungsdatenbank "postgres" aus. */
    private void executeInMaintenanceDatabase(String sql) throws SQLException {
        String host = postgres.getHost();
        Integer port = postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        String url = "jdbc:postgresql://" + host + ":" + port + "/postgres";
        String user = postgres.getUsername();
        String password = postgres.getPassword();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Fragt alle 500 ms, bis die Zählung den erwarteten Wert hat. Der
     * batch-writer arbeitet in einem eigenen Thread, deshalb müssen wir warten.
     * Gibt den zuletzt gezählten Wert zurück.
     */
    private int waitForCount(int expected, String sql, Object... arguments) throws InterruptedException {
        int count = 0;
        int attempts = WAIT_MILLISECONDS / 500;
        for (int i = 0; i < attempts; i++) {
            count = countRows(sql, arguments);
            if (count == expected) {
                return count;
            }
            Thread.sleep(500);
        }
        return count;
    }

    /**
     * Führt eine count-Abfrage aus. Kurz nach dem Ausfall kann auch diese
     * Abfrage noch scheitern; dann zählen wir 0 und versuchen es beim nächsten
     * Mal wieder.
     */
    private int countRows(String sql, Object... arguments) {
        Integer count;
        try {
            count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        } catch (RuntimeException exception) {
            return 0;
        }
        return count;
    }

    /** Baut den Body einer Nachricht im Format des chat-service (Spezifikation 2.2). */
    private String json(UUID id, String content) {
        String template = """
                {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster",\
                "content":"%s","sentAt":"2026-09-29T13:38:12.974043374Z"}""";
        return template.formatted(id, ROOM_ID, content);
    }

    /** Legt einen Body in chat.persist, nur mit content_type. */
    private void publish(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /** Wie viele Nachrichten gerade in einer Queue warten. */
    private int messageCount(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }
}
