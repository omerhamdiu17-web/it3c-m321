package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Prüft, dass der Spring-Kontext hochfährt, OHNE dass RabbitMQ oder
 * PostgreSQL erreichbar sind.
 *
 * Genau darauf verlässt sich der Betrieb: startet der batch-writer, bevor
 * RabbitMQ Verbindungen annimmt, darf er nicht abstürzen, sondern soll es
 * wieder versuchen. Die Datenbank braucht er erst beim ersten Stapel.
 */
@SpringBootTest
@DirtiesContext
class BatchWriterApplicationTest {

    /**
     * Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring eine
     * Exception und der Test wird rot. @DirtiesContext schliesst ihn danach
     * wieder, damit der Listener nicht weiter nach einem Broker sucht.
     */
    @Test
    void contextLoadsWithoutBrokerAndDatabase() {
    }
}
