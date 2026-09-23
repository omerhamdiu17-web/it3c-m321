package ch.benedict.m321.webgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Prüft die Bearer-Tokens des Desktop-Clients (PLANUNG.md, Abschnitt 3.3).
 *
 * Der Browser hat ein Session-Cookie, der Desktop-Client nicht. Er schickt
 * bei jeder Anfrage "Authorization: Bearer <JWT>". Das Gateway prüft das
 * Token lokal im Speicher: Signatur mit dem öffentlichen Schlüssel von
 * Keycloak, Ablaufzeit und Aussteller. Pro Anfrage kein Netzwerkaufruf.
 *
 * Wie bei KeycloakClientConfig gibt es zwei Adressen: die Schlüssel holt
 * das Gateway intern, der Aussteller im Token ist aber die öffentliche
 * Adresse — über die hat sich der Desktop-Client angemeldet.
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtDecoder jwtDecoder(KeycloakProperties keycloakProperties) {
        String jwkSetUri = keycloakProperties.internalRealmUrl() + "/protocol/openid-connect/certs";
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        // Prüft Ablaufzeit (exp) und Aussteller (iss). Die Schlüssel holt
        // der Decoder erst beim ersten Token, nicht schon beim Start.
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(keycloakProperties.publicRealmUrl());
        jwtDecoder.setJwtValidator(validator);
        return jwtDecoder;
    }
}
