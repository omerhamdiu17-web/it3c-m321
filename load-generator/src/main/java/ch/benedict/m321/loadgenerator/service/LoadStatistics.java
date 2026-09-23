package ch.benedict.m321.loadgenerator.service;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zählt mit, was während des Lastversuchs passiert.
 *
 * Hunderte virtuelle Threads zählen gleichzeitig. Deshalb Atomic-Zähler:
 * "zahl = zahl + 1" wäre hier falsch, weil zwei Threads denselben alten
 * Wert lesen und einer der beiden Schritte verloren ginge.
 */
public class LoadStatistics {

    /** Steht im Header keine Instanz, zählen wir unter diesem Namen. */
    public static final String UNKNOWN_INSTANCE = "unbekannt";

    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    /** Wie viele Antworten von welcher chat-service-Instanz kamen. */
    private final Map<String, AtomicLong> acceptedByInstance = new ConcurrentHashMap<>();

    /** Der chat-service hat die Nachricht angenommen (202). */
    public void recordAccepted(String instanceName) {
        acceptedCount.incrementAndGet();

        String key = instanceName;
        if (key == null) {
            key = UNKNOWN_INSTANCE;
        }
        // computeIfAbsent legt den Zähler beim ersten Mal an, und zwar nur
        // einmal, auch wenn zwei Threads gleichzeitig fragen.
        AtomicLong instanceCounter = acceptedByInstance.computeIfAbsent(key, name -> new AtomicLong());
        instanceCounter.incrementAndGet();
    }

    /** Die Anfrage ist gescheitert: Fehlercode oder keine Verbindung. */
    public void recordFailed() {
        failedCount.incrementAndGet();
    }

    /** So viele Nachrichten hat der chat-service bisher angenommen. */
    public long acceptedCount() {
        return acceptedCount.get();
    }

    /** So viele Anfragen sind bisher gescheitert. */
    public long failedCount() {
        return failedCount.get();
    }

    /** Eine Momentaufnahme: Instanz → angenommene Nachrichten, alphabetisch sortiert. */
    public Map<String, Long> acceptedByInstance() {
        Map<String, Long> snapshot = new TreeMap<>();
        for (Map.Entry<String, AtomicLong> entry : acceptedByInstance.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return snapshot;
    }
}
