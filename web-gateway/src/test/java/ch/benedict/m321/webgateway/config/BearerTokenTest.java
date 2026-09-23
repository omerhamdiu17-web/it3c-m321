package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.dto.Room;
import ch.benedict.m321.webgateway.service.ChatServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prüft den zweiten Weg hinein: der Desktop-Client schickt ein
 * Bearer-Token statt eines Session-Cookies.
 *
 * jwt() simuliert ein bereits geprüftes Token, ohne dass ein Keycloak
 * läuft. Dass die Prüfung selbst funktioniert (Signatur, Aussteller),
 * zeigt der Rauchtest gegen das echte System (scripts/smoke-test.py).
 *
 * RabbitMQ läuft hier nicht, deshalb bleibt der Listener des Zustellwegs aus.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class BearerTokenTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatServiceClient chatServiceClient;

    @Test
    void acceptsBearerTokenForRooms() throws Exception {
        UUID lobbyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Room lobby = new Room(lobbyId, "Lobby");
        List<Room> rooms = List.of(lobby);
        when(chatServiceClient.loadRooms()).thenReturn(rooms);

        MockHttpServletRequestBuilder request = get("/api/rooms").with(jwt());

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Lobby"));
    }

    @Test
    void readsUserFromBearerToken() throws Exception {
        MockHttpServletRequestBuilder request = get("/api/me")
                .with(jwt().jwt(token -> token
                        .claim("preferred_username", "bob")
                        .claim("name", "Bob Beispiel")
                        .claim("roles", List.of("user"))));

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.displayName").value("Bob Beispiel"))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        // Ein Token, das gar keines ist: 401, KEINE Weiterleitung zu Keycloak.
        // Ein Programm kann mit einer Login-Seite nichts anfangen.
        MockHttpServletRequestBuilder request = get("/api/rooms")
                .header("Authorization", "Bearer kein-gueltiges-token");

        mockMvc.perform(request)
                .andExpect(status().isUnauthorized());
    }
}
