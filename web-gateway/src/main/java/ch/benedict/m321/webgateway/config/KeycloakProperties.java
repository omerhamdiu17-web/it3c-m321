package ch.benedict.m321.webgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Die Werte aus dem Abschnitt "keycloak" der application.yml.
 *
 * Ein record reicht: Spring Boot füllt die Felder über den Konstruktor.
 * Die beiden Hilfsmethoden bauen die Realm-Adresse, damit die URLs nicht
 * an mehreren Stellen zusammengesetzt werden.
 *
 * @param publicBaseUrl   Adresse aus Sicht des Browsers (das Gateway selbst)
 * @param internalBaseUrl Adresse aus Sicht des Gateways im Docker-Netz
 * @param realm           Name des Realms in Keycloak
 * @param clientId        Kennung dieses Gateways im Realm
 * @param clientSecret    Geheimnis dazu, aus .env
 */
@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
        String publicBaseUrl,
        String internalBaseUrl,
        String realm,
        String clientId,
        String clientSecret) {

    /** Realm-Adresse, wie sie der Browser sieht. Das ist auch der issuer im Token. */
    public String publicRealmUrl() {
        return publicBaseUrl + "/auth/realms/" + realm;
    }

    /** Realm-Adresse für Aufrufe des Gateways selbst (Token, Schlüssel, Userinfo). */
    public String internalRealmUrl() {
        return internalBaseUrl + "/auth/realms/" + realm;
    }
}
