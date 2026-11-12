package ch.benedict.m321.chatservice.service;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Eine SSE-Verbindung für Unit-Tests: Sie schickt nichts über das Netz,
 * sondern schreibt mit, was gesendet worden wäre.
 *
 * Bewusst von Hand statt mit einem Mock-Framework: so sieht man beim Lesen,
 * was passiert. Mit "broken" spielt sie einen Client, der weg ist.
 */
class RecordingEmitter extends SseEmitter {

    /**
     * Jedes Ereignis als Text, genau so, wie es am Stream stünde. Eine Liste,
     * die mehrere Threads gleichzeitig benutzen dürfen: im Integrationstest
     * schreibt der Thread des Listeners, und der Test liest.
     */
    final List<String> events = new CopyOnWriteArrayList<>();

    /** Wie oft jemand versucht hat, etwas zu senden, auch erfolglos. */
    volatile int sendAttempts;

    /** true: jedes Senden scheitert wie bei einem Client, der weg ist. */
    volatile boolean broken;

    /**
     * So viele Millisekunden hängt das ERSTE Senden, wie bei einem Client, der
     * nicht mehr liest. 0 heisst: nie hängen.
     */
    volatile long hangOnFirstSendMilliseconds;

    /** true, sobald jemand die Verbindung beendet hat (complete). */
    volatile boolean completed;

    /**
     * Statt zu senden, setzt sie die Teile des Ereignisses zu Text zusammen,
     * zum Beispiel "id:42\ndata:{...}\n\n".
     */
    @Override
    public void send(SseEventBuilder builder) throws IOException {
        sendAttempts = sendAttempts + 1;
        if (sendAttempts == 1 && hangOnFirstSendMilliseconds > 0) {
            hang();
        }
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

    /** Merkt sich nur, dass die Verbindung beendet wurde. Es gibt nichts zu schliessen. */
    @Override
    public void complete() {
        completed = true;
    }

    /** Hängt so lange wie verlangt, wie ein Schreiben in eine volle Leitung. */
    private void hang() throws IOException {
        try {
            Thread.sleep(hangOnFirstSendMilliseconds);
        } catch (InterruptedException exception) {
            throw new IOException("Hängen unterbrochen", exception);
        }
    }

    /** Wartet höchstens 5 Sekunden, bis mindestens so viele Sendeversuche gezählt sind. */
    void waitForSendAttempts(int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (sendAttempts < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Wartet höchstens 5 Sekunden, bis die Verbindung beendet wurde. */
    void waitForCompleted() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!completed && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /**
     * Wartet höchstens 5 Sekunden, bis mindestens so viele Ereignisse da sind.
     * Nötig, weil die Nachricht nicht sofort ankommt: im Integrationstest
     * über RabbitMQ, und jeder Client hat einen eigenen Sende-Thread.
     */
    void waitForEvents(int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (events.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
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
