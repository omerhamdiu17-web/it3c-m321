package ch.benedict.m321.desktopclient.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Prüft PKCE gegen das Beispiel aus dem Standard selbst (RFC 7636,
 * Anhang B). Stimmt das, akzeptiert auch Keycloak unsere Challenge.
 */
class PkceTest {

    @Test
    void computesChallengeLikeRfc7636AppendixB() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

        String challenge = Pkce.challengeFor(verifier);

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge);
    }

    @Test
    void generatesDifferentVerifiersOfValidLength() {
        String first = Pkce.generateVerifier();
        String second = Pkce.generateVerifier();

        // RFC 7636: 43 bis 128 Zeichen.
        assertEquals(43, first.length());
        assertNotEquals(first, second);
    }
}
