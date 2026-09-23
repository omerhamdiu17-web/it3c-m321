package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.dto.ServerEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Merkt sich alle Browser, die gerade per WebSocket verbunden sind, und
 * in welchem Raum jeder von ihnen sitzt.
 *
 * Genau das ist der Grund, warum JEDE Gateway-Instanz JEDE Nachricht sehen
 * muss: nur sie weiss, welche Verbindungen an ihr hängen und in welchem
 * Raum. Eine Nachricht geht nur an die Verbindungen ihres Raums.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatSessionRegistry {

    /** Braucht ein Browser länger als das, um eine Nachricht abzunehmen, wird er getrennt. */
    private static final int SEND_TIME_LIMIT_MILLISECONDS = 5000;

    /** So viel darf sich für einen langsamen Browser höchstens aufstauen. */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    /**
     * Eine Verbindung zusammen mit dem Raum, den der Browser offen hat.
     *
     * @param session die WebSocket-Verbindung
     * @param roomId  der Raum, aus dem diese Verbindung Nachrichten bekommt
     */
    private record Connection(WebSocketSession session, UUID roomId) {
    }

    private final ObjectMapper objectMapper;

    /** Schlüssel ist die ID der Verbindung. ConcurrentHashMap, weil mehrere Threads zugreifen. */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * Nimmt eine neue Verbindung für einen Raum auf.
     *
     * Auf eine WebSocket-Verbindung darf immer nur EIN Thread gleichzeitig
     * schreiben. Bei uns schreiben aber zwei: der Thread des Browsers
     * (Bestätigung) und der Thread von RabbitMQ (Zustellung). Der
     * Decorator stellt die Schreibvorgänge hintereinander an.
     */
    public void register(WebSocketSession session, UUID roomId) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLISECONDS, SEND_BUFFER_LIMIT_BYTES);
        Connection connection = new Connection(safeSession, roomId);
        connections.put(session.getId(), connection);
        log.debug("WebSocket session {} registered for room {}, {} connected",
                session.getId(), roomId, connections.size());
    }

    /** Vergisst eine Verbindung, nachdem der Browser sie geschlossen hat. */
    public void remove(String sessionId) {
        connections.remove(sessionId);
        log.debug("WebSocket session {} removed, {} connected", sessionId, connections.size());
    }

    /** Schickt ein Ereignis an genau einen Browser, z.B. die Bestätigung an den Absender. */
    public void sendTo(String sessionId, ServerEvent event) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            return;
        }
        String json = toJson(event);
        send(connection.session(), json);
    }

    /**
     * Schickt ein Ereignis an alle Browser, die diesen Raum offen haben.
     * Das JSON wird nur einmal gebaut, egal wie viele es sind.
     */
    public void sendToRoom(UUID roomId, ServerEvent event) {
        String json = toJson(event);
        for (Connection connection : connections.values()) {
            if (roomId.equals(connection.roomId())) {
                send(connection.session(), json);
            }
        }
    }

    /**
     * Schreibt auf eine Verbindung. Ein Fehler bei EINEM Browser darf die
     * Zustellung an die anderen nicht abbrechen, deshalb wird er hier
     * abgefangen und nur protokolliert.
     */
    private void send(WebSocketSession session, String json) {
        try {
            TextMessage textMessage = new TextMessage(json);
            session.sendMessage(textMessage);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not send to WebSocket session {}", session.getId(), exception);
        }
    }

    /** Wandelt das Ereignis in JSON um. Bei unseren eigenen records kann das nicht fehlschlagen. */
    private String toJson(ServerEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("ServerEvent is not serializable", exception);
        }
    }
}
