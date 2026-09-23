package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.dto.QueueStats;
import ch.benedict.m321.webgateway.service.QueueStatsClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prüft, dass nur die Rolle admin die Queue-Tiefe sieht.
 *
 * RabbitMQ läuft hier nicht: der QueueStatsClient ist eine Attrappe, und
 * der Listener des Zustellwegs bleibt aus.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class QueueStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueueStatsClient queueStatsClient;

    @Test
    void showsQueueStatsToAdmin() throws Exception {
        QueueStats queueStats = new QueueStats("chat.persist", 4200, 2, 1666.0, 1500.0);
        when(queueStatsClient.loadPersistQueueStats()).thenReturn(queueStats);

        MockHttpServletRequestBuilder request = get("/api/admin/queue")
                .with(oidcLogin().idToken(token -> token
                        .claim("preferred_username", "admin")
                        .claim("roles", List.of("user", "admin"))));

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").value(4200))
                .andExpect(jsonPath("$.consumers").value(2));
    }

    @Test
    void forbidsQueueStatsForNormalUser() throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/queue")
                .with(oidcLogin().idToken(token -> token
                        .claim("preferred_username", "alice")
                        .claim("roles", List.of("user"))));

        mockMvc.perform(request)
                .andExpect(status().isForbidden());
        verify(queueStatsClient, never()).loadPersistQueueStats();
    }
}
