package ch.benedict.m321.desktopclient.auth;

import ch.benedict.m321.desktopclient.FakeServer;
import ch.benedict.m321.desktopclient.api.ChatApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Gespräch mit Keycloak. Keycloak spielt ein FakeServer.
 */
class KeycloakLoginTest {

    private static final String TOKEN_PATH = "/auth/realms/chat/protocol/openid-connect/token";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = ChatApiClient.createObjectMapper();

    @Test
    void buildsAuthorizationUriWithPkce() {
        KeycloakLogin keycloakLogin = new KeycloakLogin("http://localhost:8080/auth/realms/chat", httpClient, objectMapper);

        URI uri = keycloakLogin.authorizationUri("http://127.0.0.1:54321/callback", "CHALLENGE", "STATE");

        String text = uri.toString();
        assertTrue(text.startsWith("http://localhost:8080/auth/realms/chat/protocol/openid-connect/auth?"));
        assertTrue(text.contains("client_id=desktop-client"));
        assertTrue(text.contains("response_type=code"));
        assertTrue(text.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2Fcallback"));
        assertTrue(text.contains("code_challenge=CHALLENGE"));
        assertTrue(text.contains("code_challenge_method=S256"));
        assertTrue(text.contains("state=STATE"));
    }

    @Test
    void exchangesCodeForTokensWithVerifier() throws Exception {
        String tokenJson = """
                {"access_token": "access-1", "refresh_token": "refresh-1", "expires_in": 300, "token_type": "Bearer"}
                """;
        try (FakeServer keycloak = new FakeServer(TOKEN_PATH, 200, tokenJson)) {
            KeycloakLogin keycloakLogin = new KeycloakLogin(keycloak.baseUrl() + "/auth/realms/chat", httpClient, objectMapper);

            Tokens tokens = keycloakLogin.exchangeCode("code-1", "http://127.0.0.1:54321/callback", "verifier-1");

            assertEquals("access-1", tokens.accessToken());
            assertEquals("refresh-1", tokens.refreshToken());
            assertTrue(tokens.expiresAt().isAfter(Instant.now().plusSeconds(290)));

            FakeServer.ReceivedRequest request = keycloak.receivedRequests().get(0);
            assertEquals("POST", request.method());
            assertTrue(request.body().contains("grant_type=authorization_code"));
            assertTrue(request.body().contains("code_verifier=verifier-1"));
            assertTrue(request.body().contains("client_id=desktop-client"));
        }
    }

    @Test
    void reportsRefusedCode() throws Exception {
        try (FakeServer keycloak = new FakeServer(TOKEN_PATH, 400, "{\"error\": \"invalid_grant\"}")) {
            KeycloakLogin keycloakLogin = new KeycloakLogin(keycloak.baseUrl() + "/auth/realms/chat", httpClient, objectMapper);

            assertThrows(java.io.IOException.class,
                    () -> keycloakLogin.exchangeCode("falsch", "http://127.0.0.1:1/callback", "verifier"));
        }
    }

    @Test
    void refreshesExpiredAccessToken() throws Exception {
        String tokenJson = """
                {"access_token": "access-neu", "refresh_token": "refresh-neu", "expires_in": 300}
                """;
        try (FakeServer keycloak = new FakeServer(TOKEN_PATH, 200, tokenJson)) {
            KeycloakLogin keycloakLogin = new KeycloakLogin(keycloak.baseUrl() + "/auth/realms/chat", httpClient, objectMapper);
            // Ein Token, das schon abgelaufen ist.
            Tokens expired = new Tokens("access-alt", "refresh-alt", Instant.now().minusSeconds(10));
            AccessTokenProvider accessTokenProvider = new AccessTokenProvider(keycloakLogin, expired);

            String accessToken = accessTokenProvider.currentAccessToken();

            assertEquals("access-neu", accessToken);
            FakeServer.ReceivedRequest request = keycloak.receivedRequests().get(0);
            assertTrue(request.body().contains("grant_type=refresh_token"));
            assertTrue(request.body().contains("refresh_token=refresh-alt"));
        }
    }
}
