package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 *
 * Weder RabbitMQ noch Postgres laufen hier. Der Listener bleibt aus, und
 * die Verbindung zur Datenbank wird erst beim ersten INSERT aufgebaut.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class BatchWriterApplicationTest {

    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
