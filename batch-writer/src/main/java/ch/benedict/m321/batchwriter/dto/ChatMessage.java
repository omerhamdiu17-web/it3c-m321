package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Die fertige Nachricht, wie sie aus chat.persist kommt.
 *
 * Eigene Kopie des batch-writer. Der Vertrag zwischen den Diensten ist das
 * JSON, nicht eine gemeinsame Klasse (siehe ChatMessage im chat-service).
 * Die Felder entsprechen eins zu eins den Spalten der Tabelle message.
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
