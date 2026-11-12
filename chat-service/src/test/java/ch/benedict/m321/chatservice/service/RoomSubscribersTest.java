package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.DeliveredMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    void deliversOnlyToSubscribersOfTheMessagesRoom() throws InterruptedException {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        RecordingEmitter clientOneInRoomA = new RecordingEmitter();
        RecordingEmitter clientTwoInRoomA = new RecordingEmitter();
        RecordingEmitter clientInRoomB = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_A, null, clientOneInRoomA);
        roomSubscribers.subscribe(ROOM_A, null, clientTwoInRoomA);
        roomSubscribers.subscribe(ROOM_B, null, clientInRoomB);
        DeliveredMessage message = createMessage(ROOM_A, "Hallo Raum A");

        roomSubscribers.deliver(message);
        clientOneInRoomA.waitForEvents(1);
        clientTwoInRoomA.waitForEvents(1);

        String expectedEvent = "id:" + message.id() + "\ndata:" + message.json() + "\n\n";
        assertEquals(List.of(expectedEvent), clientOneInRoomA.events);
        assertEquals(List.of(expectedEvent), clientTwoInRoomA.events);
        assertTrue(clientInRoomB.events.isEmpty());
    }

    /** Ein Client, an den das Senden scheitert, fliegt raus; die anderen merken nichts. */
    @Test
    void removesSubscriberWhoseConnectionBroke() throws InterruptedException {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        RecordingEmitter brokenClient = new RecordingEmitter();
        brokenClient.broken = true;
        RecordingEmitter workingClient = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_A, null, brokenClient);
        roomSubscribers.subscribe(ROOM_A, null, workingClient);

        DeliveredMessage first = createMessage(ROOM_A, "erste");
        DeliveredMessage second = createMessage(ROOM_A, "zweite");

        roomSubscribers.deliver(first);
        brokenClient.waitForSendAttempts(1);
        workingClient.waitForEvents(1);
        roomSubscribers.deliver(second);
        workingClient.waitForEvents(2);

        assertEquals(1, brokenClient.sendAttempts);
        assertEquals(2, workingClient.events.size());
    }

    /** Nach dem Abmelden kommt nichts mehr an. */
    @Test
    void unsubscribedClientGetsNothing() throws InterruptedException {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        RecordingEmitter client = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_A, null, client);
        DeliveredMessage message = createMessage(ROOM_A, "zu spät");

        roomSubscribers.unsubscribe(ROOM_A, client);
        roomSubscribers.deliver(message);
        Thread.sleep(200);

        assertTrue(client.events.isEmpty());
    }

    /**
     * Nachholen (Spezifikation 2.4): Wer mit der ID von Nachricht 2 kommt,
     * bekommt 3, 4 und 5 in dieser Reihenfolge und danach live weiter.
     */
    @Test
    void catchUpSendsOnlyMessagesAfterLastEventIdInOrder() throws InterruptedException {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        List<DeliveredMessage> sentBefore = deliverMessages(roomSubscribers, 5);
        String lastEventId = sentBefore.get(1).id().toString();
        RecordingEmitter client = new RecordingEmitter();

        roomSubscribers.subscribe(ROOM_A, lastEventId, client);
        DeliveredMessage liveMessage = createMessage(ROOM_A, "live");
        roomSubscribers.deliver(liveMessage);
        client.waitForEvents(4);

        List<String> expectedIds = new ArrayList<>();
        expectedIds.add(sentBefore.get(2).id().toString());
        expectedIds.add(sentBefore.get(3).id().toString());
        expectedIds.add(sentBefore.get(4).id().toString());
        expectedIds.add(liveMessage.id().toString());
        assertEquals(expectedIds, client.receivedIds());
    }

    /** Unbekannte ID (Spezifikation 2.6): nichts nachholen, aber live geht es weiter. */
    @Test
    void unknownLastEventIdCatchesUpNothing() throws InterruptedException {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        deliverMessages(roomSubscribers, 3);
        RecordingEmitter client = new RecordingEmitter();
        String unknownId = UUID.randomUUID().toString();

        roomSubscribers.subscribe(ROOM_A, unknownId, client);
        DeliveredMessage liveMessage = createMessage(ROOM_A, "live");
        roomSubscribers.deliver(liveMessage);
        client.waitForEvents(1);
        Thread.sleep(200);

        String liveId = liveMessage.id().toString();
        assertEquals(List.of(liveId), client.receivedIds());
    }

    /**
     * Gemerkt werden nur die letzten 1000 pro Raum: die erste von 1001 ist
     * vergessen, also unbekannt. Ab der zweiten fehlen 999.
     */
    @Test
    void remembersOnlyTheLast1000MessagesPerRoom() throws InterruptedException {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        List<DeliveredMessage> sentBefore = deliverMessages(roomSubscribers, 1001);
        String forgottenId = sentBefore.get(0).id().toString();
        String rememberedId = sentBefore.get(1).id().toString();
        RecordingEmitter clientWithForgottenId = new RecordingEmitter();
        RecordingEmitter clientWithRememberedId = new RecordingEmitter();

        roomSubscribers.subscribe(ROOM_A, forgottenId, clientWithForgottenId);
        roomSubscribers.subscribe(ROOM_A, rememberedId, clientWithRememberedId);
        clientWithRememberedId.waitForEvents(999);
        Thread.sleep(200);

        assertEquals(0, clientWithForgottenId.events.size());
        assertEquals(999, clientWithRememberedId.events.size());
    }

    /**
     * Plan, Änderung 1: Ein Client, der beim Senden 3 s hängt, darf einen
     * zweiten Client im selben Raum nicht aufhalten. Der zweite bekommt alle
     * 1500 in weniger als 1 s. Der hängende hat danach mehr als 1000 im
     * Postausgang und wird getrennt.
     */
    @Test
    void hangingClientDoesNotHoldUpOtherClients() throws InterruptedException {
        RoomSubscribers roomSubscribers = new RoomSubscribers();
        RecordingEmitter hangingClient = new RecordingEmitter();
        hangingClient.hangOnFirstSendMilliseconds = 3000;
        RecordingEmitter workingClient = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_A, null, hangingClient);
        roomSubscribers.subscribe(ROOM_A, null, workingClient);
        long start = System.currentTimeMillis();

        deliverMessages(roomSubscribers, 1500);
        workingClient.waitForEvents(1500);
        long elapsedMilliseconds = System.currentTimeMillis() - start;
        hangingClient.waitForCompleted();

        assertEquals(1500, workingClient.events.size());
        assertTrue(elapsedMilliseconds < 1000, "dauerte " + elapsedMilliseconds + " ms");
        assertTrue(hangingClient.completed);
    }

    /** Liefert count Nachrichten in Raum A aus, noch ohne Client, und gibt sie zurück. */
    private List<DeliveredMessage> deliverMessages(RoomSubscribers roomSubscribers, int count) {
        List<DeliveredMessage> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            DeliveredMessage message = createMessage(ROOM_A, "Nachricht " + i);
            roomSubscribers.deliver(message);
            messages.add(message);
        }
        return messages;
    }

    /** Baut eine Nachricht mit neuer ID und dem JSON, wie es vom Fanout käme. */
    private DeliveredMessage createMessage(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        String json = "{\"id\":\"" + messageId + "\",\"roomId\":\"" + roomId + "\",\"content\":\"" + content + "\"}";
        return new DeliveredMessage(messageId, roomId, json);
    }
}
