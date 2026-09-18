package ch.benedict.m321.webgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext ohne laufendes Keycloak hochfährt.
 *
 * Das ist nicht selbstverständlich: Mit einer issuer-uri in der
 * application.yml würde Spring beim Start Keycloak abrufen und hier
 * scheitern. Genau deshalb ist die Registrierung in Java gebaut.
 *
 * Auch RabbitMQ läuft hier nicht. Der Listener des Zustellwegs bleibt
 * deshalb aus (auto-startup=false), sonst würde er im Hintergrund
 * dauernd versuchen, den Broker zu erreichen.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class WebGatewayApplicationTest {

    @Test
    void contextLoads() {
    }
}
