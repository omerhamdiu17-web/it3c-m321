package ch.benedict.m321.desktopclient.api;

import ch.benedict.m321.desktopclient.FakeServer;
import ch.benedict.m321.desktopclient.auth.AccessTokenProvider;
import ch.benedict.m321.desktopclient.auth.Tokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft die Aufrufe ans Gateway. Das Gateway spielt ein FakeServer; wir
 * schauen, ob das Bearer-Token mitgeht und die Antwort richtig gelesen wird.
 */
class ChatApiClientTest {

    private static final UUID LOBBY_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = ChatApiClient.createObjectMapper();

    @Test
    void loadsRoomsWithBearerToken() throws Exception {
        String roomsJson = """
                [{"id": "00000000-0000-0000-0000-000000000001", "name": "Lobby"}]
                """;
        try (FakeServer gateway = new FakeServer("/api/rooms", 200, roomsJson)) {
            ChatApiClient chatApiClient = createClient(gateway);

            List<Room> rooms = chatApiClient.loadRooms();

            assertEquals(1, rooms.size());
            assertEquals("Lobby", rooms.get(0).name());
            FakeServer.ReceivedRequest request = gateway.receivedRequests().get(0);
            assertEquals("Bearer gueltiges-token", request.authorization());
        }
    }

    @Test
    void loadsHistoryWithTimestamps() throws Exception {
        String historyJson = """
                [{
                  "id": "3f2b1c4e-0000-0000-0000-000000000009",
                  "roomId": "00000000-0000-0000-0000-000000000001",
                  "senderId": "sub-alice",
                  "senderName": "Alice Muster",
                  "content": "Hallo Desktop",
                  "sentAt": "2026-09-23T10:00:00Z",
                  "feldDasWirNichtKennen": 42
                }]
                """;
        String path = "/api/rooms/" + LOBBY_ROOM_ID + "/messages";
        try (FakeServer gateway = new FakeServer(path, 200, historyJson)) {
            ChatApiClient chatApiClient = createClient(gateway);

            List<ChatMessage> history = chatApiClient.loadHistory(LOBBY_ROOM_ID);

            assertEquals(1, history.size());
            ChatMessage message = history.get(0);
            assertEquals("Hallo Desktop", message.content());
            assertEquals(Instant.parse("2026-09-23T10:00:00Z"), message.sentAt());
        }
    }

    @Test
    void readsCurrentUserFromGateway() throws Exception {
        String meJson = """
                {"username": "bob", "displayName": "Bob Beispiel", "admin": false}
                """;
        try (FakeServer gateway = new FakeServer("/api/me", 200, meJson)) {
            ChatApiClient chatApiClient = createClient(gateway);

            CurrentUser currentUser = chatApiClient.loadCurrentUser();

            assertEquals("Bob Beispiel", currentUser.displayName());
        }
    }

    /** Ein Client mit einem Token, das noch lange gilt; erneuert wird hier nichts. */
    private ChatApiClient createClient(FakeServer gateway) {
        Instant inOneHour = Instant.now().plusSeconds(3600);
        Tokens tokens = new Tokens("gueltiges-token", "refresh", inOneHour);
        AccessTokenProvider accessTokenProvider = new AccessTokenProvider(null, tokens);
        return new ChatApiClient(gateway.baseUrl(), httpClient, objectMapper, accessTokenProvider);
    }
}
