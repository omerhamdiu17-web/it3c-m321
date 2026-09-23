package ch.benedict.m321.webgateway.dto;

/**
 * Was die Web-Oberfläche über den angemeldeten Benutzer erfährt.
 *
 * Bewusst wenig: kein Token, keine E-Mail. "admin" braucht die Oberfläche,
 * um den Balken mit der Queue-Tiefe zu zeigen (PLANUNG.md, offener Punkt 6).
 * Geprüft wird die Rolle trotzdem im Gateway, nicht im Browser.
 *
 * @param username    der Anmeldename (preferred_username aus Keycloak)
 * @param displayName Vor- und Nachname für die Anzeige
 * @param admin       true, wenn der Benutzer die Rolle "admin" hat
 */
public record CurrentUser(
        String username,
        String displayName,
        boolean admin) {
}
