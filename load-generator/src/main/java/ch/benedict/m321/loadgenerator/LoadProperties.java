package ch.benedict.m321.loadgenerator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Die Werte aus dem Abschnitt "load" der application.yml.
 *
 * @param chatServiceBaseUrl wohin die Anfragen gehen
 * @param messagesPerMinute  wie viele Nachrichten pro Minute das Ziel sind
 * @param durationSeconds    wie lange der Versuch läuft
 * @param roomId             in welchen Raum die Nachrichten gehen
 * @param maxInFlight        wie viele Anfragen höchstens gleichzeitig unterwegs sind
 */
@ConfigurationProperties(prefix = "load")
public record LoadProperties(
        String chatServiceBaseUrl,
        int messagesPerMinute,
        int durationSeconds,
        UUID roomId,
        int maxInFlight) {

    /**
     * Das Ziel pro Sekunde. 100'000 pro Minute sind 1'666,7 pro Sekunde;
     * die Ganzzahl-Division schneidet ab, das Ziel ist also 1'666.
     */
    public int messagesPerSecond() {
        return messagesPerMinute / 60;
    }
}
