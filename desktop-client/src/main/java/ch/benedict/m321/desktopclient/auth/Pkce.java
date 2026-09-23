package ch.benedict.m321.desktopclient.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE: "Proof Key for Code Exchange" (RFC 7636).
 *
 * Der Desktop-Client hat kein Secret — ein Programm auf dem Rechner der
 * Benutzer kann nichts geheim halten. Damit trotzdem nur ER den Code von
 * Keycloak einlösen kann, denkt er sich vor dem Login ein Zufallswort aus
 * (Verifier). Keycloak bekommt beim Login nur dessen Fingerabdruck
 * (Challenge = SHA-256). Beim Einlösen des Codes zeigt der Client das
 * Zufallswort selbst vor. Wer den Code unterwegs abfängt, kennt es nicht.
 */
public final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Base64 in der URL-sicheren Form ohne "=" am Ende, wie RFC 7636 es verlangt. */
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Diese Klasse sammelt nur Hilfsmethoden und wird nie erzeugt. */
    private Pkce() {
    }

    /** Ein neues Zufallswort: 32 zufällige Bytes, also 43 Zeichen. */
    public static String generateVerifier() {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        return URL_ENCODER.encodeToString(randomBytes);
    }

    /** Der Fingerabdruck des Zufallsworts: SHA-256, dann Base64 (Methode "S256"). */
    public static String challengeFor(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] verifierBytes = verifier.getBytes(StandardCharsets.US_ASCII);
            byte[] hash = sha256.digest(verifierBytes);
            return URL_ENCODER.encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            // Jedes JDK muss SHA-256 können; das hier passiert nie.
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
