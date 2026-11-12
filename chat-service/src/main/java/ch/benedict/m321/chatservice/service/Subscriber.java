package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.DeliveredMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Eine offene SSE-Verbindung mit eigenem Postausgang und eigenem Thread
 * (Plan, Änderung 1).
 *
 * Warum: Schreiben an einen Client kann hängen, z. B. wenn er nicht mehr
 * liest. Schriebe der Leser der Queue selbst, hinge er mit, und ALLE Clients
 * der Instanz warteten. So legt der Leser die Nachricht nur in den
 * Postausgang und ist sofort frei. Der eigene Thread leert den Postausgang
 * der Reihe nach, darum bleibt die Reihenfolge.
 *
 * Der Thread ist ein virtueller Thread (Java 21): er kostet fast nichts,
 * auch bei vielen Clients.
 */
@Slf4j
class Subscriber implements Runnable {

    /** So viele Nachrichten warten höchstens im Postausgang eines Clients. */
    static final int OUTBOX_LIMIT = 1000;

    private final SseEmitter emitter;

    /** Der Postausgang: Nachrichten, die noch zu diesem Client müssen, die älteste vorne. */
    private final BlockingQueue<DeliveredMessage> outbox = new LinkedBlockingQueue<>(OUTBOX_LIMIT);

    /** Der eigene Thread, der den Postausgang leert. */
    private final Thread senderThread;

    /** true, sobald die Verbindung zu ist. Danach nimmt der Postausgang nichts mehr an. */
    private volatile boolean closed;

    /** Bereitet den Client vor. Gesendet wird erst nach start. */
    Subscriber(SseEmitter emitter) {
        this.emitter = emitter;
        this.senderThread = Thread.ofVirtual().unstarted(this);
    }

    /** Startet den eigenen Thread. */
    void start() {
        senderThread.start();
    }

    /**
     * Legt eine Nachricht in den Postausgang und kehrt sofort zurück.
     * false heisst: Die Verbindung ist zu, oder der Postausgang ist voll,
     * weil der Client nicht mehr mitkommt.
     */
    boolean offer(DeliveredMessage message) {
        if (closed) {
            return false;
        }
        return outbox.offer(message);
    }

    /**
     * Schliesst die Verbindung. Wir halten nur den Thread an; das Beenden der
     * Verbindung macht er selbst am Ende von run. So hängt nie der Aufrufer,
     * falls auch das Beenden hängt.
     */
    void close() {
        closed = true;
        senderThread.interrupt();
    }

    /** Ist das die Verbindung, die Spring uns gegeben hat? Für das Abmelden. */
    boolean isFor(SseEmitter otherEmitter) {
        return emitter == otherEmitter;
    }

    /**
     * Die Schleife des eigenen Threads: nächste Nachricht aus dem Postausgang
     * nehmen (oder warten, bis eine kommt) und senden. Scheitert das Senden
     * oder wurde close aufgerufen, ist Schluss, und die Verbindung wird beendet.
     */
    @Override
    public void run() {
        while (!closed) {
            DeliveredMessage message;
            try {
                message = outbox.take();
            } catch (InterruptedException exception) {
                break;
            }
            boolean sent = send(message);
            if (!sent) {
                closed = true;
            }
        }
        emitter.complete();
    }

    /**
     * Schickt ein SSE-Ereignis: "id:" ist die Nachrichten-ID, "data:" das
     * JSON. Gibt false zurück, wenn die Verbindung nicht mehr offen ist.
     */
    private boolean send(DeliveredMessage message) {
        String eventId = message.id().toString();
        String json = message.json();
        SseEventBuilder event = SseEmitter.event()
                .id(eventId)
                .data(json);
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException exception) {
            log.debug("Could not send message {}, closing subscriber: {}", eventId, exception.getMessage());
            return false;
        }
    }
}
