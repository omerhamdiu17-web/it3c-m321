package ch.benedict.m321.batchwriter;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Ein PostgreSQL für die Tests, eingerichtet mit dem ECHTEN Schema.
 *
 * Die Datei postgres/init/01-schema.sql wird in den Container kopiert, an
 * dieselbe Stelle, an der docker-compose sie einbindet. So prüft jeder Test
 * das Schema mit, und es gibt keine zweite Kopie davon.
 */
public final class TestDatabase {

    /** Dasselbe Image wie in docker-compose.yml. */
    private static final String IMAGE = "postgres:16";

    /** Die Tests laufen im Ordner batch-writer, das Schema liegt eine Ebene höher. */
    private static final String SCHEMA_FILE = "../postgres/init/01-schema.sql";

    /** Hier sucht das Postgres-Image beim ersten Start nach Skripten. */
    private static final String INIT_SCRIPT = "/docker-entrypoint-initdb.d/01-schema.sql";

    /** Diese Klasse sammelt nur eine Hilfsmethode und wird nie erzeugt. */
    private TestDatabase() {
    }

    /**
     * Baut den Container. reWriteBatchedInserts steht wie im Betrieb in der
     * JDBC-URL, damit die Tests denselben Weg durch den Treiber nehmen.
     */
    public static PostgreSQLContainer<?> createContainer() {
        MountableFile schema = MountableFile.forHostPath(SCHEMA_FILE);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE);
        container.withCopyFileToContainer(schema, INIT_SCRIPT);
        container.withUrlParam("reWriteBatchedInserts", "true");
        return container;
    }
}
