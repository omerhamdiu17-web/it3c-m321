package ch.benedict.m321.webgateway.dto;

/**
 * Was die Web-Oberfläche über den angemeldeten Benutzer erfährt.
 *
 * Bewusst wenig: kein Token, keine E-Mail. Rollen kommen dazu, sobald eine
 * Funktion sie braucht (PLANUNG.md, offener Punkt 6).
 *
 * @param username    der Anmeldename (preferred_username aus Keycloak)
 * @param displayName Vor- und Nachname für die Anzeige
 */
public record CurrentUser(
        String username,
        String displayName) {
}
