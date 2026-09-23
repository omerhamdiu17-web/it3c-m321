package ch.benedict.m321.desktopclient.api;

import java.util.UUID;

/**
 * Ein Raum, wie ihn das Gateway unter /api/rooms liefert.
 * Eigene Kopie des Desktop-Clients; der Vertrag ist das JSON.
 *
 * @param id   die feste ID des Raums
 * @param name der Anzeigename, z.B. "Lobby"
 */
public record Room(UUID id, String name) {

    /** Die Raumliste im Fenster zeigt, was toString() liefert: den Namen. */
    @Override
    public String toString() {
        return name;
    }
}
