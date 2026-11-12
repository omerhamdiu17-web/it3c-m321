package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.SseTestClient;
import ch.benedict.m321.chatservice.TestMessages;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Szenario S7 im Kleinen, über echtes HTTP und echten RabbitMQ: Ein Client
 * verliert die Verbindung und holt mit Last-Event-ID genau nach, was ihm
 * fehlt (Spezifikation 2.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconnectIntegrationTest {

    /** Der Broker, über den die Nachrichten zum Stream kommen. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Der Port, den sich Spring für diesen Test ausgesucht hat. */
    @LocalServerPort
    private int port;

    /**
     * Client bekommt 1 Nachricht und trennt. Dann kommen 50. Neu verbunden
     * mit der ID der ersten bekommt er genau diese 50, in Reihenfolge, keine
     * doppelt, und danach die nächste live.
     */
    @Test
    void reconnectWithLastEventIdDeliversExactlyTheMissedMessages() throws Exception {
        UUID roomId = UUID.randomUUID();
        SseTestClient firstConnection = SseTestClient.connect(port, roomId);
        String firstId = TestMessages.post(port, roomId, "anna", "vor dem Abbruch");
        firstConnection.waitForIds(1, 5000);
        firstConnection.close();

        List<String> missedIds = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String missedId = TestMessages.post(port, roomId, "anna", "verpasst " + i);
            missedIds.add(missedId);
        }
        Thread.sleep(1000);

        SseTestClient secondConnection = SseTestClient.connect(port, roomId, firstId);
        secondConnection.waitForIds(50, 5000);
        String liveId = TestMessages.post(port, roomId, "anna", "wieder live");
        secondConnection.waitForIds(51, 5000);

        List<String> expectedIds = new ArrayList<>(missedIds);
        expectedIds.add(liveId);
        assertEquals(List.of(firstId), firstConnection.ids());
        assertEquals(expectedIds, secondConnection.ids());
        secondConnection.close();
    }
}
