package ch.benedict.m321.batchwriter.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie aus chat.persist kommt.
 *
 * Eigene Kopie des batch-writer: der Vertrag zwischen den Diensten ist das
 * JSON, nicht die Klasse des chat-service. Die Felder heissen wie im JSON und
 * entsprechen eins zu eins den Spalten der Tabelle message.
 *
 * sentAt ist ein OffsetDateTime und kein Instant wie im chat-service: der
 * PostgreSQL-Treiber kann OffsetDateTime an timestamptz binden, Instant nicht.
 *
 * @param id         vom chat-service vergeben, Primärschlüssel der Tabelle
 * @param roomId     der Raum der Nachricht
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename des Absenders
 * @param content    der Text der Nachricht
 * @param sentAt     die Server-Zeit des chat-service
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        OffsetDateTime sentAt) {
}
