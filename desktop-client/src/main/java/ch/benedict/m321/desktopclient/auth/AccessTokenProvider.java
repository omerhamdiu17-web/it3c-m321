package ch.benedict.m321.desktopclient.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Liefert immer ein gültiges Access-Token.
 *
 * Ein Access-Token von Keycloak gilt nur 5 Minuten. Läuft es bald ab,
 * holt diese Klasse mit dem Refresh-Token ein neues — der Benutzer merkt
 * davon nichts und muss sich nicht neu anmelden.
 */
public class AccessTokenProvider {

    /** So lange vor dem Ablauf wird schon erneuert, damit das Token unterwegs nicht abläuft. */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final KeycloakLogin keycloakLogin;
    private Tokens tokens;

    public AccessTokenProvider(KeycloakLogin keycloakLogin, Tokens tokens) {
        this.keycloakLogin = keycloakLogin;
        this.tokens = tokens;
    }

    /**
     * Das aktuelle Access-Token, bei Bedarf vorher erneuert.
     *
     * "synchronized": fragen zwei Threads gleichzeitig, erneuert nur einer,
     * und der zweite bekommt danach das neue Token.
     */
    public synchronized String currentAccessToken() throws IOException, InterruptedException {
        Instant refreshFrom = tokens.expiresAt().minus(REFRESH_MARGIN);
        if (Instant.now().isAfter(refreshFrom)) {
            tokens = keycloakLogin.refresh(tokens.refreshToken());
        }
        return tokens.accessToken();
    }
}
