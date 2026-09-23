package ch.benedict.m321.webgateway.dto;

/**
 * Wie voll die Queue chat.persist gerade ist und wie schnell sie sich füllt
 * und leert. Daraus macht die Oberfläche den Balken (PLANUNG.md, 4.2).
 *
 * @param queueName          immer "chat.persist"
 * @param messages           so viele Nachrichten warten noch auf den batch-writer
 * @param consumers          so viele batch-writer-Verbindungen hängen an der Queue
 * @param publishRatePerSecond so viele Nachrichten kommen pro Sekunde herein (vom chat-service)
 * @param ackRatePerSecond     so viele werden pro Sekunde bestätigt, also in die Datenbank geschrieben
 */
public record QueueStats(
        String queueName,
        long messages,
        int consumers,
        double publishRatePerSecond,
        double ackRatePerSecond) {
}
