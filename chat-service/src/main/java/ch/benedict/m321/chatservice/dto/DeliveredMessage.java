package ch.benedict.m321.chatservice.dto;

import java.util.UUID;

/**
 * Eine Nachricht, wie sie vom Fanout kommt und an die Clients geht.
 *
 * Gelesen werden nur id und roomId: die id wird zur "id:"-Zeile am Stream,
 * die roomId entscheidet, wer die Nachricht bekommt. Das JSON geht
 * unverändert als "data:" weiter. So ist es garantiert dasselbe Format wie in
 * chat.persist (Spezifikation 1.3).
 *
 * @param id     die vom chat-service vergebene Nachrichten-ID
 * @param roomId der Raum der Nachricht
 * @param json   die Nachricht genau so, wie sie aus der Queue kam
 */
public record DeliveredMessage(UUID id, UUID roomId, String json) {
}
