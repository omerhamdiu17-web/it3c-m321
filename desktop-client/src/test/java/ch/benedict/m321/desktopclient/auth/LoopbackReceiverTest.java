package ch.benedict.m321.desktopclient.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Rückleitung: wir spielen den Browser, den Keycloak nach dem
 * Login auf 127.0.0.1 zurückschickt.
 */
class LoopbackReceiverTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void acceptsCodeWithMatchingState() throws Exception {
        try (LoopbackReceiver receiver = new LoopbackReceiver("mein-state")) {
            String redirectUri = receiver.redirectUri();
            assertTrue(redirectUri.startsWith("http://127.0.0.1:"));

            HttpResponse<String> response = callback(redirectUri + "?code=abc123&state=mein-state");

            assertEquals(200, response.statusCode());
            String code = receiver.waitForCode(Duration.ofSeconds(5));
            assertEquals("abc123", code);
        }
    }

    @Test
    void rejectsForeignState() throws Exception {
        try (LoopbackReceiver receiver = new LoopbackReceiver("mein-state")) {
            HttpResponse<String> response = callback(receiver.redirectUri() + "?code=abc123&state=fremd");

            assertEquals(400, response.statusCode());
            assertThrows(ExecutionException.class, () -> receiver.waitForCode(Duration.ofSeconds(5)));
        }
    }

    /** Ruft die Rückleitungs-Adresse auf, wie es der Browser täte. */
    private HttpResponse<String> callback(String url) throws Exception {
        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
