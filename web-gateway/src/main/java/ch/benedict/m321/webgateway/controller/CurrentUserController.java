package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.dto.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sagt der Web-Oberfläche, wer angemeldet ist.
 *
 * Die Oberfläche hat kein Token und kann das nicht selbst herausfinden;
 * sie hat nur das Session-Cookie des Gateways. Dieser Endpunkt ist die
 * einzige Stelle, an der das Gateway etwas über den Benutzer preisgibt.
 */
@RestController
public class CurrentUserController {

    /**
     * Liefert Anmeldename und Anzeigename des angemeldeten Benutzers.
     * Ohne Anmeldung kommt man hier nicht an; Spring Security leitet
     * vorher zu Keycloak weiter.
     */
    @GetMapping("/api/me")
    public CurrentUser currentUser(@AuthenticationPrincipal OidcUser user) {
        String username = user.getPreferredUsername();
        String displayName = user.getFullName();
        if (displayName == null) {
            displayName = username;
        }
        return new CurrentUser(username, displayName);
    }
}
