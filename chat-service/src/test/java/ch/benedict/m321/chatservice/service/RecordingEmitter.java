package ch.benedict.m321.chatservice.service;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Eine SSE-Verbindung für Unit-Tests: Sie schickt nichts über das Netz,
 * sondern schreibt mit, was gesendet worden wäre.
 *
 * Bewusst von Hand statt mit einem Mock-Framework: so sieht man beim Lesen,
 * was passiert. Mit "broken" spielt sie einen Client, der weg ist.
 */
class RecordingEmitter extends SseEmitter {

    /** Jedes Ereignis als Text, genau so, wie es am Stream stünde. */
    final List<String> events = new ArrayList<>();

    /** Wie oft jemand versucht hat, etwas zu senden, auch erfolglos. */
    int sendAttempts;

    /** true: jedes Senden scheitert wie bei einem Client, der weg ist. */
    boolean broken;

    /**
     * Statt zu senden, setzt sie die Teile des Ereignisses zu Text zusammen,
     * zum Beispiel "id:42\ndata:{...}\n\n".
     */
    @Override
    public void send(SseEventBuilder builder) throws IOException {
        sendAttempts = sendAttempts + 1;
        if (broken) {
            throw new IOException("Client ist weg");
        }
        Set<DataWithMediaType> parts = builder.build();
        StringBuilder text = new StringBuilder();
        for (DataWithMediaType part : parts) {
            Object data = part.getData();
            text.append(data);
        }
        events.add(text.toString());
    }

    /** Die Werte aller "id:"-Zeilen, in der Reihenfolge des Empfangs. */
    List<String> receivedIds() {
        List<String> ids = new ArrayList<>();
        for (String event : events) {
            String[] lines = event.split("\n");
            for (String line : lines) {
                if (line.startsWith("id:")) {
                    String id = line.substring(3);
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
