package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.DeliveredMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merkt sich, welche offenen SSE-Verbindungen dieser Instanz zu welchem Raum
 * gehören, und gibt jede Nachricht nur an die Verbindungen ihres Raums weiter
 * (Spezifikation 2.1). Dazu merkt sie sich pro Raum die letzten 1000
 * Nachrichten, damit ein Client nach einem Abbruch nachholen kann
 * (Spezifikation 2.4).
 *
 * "Weitergeben" heisst: in den Postausgang des Clients legen (Subscriber).
 * Gesendet wird dort, im eigenen Thread des Clients. So hält ein hängender
 * Client den Leser der Queue nicht auf (Plan, Änderung 1).
 *
 * Alle Methoden sind synchronized, es gibt also EINE Sperre für alles. Dadurch
 * läuft nie ein Weitergeben gleichzeitig mit einem An- oder Abmelden:
 * - Jeder Postausgang bekommt die Nachrichten in der Reihenfolge, in der sie
 *   aus der Queue kamen (Spezifikation 2.2).
 * - Zwischen Nachholen und Anmelden kann keine neue Nachricht durchrutschen.
 *   Darum fehlt beim Nachholen nichts, und nichts kommt doppelt (S7).
 */
@Component
@Slf4j
public class RoomSubscribers {

    /** So viele Nachrichten merkt sich jede Instanz pro Raum (Spezifikation 2.4). */
    static final int RECENT_MESSAGES_PER_ROOM = 1000;

    /** Raum → offene Verbindungen in diesem Raum. */
    private final Map<UUID, List<Subscriber>> subscribersByRoom = new HashMap<>();

    /** Raum → die letzten Nachrichten, die älteste vorne, die neuste hinten. */
    private final Map<UUID, Deque<DeliveredMessage>> recentMessagesByRoom = new HashMap<>();

    /**
     * Meldet eine Verbindung für einen Raum an. Ab jetzt bekommt sie dessen
     * Nachrichten.
     *
     * Kommt der Client mit einer lastEventId zurück, kommt VOR dem Anmelden
     * alles, was danach kam, in seinen Postausgang. Kennen wir die ID nicht
     * (zu alt oder die Instanz ist neu), holen wir nichts nach, warnen im Log
     * und melden ihn nur für live an (Spezifikation 2.6).
     */
    public synchronized void subscribe(UUID roomId, String lastEventId, SseEmitter emitter) {
        Subscriber subscriber = new Subscriber(emitter);
        if (lastEventId != null) {
            queueMissedMessages(roomId, lastEventId, subscriber);
        }
        subscriber.start();
        List<Subscriber> subscribers = subscribersByRoom.get(roomId);
        if (subscribers == null) {
            subscribers = new ArrayList<>();
            subscribersByRoom.put(roomId, subscribers);
        }
        subscribers.add(subscriber);
        log.debug("Subscriber added to room {}, now {}", roomId, subscribers.size());
    }

    /**
     * Meldet eine Verbindung ab, z. B. weil der Client weg ist. Ein leerer
     * Raum wird ganz entfernt, damit die Liste nicht endlos wächst.
     */
    public synchronized void unsubscribe(UUID roomId, SseEmitter emitter) {
        List<Subscriber> subscribers = subscribersByRoom.get(roomId);
        if (subscribers == null) {
            return;
        }
        Subscriber found = null;
        for (Subscriber subscriber : subscribers) {
            if (subscriber.isFor(emitter)) {
                found = subscriber;
            }
        }
        if (found != null) {
            remove(roomId, found);
        }
    }

    /**
     * Merkt sich die Nachricht und legt sie in den Postausgang jeder
     * Verbindung ihres Raums. Nimmt ein Postausgang nichts mehr an (Verbindung
     * zu oder Client kommt nicht mehr mit), wird nur diese Verbindung getrennt.
     *
     * Wir gehen durch eine Kopie der Liste. So dürfen wir unterwegs entfernen.
     */
    public synchronized void deliver(DeliveredMessage message) {
        UUID roomId = message.roomId();
        remember(message);
        List<Subscriber> subscribers = subscribersByRoom.get(roomId);
        if (subscribers == null) {
            return;
        }
        List<Subscriber> receivers = new ArrayList<>(subscribers);
        for (Subscriber subscriber : receivers) {
            boolean accepted = subscriber.offer(message);
            if (!accepted) {
                log.warn("Subscriber in room {} is closed or too slow, disconnecting it", roomId);
                remove(roomId, subscriber);
            }
        }
    }

    /** Entfernt eine Verbindung aus ihrem Raum und schliesst sie. */
    private void remove(UUID roomId, Subscriber subscriber) {
        List<Subscriber> subscribers = subscribersByRoom.get(roomId);
        subscribers.remove(subscriber);
        if (subscribers.isEmpty()) {
            subscribersByRoom.remove(roomId);
        }
        subscriber.close();
        log.debug("Subscriber removed from room {}", roomId);
    }

    /**
     * Legt die Nachricht hinten an die Liste ihres Raums. Sind es mehr als
     * 1000, fällt die älteste vorne raus.
     */
    private void remember(DeliveredMessage message) {
        UUID roomId = message.roomId();
        Deque<DeliveredMessage> recentMessages = recentMessagesByRoom.get(roomId);
        if (recentMessages == null) {
            recentMessages = new ArrayDeque<>();
            recentMessagesByRoom.put(roomId, recentMessages);
        }
        recentMessages.addLast(message);
        if (recentMessages.size() > RECENT_MESSAGES_PER_ROOM) {
            recentMessages.removeFirst();
        }
    }

    /**
     * Legt alles, was nach lastEventId kam, in den Postausgang. Es sind
     * höchstens 999, der Postausgang fasst 1000, es passt also immer.
     */
    private void queueMissedMessages(UUID roomId, String lastEventId, Subscriber subscriber) {
        List<DeliveredMessage> missedMessages = messagesAfter(roomId, lastEventId);
        if (missedMessages == null) {
            log.warn("Unknown Last-Event-ID {} for room {}, nothing to catch up", lastEventId, roomId);
            return;
        }
        log.info("Catching up {} messages after {} in room {}", missedMessages.size(), lastEventId, roomId);
        for (DeliveredMessage missedMessage : missedMessages) {
            subscriber.offer(missedMessage);
        }
    }

    /**
     * Die gemerkten Nachrichten eines Raums, die NACH lastEventId kamen, in
     * ihrer Reihenfolge. null, wenn wir lastEventId nicht kennen.
     */
    private List<DeliveredMessage> messagesAfter(UUID roomId, String lastEventId) {
        Deque<DeliveredMessage> recentMessages = recentMessagesByRoom.get(roomId);
        if (recentMessages == null) {
            return null;
        }
        List<DeliveredMessage> messagesAfter = new ArrayList<>();
        boolean lastEventIdFound = false;
        for (DeliveredMessage recentMessage : recentMessages) {
            if (lastEventIdFound) {
                messagesAfter.add(recentMessage);
            }
            String recentId = recentMessage.id().toString();
            if (recentId.equals(lastEventId)) {
                lastEventIdFound = true;
            }
        }
        if (!lastEventIdFound) {
            return null;
        }
        return messagesAfter;
    }
}
