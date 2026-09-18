package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.websocket.ChatSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Meldet den WebSocket-Endpunkt an.
 *
 * Bewusst der einfache WebSocket ohne STOMP oder SockJS: eine Verbindung,
 * JSON hin und her, mehr braucht der Chat nicht. Verbinden darf sich nur
 * eine Seite vom selben Ursprung (das ist die Vorgabe von Spring), also
 * unsere eigene Web-Oberfläche.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatSocketHandler chatSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatSocketHandler, "/ws/chat");
    }
}
