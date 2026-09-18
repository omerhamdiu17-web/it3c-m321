package ch.benedict.m321.webgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Einstiegspunkt des web-gateway.
 *
 * Das Gateway ist der einzige Dienst mit einem Port nach aussen. Es meldet
 * den Benutzer über Keycloak an, reicht /auth an Keycloak durch und liefert
 * die Web-Oberfläche aus. Nachrichten nimmt es per WebSocket an und reicht
 * sie per REST an den chat-service weiter; zugestellte Nachrichten liest
 * es aus RabbitMQ und schickt sie an die verbundenen Browser.
 *
 * ConfigurationPropertiesScan findet unsere KeycloakProperties.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WebGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebGatewayApplication.class, args);
    }
}
