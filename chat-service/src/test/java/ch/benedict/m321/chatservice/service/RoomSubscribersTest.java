package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.DeliveredMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft, wer eine Nachricht bekommt, ohne Broker und ohne Web: nur die
 * Verbindungen im Raum der Nachricht (Spezifikation 2.1, S3).
 */
class RoomSubscribersTest {

    /** Raum A im Test. */
    private static final UUID ROOM_A = UUID.fromString("3f2b1c4e-0000-0000-0000-00000000000a");

    /** Raum B im Test. */
    private static final UUID ROOM_B = UUID.fromString("3f2b1c4e-0000-0000-0000-00000000000b");

    /** Zwei Clients in Raum A bekommen die Nachricht für Raum A, der Client in Raum B nicht. */
    @Test
    void deliversOnlyToSubscribersOfTheMessagesRoom() {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        RecordingEmitter clientOneInRoomA = new RecordingEmitter();
        RecordingEmitter clientTwoInRoomA = new RecordingEmitter();
        RecordingEmitter clientInRoomB = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_A, clientOneInRoomA);
        roomSubscribers.subscribe(ROOM_A, clientTwoInRoomA);
        roomSubscribers.subscribe(ROOM_B, clientInRoomB);
        DeliveredMessage message = createMessage(ROOM_A, "Hallo Raum A");

        roomSubscribers.deliver(message);

        String expectedEvent = "id:" + message.id() + "\ndata:" + message.json() + "\n\n";
        assertEquals(List.of(expectedEvent), clientOneInRoomA.events);
        assertEquals(List.of(expectedEvent), clientTwoInRoomA.events);
        assertTrue(clientInRoomB.events.isEmpty());
    }

    /** Ein Client, an den das Senden scheitert, fliegt raus; die anderen merken nichts. */
    @Test
    void removesSubscriberWhoseConnectionBroke() {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        RecordingEmitter brokenClient = new RecordingEmitter();
        brokenClient.broken = true;
        RecordingEmitter workingClient = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_A, brokenClient);
        roomSubscribers.subscribe(ROOM_A, workingClient);

        DeliveredMessage first = createMessage(ROOM_A, "erste");
        DeliveredMessage second = createMessage(ROOM_A, "zweite");

        roomSubscribers.deliver(first);
        roomSubscribers.deliver(second);

        assertEquals(1, brokenClient.sendAttempts);
        assertEquals(2, workingClient.events.size());
    }

    /** Nach dem Abmelden kommt nichts mehr an. */
    @Test
    void unsubscribedClientGetsNothing() {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        RecordingEmitter client = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_A, client);
        DeliveredMessage message = createMessage(ROOM_A, "zu spät");

        roomSubscribers.unsubscribe(ROOM_A, client);
        roomSubscribers.deliver(message);

        assertTrue(client.events.isEmpty());
    }

    /** Baut eine Nachricht mit neuer ID und dem JSON, wie es vom Fanout käme. */
    private DeliveredMessage createMessage(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        String json = "{\"id\":\"" + messageId + "\",\"roomId\":\"" + roomId + "\",\"content\":\"" + content + "\"}";
        return new DeliveredMessage(messageId, roomId, json);
    }
}
