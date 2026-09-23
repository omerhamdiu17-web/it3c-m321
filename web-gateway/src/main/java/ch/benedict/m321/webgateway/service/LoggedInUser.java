package ch.benedict.m321.webgateway.service;

import org.springframework.security.oauth2.core.ClaimAccessor;

import java.util.List;

/**
 * Wer angemeldet ist, gelesen aus den Claims, die Keycloak ausgestellt hat.
 *
 * An genau dieser einen Stelle steht, welcher Claim was bedeutet. Vorher
 * stand das in jedem Controller einzeln, und jeder hätte es ein bisschen
 * anders machen können.
 *
 * @param subject     die sub-Kennung aus Keycloak, bleibt für ein Konto immer gleich
 * @param username    der Anmeldename (preferred_username)
 * @param displayName Vor- und Nachname, sonst der Anmeldename
 * @param admin       true, wenn Keycloak die Realm-Rolle "admin" mitgeschickt hat
 */
public record LoggedInUser(String subject, String username, String displayName, boolean admin) {

    /** Die Rolle, die zusätzlich die Queue-Tiefe sehen darf (keycloak/realm-chat.json). */
    public static final String ADMIN_ROLE = "admin";

    /**
     * Liest die Claims eines angemeldeten Benutzers.
     *
     * ClaimAccessor ist das, was alle Token-Arten von Spring gemeinsam haben:
     * man kann einen Claim beim Namen abfragen. Die Rollen stehen im Claim
     * "roles", den der Protocol Mapper "realm-roles" im Realm einträgt.
     */
    public static LoggedInUser fromClaims(ClaimAccessor claims) {
        String subject = claims.getClaimAsString("sub");
        String username = claims.getClaimAsString("preferred_username");

        String displayName = claims.getClaimAsString("name");
        if (displayName == null) {
            displayName = username;
        }

        boolean admin = false;
        List<String> roles = claims.getClaimAsStringList("roles");
        if (roles != null) {
            admin = roles.contains(ADMIN_ROLE);
        }

        return new LoggedInUser(subject, username, displayName, admin);
    }
}
