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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Merkt sich alle Browser, die gerade per WebSocket verbunden sind.
 *
 * Genau das ist der Grund, warum JEDE Gateway-Instanz JEDE Nachricht sehen
 * muss: nur sie weiss, welche Verbindungen an ihr hängen.
 *
 * In Schritt 3 gibt es nur einen einzigen Raum, deshalb geht eine Nachricht
 * an alle Verbindungen. Sobald es mehrere Räume gibt (Schritt 4, mit der
 * Datenbank), wird hier nach Raum gefiltert.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatSessionRegistry {

    /** Braucht ein Browser länger als das, um eine Nachricht abzunehmen, wird er getrennt. */
    private static final int SEND_TIME_LIMIT_MILLISECONDS = 5000;

    /** So viel darf sich für einen langsamen Browser höchstens aufstauen. */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;

    /** Schlüssel ist die ID der Verbindung. ConcurrentHashMap, weil mehrere Threads zugreifen. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Nimmt eine neue Verbindung auf.
     *
     * Auf eine WebSocket-Verbindung darf immer nur EIN Thread gleichzeitig
     * schreiben. Bei uns schreiben aber zwei: der Thread des Browsers
     * (Bestätigung) und der Thread von RabbitMQ (Zustellung). Der
     * Decorator stellt die Schreibvorgänge hintereinander an.
     */
    public void register(WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLISECONDS, SEND_BUFFER_LIMIT_BYTES);
        sessions.put(session.getId(), safeSession);
        log.debug("WebSocket session {} registered, {} connected", session.getId(), sessions.size());
    }

    /** Vergisst eine Verbindung, nachdem der Browser sie geschlossen hat. */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
        log.debug("WebSocket session {} removed, {} connected", sessionId, sessions.size());
    }

    /** Schickt ein Ereignis an genau einen Browser, z.B. die Bestätigung an den Absender. */
    public void sendTo(String sessionId, ServerEvent event) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        String json = toJson(event);
        send(session, json);
    }

    /** Schickt ein Ereignis an alle verbundenen Browser. Das JSON wird nur einmal gebaut. */
    public void sendToAll(ServerEvent event) {
        String json = toJson(event);
        for (WebSocketSession session : sessions.values()) {
            send(session, json);
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
