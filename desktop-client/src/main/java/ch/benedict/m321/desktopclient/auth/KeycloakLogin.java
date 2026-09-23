package ch.benedict.m321.desktopclient.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Spricht mit Keycloak: baut die Login-Adresse und tauscht den Code gegen
 * Tokens (PLANUNG.md, Abschnitt 3.3 und offener Punkt 4).
 *
 * Keycloak erreicht der Desktop-Client über das Gateway, genau wie der
 * Browser: http://localhost:8080/auth/... — der eine offene Port reicht.
 */
public class KeycloakLogin {

    /** Der öffentliche Client im Realm (keycloak/realm-chat.json), ohne Secret. */
    public static final String CLIENT_ID = "desktop-client";

    private final String realmUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * @param realmUrl     z.B. http://localhost:8080/auth/realms/chat
     * @param httpClient   der HTTP-Client aus dem JDK
     * @param objectMapper liest die JSON-Antwort von Keycloak
     */
    public KeycloakLogin(String realmUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.realmUrl = realmUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Die Adresse, die der Client im System-Browser öffnet. Dort meldet sich
     * der Benutzer bei Keycloak an; der Client sieht das Passwort nie.
     *
     * @param redirectUri   wohin Keycloak danach zurückleitet (127.0.0.1)
     * @param codeChallenge der Fingerabdruck des PKCE-Zufallsworts
     * @param state         Zufallswert; kommt unverändert zurück und schützt vor untergeschobenen Antworten
     */
    public URI authorizationUri(String redirectUri, String codeChallenge, String state) {
        String query = "client_id=" + encode(CLIENT_ID)
                + "&response_type=code"
                + "&scope=" + encode("openid profile")
                + "&redirect_uri=" + encode(redirectUri)
                + "&code_challenge=" + encode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + encode(state);
        return URI.create(realmUrl + "/protocol/openid-connect/auth?" + query);
    }

    /**
     * Tauscht den Code aus der Rückleitung gegen Tokens. Der Verifier ist
     * der Beweis, dass WIR den Login gestartet haben (PKCE).
     */
    public Tokens exchangeCode(String code, String redirectUri, String codeVerifier)
            throws IOException, InterruptedException {
        String form = "grant_type=authorization_code"
                + "&client_id=" + encode(CLIENT_ID)
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri)
                + "&code_verifier=" + encode(codeVerifier);
        return requestTokens(form);
    }

    /** Holt mit dem Refresh-Token ein neues Access-Token, ohne neuen Login. */
    public Tokens refresh(String refreshToken) throws IOException, InterruptedException {
        String form = "grant_type=refresh_token"
                + "&client_id=" + encode(CLIENT_ID)
                + "&refresh_token=" + encode(refreshToken);
        return requestTokens(form);
    }

    /** Schickt das Formular an den Token-Endpunkt von Keycloak und liest die Antwort. */
    private Tokens requestTokens(String form) throws IOException, InterruptedException {
        URI tokenUri = URI.create(realmUrl + "/protocol/openid-connect/token");
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Keycloak refused the token request: HTTP " + response.statusCode()
                    + " " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String accessToken = json.path("access_token").asText();
        String refreshToken = json.path("refresh_token").asText();
        long expiresInSeconds = json.path("expires_in").asLong();
        Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);
        return new Tokens(accessToken, refreshToken, expiresAt);
    }

    /** Macht einen Wert URL-tauglich, z.B. wird aus einem Leerzeichen "+". */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
