package ch.benedict.m321.webgateway.dto;

import java.util.UUID;

/**
 * Was das Gateway per REST an den chat-service schickt (POST /messages).
 *
 * Eigene Kopie des Gateways; das Gegenstück liegt im chat-service.
 * senderId und senderName füllt das Gateway aus dem Login und NICHT aus
 * dem, was der Browser schickt. Sonst könnte sich jeder als jemand
 * anderes ausgeben.
 *
 * @param roomId     der Raum, in den die Nachricht gehört
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename des Absenders
 * @param content    der Text der Nachricht
 */
public record SendMessageRequest(
        UUID roomId,
        String senderId,
        String senderName,
        String content) {
}
