package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.DeliveredMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merkt sich, welche offenen SSE-Verbindungen dieser Instanz zu welchem Raum
 * gehören, und gibt jede Nachricht nur an die Verbindungen ihres Raums weiter
 * (Spezifikation 2.1).
 *
 * Alle Methoden sind synchronized, es gibt also EINE Sperre für alles. Dadurch
 * läuft nie ein Senden gleichzeitig mit einem An- oder Abmelden, und jede
 * Verbindung bekommt die Nachrichten in der Reihenfolge, in der sie aus der
 * Queue kamen (Spezifikation 2.2).
 */
@Component
@Slf4j
public class RoomSubscribers {

    /** Raum → offene Verbindungen in diesem Raum. */
    private final Map<UUID, List<SseEmitter>> subscribersByRoom = new HashMap<>();

    /** Meldet eine Verbindung für einen Raum an. Ab jetzt bekommt sie dessen Nachrichten. */
    public synchronized void subscribe(UUID roomId, SseEmitter emitter) {
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
     * Schickt eine Nachricht an alle Verbindungen ihres Raums. Wohin das
     * Senden scheitert, der Client ist weg: diese Verbindung fliegt raus.
     *
     * Wir senden an eine Kopie der Liste. So darf sich die echte Liste
     * ändern, während wir senden.
     */
    public synchronized void deliver(DeliveredMessage message) {
        UUID roomId = message.roomId();
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
