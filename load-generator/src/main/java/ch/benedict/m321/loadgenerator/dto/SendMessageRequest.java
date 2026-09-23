package ch.benedict.m321.loadgenerator.dto;

import java.util.UUID;

/**
 * Was der load-generator an POST /messages schickt.
 *
 * Eigene Kopie; das Gegenstück liegt im chat-service. Der Vertrag ist das
 * JSON, nicht eine gemeinsame Klasse.
 *
 * @param roomId     der Raum "Lasttest"
 * @param senderId   eine feste Kennung, damit man die Last in der Datenbank erkennt
 * @param senderName der Anzeigename
 * @param content    der Text, mit laufender Nummer
 */
public record SendMessageRequest(
        UUID roomId,
        String senderId,
        String senderName,
        String content) {
}
