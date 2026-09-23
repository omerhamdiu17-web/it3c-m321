package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.dto.ServerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prüft, dass eine Nachricht nur bei den Browsern ankommt, die ihren Raum
 * offen haben. Die Browser sind Attrappen (Mocks).
 */
@ExtendWith(MockitoExtension.class)
class ChatSessionRegistryTest {

    private static final UUID LOBBY_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID M321_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private WebSocketSession browserInLobby;

    @Mock
    private WebSocketSession browserInM321;

    private ChatSessionRegistry chatSessionRegistry;

    @BeforeEach
    void registerTwoBrowsersInDifferentRooms() {
        ObjectMapper objectMapper = new ObjectMapper();
        chatSessionRegistry = new ChatSessionRegistry(objectMapper);

        when(browserInLobby.getId()).thenReturn("session-lobby");
        when(browserInM321.getId()).thenReturn("session-m321");
        chatSessionRegistry.register(browserInLobby, LOBBY_ROOM_ID);
        chatSessionRegistry.register(browserInM321, M321_ROOM_ID);
    }

    @Test
    void sendsOnlyToBrowsersInTheRoom() throws Exception {
        ServerEvent event = ServerEvent.error("nur für die Lobby");

        chatSessionRegistry.sendToRoom(LOBBY_ROOM_ID, event);

        verify(browserInLobby).sendMessage(any(TextMessage.class));
        verify(browserInM321, never()).sendMessage(any());
    }

    @Test
    void forgetsRemovedBrowser() throws Exception {
        chatSessionRegistry.remove("session-lobby");
        ServerEvent event = ServerEvent.error("niemand mehr da");

        chatSessionRegistry.sendToRoom(LOBBY_ROOM_ID, event);

        verify(browserInLobby, never()).sendMessage(any());
    }
}
