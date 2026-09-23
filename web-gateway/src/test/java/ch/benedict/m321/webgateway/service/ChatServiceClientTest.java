package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.dto.AcceptedResponse;
import ch.benedict.m321.webgateway.dto.ChatMessage;
import ch.benedict.m321.webgateway.dto.Room;
import ch.benedict.m321.webgateway.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Prüft den Sendeweg, ohne dass ein chat-service läuft.
 *
 * MockRestServiceServer spielt den chat-service: er prüft, was das Gateway
 * schickt, und antwortet so, wie wir es vorgeben. Ein Spring-Kontext ist
 * dafür nicht nötig, der Test ist in Millisekunden durch.
 */
class ChatServiceClientTest {

    private static final String CHAT_SERVICE_BASE_URL = "http://chat-service:8080";

    private MockRestServiceServer chatServiceMock;
    private ChatServiceClient chatServiceClient;

    @BeforeEach
    void createClientWithMockedServer() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        chatServiceMock = MockRestServiceServer.bindTo(restClientBuilder).build();
        chatServiceClient = new ChatServiceClient(restClientBuilder, CHAT_SERVICE_BASE_URL);
    }

    @Test
    void postsMessageAndReturnsAcceptedResponse() {
        UUID roomId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String responseJson = "{\"id\":\"" + messageId + "\",\"sentAt\":\"2026-09-18T12:00:00Z\"}";

        chatServiceMock.expect(requestTo(CHAT_SERVICE_BASE_URL + "/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.roomId").value(roomId.toString()))
                .andExpect(jsonPath("$.senderId").value("sub-alice"))
                .andExpect(jsonPath("$.senderName").value("Alice Muster"))
                .andExpect(jsonPath("$.content").value("Hallo"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseJson));

        SendMessageRequest request = new SendMessageRequest(roomId, "sub-alice", "Alice Muster", "Hallo");
        AcceptedResponse acceptedResponse = chatServiceClient.send(request);

        assertEquals(messageId, acceptedResponse.id());
        chatServiceMock.verify();
    }

    @Test
    void throwsWhenChatServiceReportsBrokerDown() {
        chatServiceMock.expect(requestTo(CHAT_SERVICE_BASE_URL + "/messages"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        UUID roomId = UUID.randomUUID();
        SendMessageRequest request = new SendMessageRequest(roomId, "sub-alice", "Alice Muster", "Hallo");

        assertThrows(RestClientException.class, () -> chatServiceClient.send(request));
    }

    @Test
    void loadsRooms() {
        String responseJson = """
                [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "Lobby"},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "M321"}
                ]
                """;
        chatServiceMock.expect(requestTo(CHAT_SERVICE_BASE_URL + "/rooms"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseJson));

        List<Room> rooms = chatServiceClient.loadRooms();

        assertEquals(2, rooms.size());
        assertEquals("Lobby", rooms.get(0).name());
        chatServiceMock.verify();
    }

    @Test
    void loadsHistory() {
        UUID roomId = UUID.randomUUID();
        String responseJson = """
                [
                  {
                    "id": "3f2b1c4e-0000-0000-0000-000000000009",
                    "roomId": "%s",
                    "senderId": "sub-alice",
                    "senderName": "Alice Muster",
                    "content": "Hallo Verlauf",
                    "sentAt": "2026-09-23T10:00:00Z"
                  }
                ]
                """.formatted(roomId);
        chatServiceMock.expect(requestTo(CHAT_SERVICE_BASE_URL + "/rooms/" + roomId + "/messages"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseJson));

        List<ChatMessage> history = chatServiceClient.loadHistory(roomId);

        assertEquals(1, history.size());
        assertEquals("Hallo Verlauf", history.get(0).content());
        assertEquals(roomId, history.get(0).roomId());
        chatServiceMock.verify();
    }
}
