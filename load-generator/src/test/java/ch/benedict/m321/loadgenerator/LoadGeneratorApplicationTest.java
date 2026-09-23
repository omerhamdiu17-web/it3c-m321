package ch.benedict.m321.loadgenerator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext hochfährt und die Einstellungen passen.
 *
 * Mit einer Dauer von 0 Sekunden startet der Lastversuch zwar, schickt
 * aber nichts. Ein chat-service wird deshalb nicht gebraucht.
 */
@SpringBootTest(properties = "load.duration-seconds=0")
class LoadGeneratorApplicationTest {

    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
