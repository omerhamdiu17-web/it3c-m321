package ch.benedict.m321.desktopclient.api;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie über den WebSocket und im Verlauf kommt.
 * Eigene Kopie des Desktop-Clients; der Vertrag ist das JSON.
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Die Nachrichtenliste im Fenster zeigt, was toString() liefert:
     * Uhrzeit in der Zeitzone dieses Rechners, Absender und Text.
     */
    @Override
    public String toString() {
        LocalTime localTime = LocalTime.ofInstant(sentAt, ZoneId.systemDefault());
        String time = TIME_FORMAT.format(localTime);
        return time + "  " + senderName + ": " + content;
    }
}
