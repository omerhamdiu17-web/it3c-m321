package ch.benedict.m321.webgateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prüft /api/me mit einem simulierten Login.
 *
 * oidcLogin() baut einen Benutzer, wie ihn Keycloak liefern würde, ohne
 * dass ein Keycloak läuft. Wir geben nur die zwei Claims mit, die der
 * Controller liest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurrentUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsUsernameAndDisplayNameOfLoggedInUser() throws Exception {
        MockHttpServletRequestBuilder request = get("/api/me")
                .with(oidcLogin().idToken(token -> token
                        .claim("preferred_username", "alice")
                        .claim("name", "Alice Muster")));

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.displayName").value("Alice Muster"));
    }

    @Test
    void fallsBackToUsernameWhenNameIsMissing() throws Exception {
        MockHttpServletRequestBuilder request = get("/api/me")
                .with(oidcLogin().idToken(token -> token
                        .claim("preferred_username", "bob")));

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("bob"));
    }
}
