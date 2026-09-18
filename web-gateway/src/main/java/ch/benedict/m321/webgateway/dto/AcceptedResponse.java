package ch.benedict.m321.webgateway.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Die Antwort des chat-service auf POST /messages (202 Accepted).
 *
 * Eigene Kopie des Gateways. "Angenommen" heisst: die Nachricht liegt in
 * der Queue. Gespeichert oder zugestellt ist sie damit noch nicht.
 *
 * @param id     die vom chat-service vergebene Nachrichten-ID
 * @param sentAt der vom chat-service gesetzte Zeitpunkt
 */
public record AcceptedResponse(UUID id, Instant sentAt) {
}
