package ch.benedict.m321.chatservice.dto;

import java.util.UUID;

/**
 * Ein Chatraum, so wie ihn die Oberfläche in der Raumliste braucht.
 *
 * @param id   die feste ID aus postgres/init/03-rooms.sql
 * @param name der Anzeigename, z.B. "Lobby"
 */
public record Room(UUID id, String name) {
}
