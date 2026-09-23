package ch.benedict.m321.chatservice;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Ein Postgres für die Tests, eingerichtet mit den ECHTEN Init-Skripten.
 *
 * Der Ordner postgres/init wird in den Container kopiert, genau wie ihn
 * docker-compose einbindet. So prüft jeder Test auch das Schema und die
 * festen Räume, und es gibt keine zweite Kopie des Schemas nur für Tests.
 */
public final class ChatDatabase {

    /** Das Passwort des Benutzers "chat", nur für den Test. */
    private static final String CHAT_PASSWORD = "chat-test";

    /** Die Lobby aus postgres/init/03-rooms.sql. */
    public static final String LOBBY_ROOM_ID = "00000000-0000-0000-0000-000000000001";

    /** Diese Klasse sammelt nur Hilfsmethoden und wird nie erzeugt. */
    private ChatDatabase() {
    }

    /**
     * Baut den Container. Die Init-Skripte lesen die Passwörter aus der
     * Umgebung, deshalb setzen wir sie hier wie docker-compose aus der .env.
     * Die Tests laufen im Ordner chat-service, daher "../postgres/init".
     */
    public static PostgreSQLContainer<?> createContainer() {
        MountableFile initScripts = MountableFile.forHostPath("../postgres/init");
        return new PostgreSQLContainer<>("postgres:16.15")
                .withUsername("postgres")
                .withPassword("postgres-test")
                .withEnv("CHAT_DB_PASSWORD", CHAT_PASSWORD)
                .withEnv("KEYCLOAK_DB_PASSWORD", "keycloak-test")
                .withCopyFileToContainer(initScripts, "/docker-entrypoint-initdb.d/");
    }

    /**
     * Zeigt die Anwendung auf die Datenbank "chat" im Container, mit dem
     * Benutzer "chat" — also mit denselben Rechten wie im Betrieb.
     */
    public static void registerProperties(PostgreSQLContainer<?> container, DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String host = container.getHost();
            Integer port = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
            return "jdbc:postgresql://" + host + ":" + port + "/chat";
        });
        registry.add("spring.datasource.username", () -> "chat");
        registry.add("spring.datasource.password", () -> CHAT_PASSWORD);
    }
}
