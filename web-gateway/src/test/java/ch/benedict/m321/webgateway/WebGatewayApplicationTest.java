package ch.benedict.m321.webgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Prüft, dass der Spring-Kontext des Gateways hochfährt, OHNE dass Keycloak,
 * RabbitMQ oder der chat-service erreichbar sind.
 *
 * Darauf verlässt sich der Betrieb (Spezifikation F1 und 3.5): Das Gateway
 * braucht beim Start keinen dieser Dienste. Die Adressen kommen aus
 * gateway-test.properties und zeigen auf einen Port, an dem niemand zuhört.
 * Verlangt eine spätere Aufgabe beim Start einen Dienst, wird dieser Test rot.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:gateway-test.properties")
class WebGatewayApplicationTest {

    /**
     * Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring eine
     * Exception und der Test wird rot.
     */
    @Test
    void contextLoadsWithoutKeycloakBrokerAndChatService() {
    }
}
