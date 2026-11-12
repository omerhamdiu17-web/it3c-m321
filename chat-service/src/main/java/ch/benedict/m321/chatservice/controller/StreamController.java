package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.service.RoomSubscribers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.UUID;

/**
 * Der Anzeigeweg nach aussen: Ein Client öffnet GET /stream?roomId=… und
 * bekommt ab dann jede Nachricht seines Raums als Server-Sent Event
 * (Spezifikation 1.3).
 *
 * Wie /messages ist das nur im Docker-Netz erreichbar. Später hängt sich das
 * web-gateway hier an; heute tun es die Tests und curl.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class StreamController {

    /** 0 heisst: kein Zeitlimit. Sonst schlösse Tomcat die Verbindung nach rund 30 s. */
    private static final long NO_TIMEOUT = 0L;

    /** Text der Kommentarzeile, die sofort beim Verbinden rausgeht. */
    private static final String CONNECTED_COMMENT = "verbunden";

    private final RoomSubscribers roomSubscribers;

    /**
     * Öffnet die Verbindung und meldet sie für den Raum an.
     *
     * Zuerst geht eine Kommentarzeile ":verbunden" raus. Ohne sie schickte
     * Spring die Header erst mit der ersten Nachricht, und der Client wüsste
     * so lange nicht, ob er verbunden ist (mit curl nachgeprüft). Eine Zeile,
     * die mit ":" beginnt, ist laut SSE-Standard ein Kommentar: kein Ereignis,
     * keine id, jeder Client überliest sie.
     *
     * Die drei Rückrufe melden die Verbindung wieder ab, egal wie sie endet:
     * normal, durch Zeitlimit oder durch einen Fehler (Client weg).
     * Fehlt roomId oder ist es keine UUID, antwortet Spring selbst mit 400.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam UUID roomId) throws IOException {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        emitter.onCompletion(() -> roomSubscribers.unsubscribe(roomId, emitter));
        emitter.onTimeout(() -> roomSubscribers.unsubscribe(roomId, emitter));
        emitter.onError(error -> roomSubscribers.unsubscribe(roomId, emitter));

        SseEventBuilder connectedComment = SseEmitter.event().comment(CONNECTED_COMMENT);
        emitter.send(connectedComment);
        roomSubscribers.subscribe(roomId, emitter);
        log.info("Client subscribed to room {}", roomId);
        return emitter;
    }
}
