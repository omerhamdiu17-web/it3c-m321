package ch.benedict.m321.webgateway.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Die fertige Nachricht, wie sie über den Zustellweg (chat.delivery) kommt.
 *
 * Das ist die EIGENE Kopie des Gateways. Der Vertrag zwischen den Diensten
 * ist das JSON, nicht eine gemeinsame Klasse: der chat-service hat seine
 * Kopie, das Gateway hat diese hier. Ein gemeinsames Modul würde alle
 * Dienste aneinanderbinden.
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
