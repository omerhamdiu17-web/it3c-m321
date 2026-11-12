package ch.benedict.m321.chatservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Szenario S3 im Kleinen: zwei Instanzen des chat-service am selben RabbitMQ,
 * je ein Client in Raum A an jeder Instanz, ein dritter in Raum B
 * (Spezifikation 2.1, 2.2, 3.1).
 */
@Testcontainers
class TwoInstancesIntegrationTest {

    /** So viele Nachrichten schickt jeder der beiden Absender. */
    private static final int MESSAGES_PER_SENDER = 50;

    /** Beide Instanzen hängen an diesem einen Broker, wie im docker compose. */
    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Liest das JSON aus den data-Zeilen. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Über jede Instanz schickt ein eigener Absender 50 Nachrichten an Raum A,
     * abwechselnd. Beide Clients in Raum A bekommen alle 100, jede genau
     * einmal, und die Texte jedes Absenders in seiner Reihenfolge. Der Client
     * in Raum B bekommt keine.
     */
    @Test
    void bothClientsInRoomGetEveryMessageExactlyOnceInSenderOrder() throws Exception {
        ConfigurableApplicationContext instanceOne = ChatServiceStarter.start(rabbitMq);
        ConfigurableApplicationContext instanceTwo = ChatServiceStarter.start(rabbitMq);
        int portOne = ChatServiceStarter.portOf(instanceOne);
        int portTwo = ChatServiceStarter.portOf(instanceTwo);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        SseTestClient clientAtInstanceOne = SseTestClient.connect(portOne, roomA);
        SseTestClient clientAtInstanceTwo = SseTestClient.connect(portTwo, roomA);
        SseTestClient clientInRoomB = SseTestClient.connect(portOne, roomB);

        for (int i = 1; i <= MESSAGES_PER_SENDER; i++) {
            TestMessages.post(portOne, roomA, "sender-one", String.valueOf(i));
            TestMessages.post(portTwo, roomA, "sender-two", String.valueOf(i));
        }
        int expectedCount = 2 * MESSAGES_PER_SENDER;
        clientAtInstanceOne.waitForIds(expectedCount, 10000);
        clientAtInstanceTwo.waitForIds(expectedCount, 10000);
        Thread.sleep(500);

        assertReceivedAllInSenderOrder(clientAtInstanceOne, expectedCount);
        assertReceivedAllInSenderOrder(clientAtInstanceTwo, expectedCount);
        assertEquals(0, clientInRoomB.ids().size());

        clientAtInstanceOne.close();
        clientAtInstanceTwo.close();
        clientInRoomB.close();
        instanceOne.close();
        instanceTwo.close();
    }

    /**
     * Prüft einen Client: genau expectedCount verschiedene IDs, und pro
     * Absender kommen die Texte als 1, 2, 3, … ohne Lücke und ohne Sprung.
     */
    private void assertReceivedAllInSenderOrder(SseTestClient client, int expectedCount) throws Exception {
        List<String> ids = client.ids();
        Set<String> distinctIds = new HashSet<>(ids);
        assertEquals(expectedCount, ids.size());
        assertEquals(expectedCount, distinctIds.size());

        List<String> fromSenderOne = new ArrayList<>();
        List<String> fromSenderTwo = new ArrayList<>();
        for (String json : client.data()) {
            JsonNode message = objectMapper.readTree(json);
            String senderId = message.get("senderId").asText();
            String content = message.get("content").asText();
            if (senderId.equals("sender-one")) {
                fromSenderOne.add(content);
            } else {
                fromSenderTwo.add(content);
            }
        }
        List<String> expectedOrder = new ArrayList<>();
        for (int i = 1; i <= MESSAGES_PER_SENDER; i++) {
            expectedOrder.add(String.valueOf(i));
        }
        assertEquals(expectedOrder, fromSenderOne);
        assertEquals(expectedOrder, fromSenderTwo);
    }
}
