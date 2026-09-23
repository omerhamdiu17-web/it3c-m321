package ch.benedict.m321.webgateway.websocket;

import ch.benedict.m321.webgateway.dto.AcceptedResponse;
import ch.benedict.m321.webgateway.dto.IncomingChatMessage;
import ch.benedict.m321.webgateway.dto.SendMessageRequest;
import ch.benedict.m321.webgateway.dto.ServerEvent;
import ch.benedict.m321.webgateway.service.ChatServiceClient;
import ch.benedict.m321.webgateway.service.ChatSessionRegistry;
import ch.benedict.m321.webgateway.service.LoggedInUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;

/**
 * Der WebSocket-Endpunkt /ws/chat: Übersetzer zwischen Browser und interner API.
 *
 * Der Handler entscheidet nichts. Er nimmt den Text vom Browser, ergänzt
 * den Absender aus dem Login und reicht alles an den chat-service weiter.
 *
 * Eine eigene Anmeldung braucht der WebSocket nicht: der Verbindungsaufbau
 * ist eine normale HTTP-Anfrage mit dem Session-Cookie, und die lässt
 * Spring Security nur mit Anmeldung durch (siehe SecurityConfig).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatSocketHandler extends TextWebSocketHandler {

    private final ChatServiceClient chatServiceClient;
    private final ChatSessionRegistry chatSessionRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Neue Verbindung: ab jetzt bekommt dieser Browser die Nachrichten
     * seines Raums.
     *
     * Den Raum schickt der Browser in der Adresse mit: /ws/chat?roomId=...
     * Fehlt er oder ist er keine gültige ID, wird die Verbindung sofort
     * wieder geschlossen — sonst hinge ein Browser im Nirgendwo.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        UUID roomId = readRoomId(session);
        if (roomId == null) {
            log.warn("WebSocket session {} has no valid roomId, closing it", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        chatSessionRegistry.register(session, roomId);
    }

    /**
     * Liest den Parameter roomId aus der Adresse der Verbindung.
     * Gibt null zurück, wenn er fehlt oder keine UUID ist.
     */
    private UUID readRoomId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        UriComponents uriComponents = UriComponentsBuilder.fromUri(uri).build();
        MultiValueMap<String, String> queryParameters = uriComponents.getQueryParams();
        String roomIdText = queryParameters.getFirst("roomId");
        if (roomIdText == null) {
            return null;
        }
        try {
            return UUID.fromString(roomIdText);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Verbindung zu: nichts mehr an diesen Browser schicken. */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        chatSessionRegistry.remove(session.getId());
    }

    /**
     * Der Browser hat eine Nachricht geschickt.
     *
     * Der Absender bekommt auf jeden Fall eine Antwort: "accepted", wenn
     * der chat-service die Nachricht angenommen hat, sonst "error". Die
     * Nachricht selbst sieht er erst, wenn sie über den Zustellweg
     * zurückkommt, genau wie alle anderen.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        String sessionId = session.getId();
        try {
            String json = textMessage.getPayload();
            IncomingChatMessage incoming = objectMapper.readValue(json, IncomingChatMessage.class);
            SendMessageRequest request = buildRequest(session, incoming);

            AcceptedResponse acceptedResponse = chatServiceClient.send(request);

            ServerEvent acceptedEvent = ServerEvent.accepted(acceptedResponse);
            chatSessionRegistry.sendTo(sessionId, acceptedEvent);
        } catch (JsonProcessingException | RestClientException exception) {
            log.warn("Message from WebSocket session {} was not sent", sessionId, exception);

            ServerEvent errorEvent = ServerEvent.error("Nachricht nicht gesendet.");
            chatSessionRegistry.sendTo(sessionId, errorEvent);
        }
    }

    /**
     * Baut die Anfrage an den chat-service.
     *
     * Raum und Text kommen vom Browser, der Absender kommt aus dem Login.
     * Beim Verbindungsaufbau hat Spring den angemeldeten Benutzer an die
     * WebSocket-Verbindung gehängt; dort holen wir ihn ab.
     */
    private SendMessageRequest buildRequest(WebSocketSession session, IncomingChatMessage incoming) {
        Principal principal = session.getPrincipal();
        Authentication authentication = (Authentication) principal;
        ClaimAccessor claims = (ClaimAccessor) authentication.getPrincipal();
        LoggedInUser sender = LoggedInUser.fromClaims(claims);

        return new SendMessageRequest(incoming.roomId(), sender.subject(), sender.displayName(), incoming.content());
    }
}
