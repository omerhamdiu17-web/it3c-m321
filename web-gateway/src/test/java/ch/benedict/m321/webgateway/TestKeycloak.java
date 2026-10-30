package ch.benedict.m321.webgateway;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Ein Keycloak für die Tests, eingerichtet mit der ECHTEN Realm-Datei.
 *
 * keycloak/realm-chat.json wird an dieselbe Stelle kopiert, an der
 * docker-compose.yml sie einbindet, und Keycloak startet mit denselben
 * Einstellungen wie im Betrieb (Spezifikation 4.3). So prüft jeder Test die
 * Realm-Datei mit, und es gibt keine zweite Kopie davon.
 */
public final class TestKeycloak {

    /** Secret des Clients web-gateway in allen Tests. Im Betrieb kommt es aus .env. */
    public static final String CLIENT_SECRET = "test-secret";

    /** Dasselbe Image wie in docker-compose.yml. */
    private static final String IMAGE = "quay.io/keycloak/keycloak:26.7.3";

    /** Die Tests laufen im Ordner web-gateway, die Realm-Datei liegt eine Ebene höher. */
    private static final String REALM_FILE = "../keycloak/realm-chat.json";

    /** Hier sucht "--import-realm" nach Realm-Dateien (wie in docker-compose.yml). */
    private static final String IMPORT_TARGET = "/opt/keycloak/data/import/realm-chat.json";

    /** Port für alle Anfragen an Keycloak. */
    private static final int HTTP_PORT = 8080;

    /** Port des Health-Endpunkts; nur dort gibt es /auth/health/ready (E2). */
    private static final int MANAGEMENT_PORT = 9000;

    /** Diese Klasse sammelt nur Hilfsmethoden und wird nie erzeugt. */
    private TestKeycloak() {
    }

    /**
     * Baut den Container, noch nicht gestartet. publicUrl ist die Adresse, unter
     * der Browser das Gateway sehen: Daraus bildet Keycloak den Issuer und alle
     * Adressen in Seiten und Umleitungen (KC_HOSTNAME, E3), und die Realm-Datei
     * setzt sie für ${PUBLIC_URL} ein. Die Demo-Passwörter bleiben bei den
     * Beispielwerten der Realm-Datei, solange ein Test sie nicht setzt.
     *
     * Bereit ist Keycloak erst, wenn /auth/health/ready mit 200 antwortet, wie
     * beim Healthcheck in docker-compose.yml. Drei Minuten Geduld, weil ein
     * Runner von GitHub langsamer ist als der Rechner aus E1 (32 s).
     */
    public static GenericContainer<?> createContainer(String publicUrl) {
        DockerImageName image = DockerImageName.parse(IMAGE);
        MountableFile realm = MountableFile.forHostPath(REALM_FILE);
        HttpWaitStrategy ready = Wait.forHttp("/auth/health/ready")
                .forPort(MANAGEMENT_PORT)
                .forStatusCode(200);
        Duration patience = Duration.ofMinutes(3);
        ready.withStartupTimeout(patience);

        GenericContainer<?> container = new GenericContainer<>(image);
        container.withCommand("start-dev", "--import-realm");
        container.withCopyFileToContainer(realm, IMPORT_TARGET);
        container.withEnv("KC_HTTP_RELATIVE_PATH", "/auth");
        container.withEnv("KC_HOSTNAME", publicUrl + "/auth");
        container.withEnv("KC_HEALTH_ENABLED", "true");
        container.withEnv("PUBLIC_URL", publicUrl);
        container.withEnv("KEYCLOAK_CLIENT_SECRET", CLIENT_SECRET);
        container.withExposedPorts(HTTP_PORT, MANAGEMENT_PORT);
        container.waitingFor(ready);
        return container;
    }

    /** Adresse, unter der der Test Keycloak direkt erreicht, mit /auth am Ende. */
    public static String url(GenericContainer<?> container) {
        String host = container.getHost();
        Integer port = container.getMappedPort(HTTP_PORT);
        return "http://" + host + ":" + port + "/auth";
    }
}
