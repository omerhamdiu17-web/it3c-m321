package ch.benedict.m321.webgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des web-gateway.
 *
 * Das Gateway ist der einzige Dienst mit einem Port nach aussen
 * (127.0.0.1:8080). Darum läuft alles, was der Browser braucht, hier durch:
 * Es meldet Benutzer über Keycloak an, reicht die Pfade unter /auth an
 * Keycloak im Docker-Netz durch und liefert die Web-UI aus. Später nimmt es
 * Nachrichten über WebSocket an, schickt sie an den chat-service und stellt
 * jede zugestellte Nachricht den verbundenen Browsern zu (Spezifikation 1.2).
 */
@SpringBootApplication
public class WebGatewayApplication {

    /**
     * Startet Spring. Mehr passiert hier nicht: den Rest finden die
     * Konfiguration und die Controller, die Spring selbst entdeckt.
     */
    public static void main(String[] args) {
        SpringApplication.run(WebGatewayApplication.class, args);
    }
}
