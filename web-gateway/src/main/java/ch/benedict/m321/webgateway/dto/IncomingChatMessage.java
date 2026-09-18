package ch.benedict.m321.webgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Was der Browser über den WebSocket schickt.
 *
 * Bewusst nur Raum und Text. Wer der Absender ist, weiss das Gateway aus
 * dem Login; der Browser darf das nicht selbst behaupten. Schickt er
 * trotzdem weitere Felder mit (z.B. senderName), werden sie ignoriert.
 *
 * @param roomId  der Raum, in den die Nachricht gehört
 * @param content der Text der Nachricht
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingChatMessage(UUID roomId, String content) {
}
