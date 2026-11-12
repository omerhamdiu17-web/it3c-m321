package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.DeliveredMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
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
 * Alle Methoden sind synchronized, es gibt also EINE Sperre für alles. Dadurch
 * läuft nie ein Senden gleichzeitig mit einem An- oder Abmelden:
 * - Jede Verbindung bekommt die Nachrichten in der Reihenfolge, in der sie
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
    private final Map<UUID, List<SseEmitter>> subscribersByRoom = new HashMap<>();

    /** Raum → die letzten Nachrichten, die älteste vorne, die neuste hinten. */
    private final Map<UUID, Deque<DeliveredMessage>> recentMessagesByRoom = new HashMap<>();

    /**
     * Meldet eine Verbindung für einen Raum an. Ab jetzt bekommt sie dessen
     * Nachrichten.
     *
     * Kommt der Client mit einer lastEventId zurück, bekommt er VOR dem
     * Anmelden alles, was danach kam. Kennen wir die ID nicht (zu alt oder die
     * Instanz ist neu), holen wir nichts nach, warnen im Log und melden ihn nur
     * für live an (Spezifikation 2.6).
     */
    public synchronized void subscribe(UUID roomId, String lastEventId, SseEmitter emitter) {
        if (lastEventId != null) {
            boolean caughtUp = sendMissedMessages(roomId, lastEventId, emitter);
            if (!caughtUp) {
                return;
            }
        }
        List<SseEmitter> subscribers = subscribersByRoom.get(roomId);
        if (subscribers == null) {
            subscribers = new ArrayList<>();
            subscribersByRoom.put(roomId, subscribers);
        }
        subscribers.add(emitter);
        log.debug("Subscriber added to room {}, now {}", roomId, subscribers.size());
    }

    /**
     * Meldet eine Verbindung ab, z. B. weil der Client weg ist. Ein leerer
     * Raum wird ganz entfernt, damit die Liste nicht endlos wächst.
     */
    public synchronized void unsubscribe(UUID roomId, SseEmitter emitter) {
        List<SseEmitter> subscribers = subscribersByRoom.get(roomId);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(emitter);
        if (subscribers.isEmpty()) {
            subscribersByRoom.remove(roomId);
        }
        log.debug("Subscriber removed from room {}", roomId);
    }

    /**
     * Merkt sich die Nachricht und schickt sie an alle Verbindungen ihres
     * Raums. Wohin das Senden scheitert, der Client ist weg: diese Verbindung
     * fliegt raus.
     *
     * Wir senden an eine Kopie der Liste. So darf sich die echte Liste
     * ändern, während wir senden.
     */
    public synchronized void deliver(DeliveredMessage message) {
        UUID roomId = message.roomId();
        remember(message);
        List<SseEmitter> subscribers = subscribersByRoom.get(roomId);
        if (subscribers == null) {
            return;
        }
        List<SseEmitter> receivers = new ArrayList<>(subscribers);
        for (SseEmitter emitter : receivers) {
            boolean sent = send(emitter, message);
            if (!sent) {
                unsubscribe(roomId, emitter);
            }
        }
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
     * Schickt alles, was nach lastEventId kam. Gibt false zurück, wenn die
     * Verbindung dabei abbricht; dann wird sie gar nicht erst angemeldet.
     */
    private boolean sendMissedMessages(UUID roomId, String lastEventId, SseEmitter emitter) {
        List<DeliveredMessage> missedMessages = messagesAfter(roomId, lastEventId);
        if (missedMessages == null) {
            log.warn("Unknown Last-Event-ID {} for room {}, nothing to catch up", lastEventId, roomId);
            return true;
        }
        log.info("Catching up {} messages after {} in room {}", missedMessages.size(), lastEventId, roomId);
        for (DeliveredMessage missedMessage : missedMessages) {
            boolean sent = send(emitter, missedMessage);
            if (!sent) {
                return false;
            }
        }
        return true;
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

    /**
     * Schickt ein SSE-Ereignis: "id:" ist die Nachrichten-ID, "data:" das
     * JSON. Gibt false zurück, wenn die Verbindung nicht mehr offen ist.
     */
    private boolean send(SseEmitter emitter, DeliveredMessage message) {
        String eventId = message.id().toString();
        String json = message.json();
        SseEventBuilder event = SseEmitter.event()
                .id(eventId)
                .data(json);
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException exception) {
            log.debug("Could not send message {}, removing subscriber: {}", eventId, exception.getMessage());
            return false;
        }
    }
}
