package ch.benedict.m321.webgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Schritte des Authorization Code Flow mit PKCE, so wie ein Client sie
 * macht (Spezifikation 3.1, Punkt 5). Die Tests holen damit echte Tokens von
 * Keycloak: direkt beim Container (Task 3) und über das Gateway (Task 7).
 *
 * PKCE in zwei Sätzen: Der Client denkt sich ein Geheimnis aus (verifier) und
 * schickt beim Login nur dessen Hash (challenge). Beim Einlösen des Codes zeigt
 * er das Geheimnis selbst; wer nur den Code abfängt, kann ihn nicht einlösen.
 */
public final class KeycloakFlow {

    /** Liest die JSON-Antworten von Keycloak. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Der Code steht in der Umleitung als Parameter "code". */
    private static final Pattern CODE = Pattern.compile("[?&]code=([^&]+)");

    /** Diese Klasse sammelt nur Hilfsmethoden und wird nie erzeugt. */
    private KeycloakFlow() {
    }

    /** Ein neues Geheimnis für PKCE: 32 Zufallsbytes, Base64url ohne "=". */
    public static String newVerifier() {
        byte[] random = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(random);
        Base64.Encoder urlEncoder = Base64.getUrlEncoder();
        Base64.Encoder encoder = urlEncoder.withoutPadding();
        return encoder.encodeToString(random);
    }

    /** Der Hash des Geheimnisses, den Keycloak beim Login bekommt (Methode S256). */
    public static String challengeFor(String verifier) throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] verifierBytes = verifier.getBytes(StandardCharsets.US_ASCII);
        byte[] hash = sha256.digest(verifierBytes);
        Base64.Encoder urlEncoder = Base64.getUrlEncoder();
        Base64.Encoder encoder = urlEncoder.withoutPadding();
        return encoder.encodeToString(hash);
    }

    /**
     * Adresse, mit der ein Client den Login startet. keycloakUrl endet mit /auth.
     * Ist challenge null, fehlen die PKCE-Parameter: So prüfen Tests, dass
     * Keycloak eine Anfrage ohne PKCE ablehnt.
     */
    public static String authorizationUrl(String keycloakUrl, String clientId, String redirectUri, String challenge) {
        String encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        String url = keycloakUrl + "/realms/chat/protocol/openid-connect/auth"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&scope=openid%20profile%20email"
                + "&state=test-state"
                + "&redirect_uri=" + encodedRedirect;
        if (challenge == null) {
            return url;
        }
        return url + "&code_challenge=" + challenge + "&code_challenge_method=S256";
    }

    /** Liest den Code aus der Umleitung zurück zum Client. */
    public static String codeFrom(String location) {
        Matcher matcher = CODE.matcher(location);
        if (!matcher.find()) {
            throw new IllegalStateException("Kein code in der Umleitung: " + location);
        }
        return matcher.group(1);
    }

    /**
     * Löst den Code beim Token-Endpunkt ein, wie es der Client nach der
     * Rückleitung tut. clientSecret ist null beim öffentlichen desktop-client.
     */
    public static HttpResponse<String> redeemCode(TestBrowser browser, String keycloakUrl, String clientId,
                                                  String clientSecret, String redirectUri, String code,
                                                  String verifier) throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "authorization_code");
        fields.put("client_id", clientId);
        if (clientSecret != null) {
            fields.put("client_secret", clientSecret);
        }
        fields.put("redirect_uri", redirectUri);
        fields.put("code", code);
        fields.put("code_verifier", verifier);
        String tokenUrl = keycloakUrl + "/realms/chat/protocol/openid-connect/token";
        return browser.postForm(tokenUrl, fields);
    }

    /** Ein Token aus der Antwort des Token-Endpunkts, z. B. "access_token" oder "id_token". */
    public static String tokenOf(HttpResponse<String> tokenResponse, String tokenName) throws IOException {
        JsonNode tokens = JSON.readTree(tokenResponse.body());
        JsonNode token = tokens.get(tokenName);
        if (token == null) {
            throw new IllegalStateException("Kein " + tokenName + " in: " + tokenResponse.body());
        }
        return token.asText();
    }

    /**
     * Die Claims eines Tokens. Ein JWT hat drei Teile, durch Punkte getrennt;
     * der mittlere ist JSON in Base64url. Die Signatur prüft hier niemand: Der
     * Test hat das Token gerade selbst von Keycloak geholt.
     */
    public static JsonNode claimsOf(HttpResponse<String> tokenResponse, String tokenName) throws IOException {
        String compact = tokenOf(tokenResponse, tokenName);
        String[] parts = compact.split("\\.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        byte[] payload = decoder.decode(parts[1]);
        return JSON.readTree(payload);
    }
}
