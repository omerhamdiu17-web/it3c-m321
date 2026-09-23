package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.dto.CurrentUser;
import ch.benedict.m321.webgateway.service.LoggedInUser;
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
     * Liefert Anmeldename, Anzeigename und ob der Benutzer admin ist.
     * Ohne Anmeldung kommt man hier nicht an; Spring Security leitet
     * vorher zu Keycloak weiter.
     */
    @GetMapping("/api/me")
    public CurrentUser currentUser(@AuthenticationPrincipal OidcUser user) {
        LoggedInUser loggedInUser = LoggedInUser.fromClaims(user);
        return new CurrentUser(loggedInUser.username(), loggedInUser.displayName(), loggedInUser.admin());
    }
}
