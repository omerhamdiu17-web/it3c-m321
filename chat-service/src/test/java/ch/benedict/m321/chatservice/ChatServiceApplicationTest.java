package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 * Ein RabbitMQ wird dafür nicht gebraucht: Der Leser der eigenen Queue
 * versucht die Verbindung im Hintergrund immer wieder, der Start wartet
 * nicht darauf.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    /** Fährt der Kontext nicht hoch, sind alle anderen Tests wertlos. Darum gibt es diesen leeren Test. */
    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
