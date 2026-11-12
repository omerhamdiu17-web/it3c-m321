package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.SseTestClient;
import ch.benedict.m321.chatservice.TestMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft GET /stream von aussen, über echtes HTTP auf einem freien Port, mit
 * echtem RabbitMQ (Spezifikation 1.3, 2.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StreamEndpointIntegrationTest {

    /** Der Broker, über den die Nachricht zum Stream kommt. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Der Port, den sich Spring für diesen Test ausgesucht hat. */
    @LocalServerPort
    private int port;

    /**
     * Der Normalfall: Antwort 200 mit text/event-stream, und eine Nachricht
     * kommt mit "id:" und mit "data:" als JSON mit den 6 Feldern.
     */
    @Test
    void messageArrivesWithIdAndData() throws Exception {
        UUID roomId = UUID.randomUUID();
        SseTestClient client = SseTestClient.connect(port, roomId);

        String messageId = TestMessages.post(port, roomId, "anna", "Grüezi");
        client.waitForIds(1, 5000);

        assertEquals(200, client.statusCode());
        assertTrue(client.contentType().startsWith("text/event-stream"), client.contentType());
        assertEquals(List.of(messageId), client.ids());
        String json = client.data().get(0);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode message = objectMapper.readTree(json);
        List<String> fieldNames = fieldNamesOf(message);
        assertEquals(List.of("id", "roomId", "senderId", "senderName", "content", "sentAt"), fieldNames);
        assertEquals("Grüezi", message.get("content").asText());
        client.close();
    }

    /** Ein Client in einem anderen Raum bekommt nichts. */
    @Test
    void clientInOtherRoomGetsNothing() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        SseTestClient clientInRoomA = SseTestClient.connect(port, roomA);
        SseTestClient clientInRoomB = SseTestClient.connect(port, roomB);

        TestMessages.post(port, roomA, "anna", "nur für A");
        clientInRoomA.waitForIds(1, 5000);
        Thread.sleep(500);

        assertEquals(1, clientInRoomA.ids().size());
        assertEquals(0, clientInRoomB.ids().size());
        clientInRoomA.close();
        clientInRoomB.close();
    }

    /** Ohne Raum oder mit einem Raum, der keine UUID ist, gibt es 400. */
    @Test
    void invalidRoomIsRejected() throws Exception {
        int withoutRoom = TestMessages.statusOfGet(port, "/stream");
        int withBrokenRoom = TestMessages.statusOfGet(port, "/stream?roomId=kaputt");

        assertEquals(400, withoutRoom);
        assertEquals(400, withBrokenRoom);
    }

    /** Die Namen der Felder eines JSON-Objekts, in ihrer Reihenfolge. */
    private List<String> fieldNamesOf(JsonNode message) {
        List<String> fieldNames = new ArrayList<>();
        Iterator<String> names = message.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            fieldNames.add(name);
        }
        return fieldNames;
    }
}
