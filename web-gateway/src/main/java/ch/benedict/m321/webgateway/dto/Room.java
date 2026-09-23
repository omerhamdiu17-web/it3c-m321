package ch.benedict.m321.webgateway.dto;

import java.util.UUID;

/**
 * Ein Chatraum, wie ihn der chat-service unter GET /rooms liefert.
 *
 * Eigene Kopie des Gateways; das Gegenstück liegt im chat-service.
 *
 * @param id   die feste ID des Raums
 * @param name der Anzeigename, z.B. "Lobby"
 */
public record Room(UUID id, String name) {
}
