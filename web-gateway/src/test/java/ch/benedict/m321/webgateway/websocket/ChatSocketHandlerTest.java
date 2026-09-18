package ch.benedict.m321.webgateway.websocket;

import ch.benedict.m321.webgateway.dto.AcceptedResponse;
import ch.benedict.m321.webgateway.dto.SendMessageRequest;
import ch.benedict.m321.webgateway.dto.ServerEvent;
import ch.benedict.m321.webgateway.service.ChatServiceClient;
import ch.benedict.m321.webgateway.service.ChatSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prüft den WebSocket-Handler ohne Netzwerk: chat-service und Browser
 * sind Attrappen (Mocks). So sieht man genau, was der Handler weiterreicht.
 */
@ExtendWith(MockitoExtension.class)
class ChatSocketHandlerTest {

    private static final String SESSION_ID = "session-1";
    private static final UUID ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ChatServiceClient chatServiceClient;

    @Mock
    private ChatSessionRegistry chatSessionRegistry;

    @Mock
    private WebSocketSession session;

    private ChatSocketHandler chatSocketHandler;

    @BeforeEach
    void createHandler() {
        ObjectMapper objectMapper = new ObjectMapper();
        chatSocketHandler = new ChatSocketHandler(chatServiceClient, chatSessionRegistry, objectMapper);
        when(session.getId()).thenReturn(SESSION_ID);
    }

    @Test
    void takesSenderFromLoginAndNotFromBrowser() throws Exception {
        when(session.getPrincipal()).thenReturn(loggedInAlice());
        AcceptedResponse acceptedResponse = new AcceptedResponse(UUID.randomUUID(), Instant.now());
        when(chatServiceClient.send(any())).thenReturn(acceptedResponse);

        // Der Browser versucht, sich als Bob auszugeben. Das Feld wird ignoriert.
        String json = "{\"roomId\":\"" + ROOM_ID + "\",\"content\":\"Hallo\",\"senderName\":\"Bob\"}";
        chatSocketHandler.handleMessage(session, new TextMessage(json));

        ArgumentCaptor<SendMessageRequest> requestCaptor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(chatServiceClient).send(requestCaptor.capture());
        SendMessageRequest request = requestCaptor.getValue();
        assertEquals(ROOM_ID, request.roomId());
        assertEquals("sub-alice", request.senderId());
        assertEquals("Alice Muster", request.senderName());
        assertEquals("Hallo", request.content());
    }

    @Test
    void confirmsAcceptedMessageToSender() throws Exception {
        when(session.getPrincipal()).thenReturn(loggedInAlice());
        AcceptedResponse acceptedResponse = new AcceptedResponse(UUID.randomUUID(), Instant.now());
        when(chatServiceClient.send(any())).thenReturn(acceptedResponse);

        String json = "{\"roomId\":\"" + ROOM_ID + "\",\"content\":\"Hallo\"}";
        chatSocketHandler.handleMessage(session, new TextMessage(json));

        ServerEvent expectedEvent = ServerEvent.accepted(acceptedResponse);
        verify(chatSessionRegistry).sendTo(SESSION_ID, expectedEvent);
    }

    @Test
    void reportsErrorToSenderWhenChatServiceIsDown() throws Exception {
        when(session.getPrincipal()).thenReturn(loggedInAlice());
        when(chatServiceClient.send(any())).thenThrow(new ResourceAccessException("chat-service not reachable"));

        String json = "{\"roomId\":\"" + ROOM_ID + "\",\"content\":\"Hallo\"}";
        chatSocketHandler.handleMessage(session, new TextMessage(json));

        ServerEvent expectedEvent = ServerEvent.error("Nachricht nicht gesendet.");
        verify(chatSessionRegistry).sendTo(SESSION_ID, expectedEvent);
    }

    @Test
    void reportsErrorToSenderWhenJsonIsBroken() throws Exception {
        chatSocketHandler.handleMessage(session, new TextMessage("das ist kein JSON"));

        verify(chatServiceClient, never()).send(any());
        ServerEvent expectedEvent = ServerEvent.error("Nachricht nicht gesendet.");
        verify(chatSessionRegistry).sendTo(eq(SESSION_ID), eq(expectedEvent));
    }

    /** Baut eine Anmeldung, wie sie Spring nach dem Login bei Keycloak an die Verbindung hängt. */
    private Authentication loggedInAlice() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .subject("sub-alice")
                .claim("preferred_username", "alice")
                .claim("name", "Alice Muster")
                .build();
        OidcUser user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken);
        return new OAuth2AuthenticationToken(user, AuthorityUtils.NO_AUTHORITIES, "keycloak");
    }
}
