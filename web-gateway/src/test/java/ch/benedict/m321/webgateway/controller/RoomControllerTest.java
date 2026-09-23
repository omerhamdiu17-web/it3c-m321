package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.dto.Room;
import ch.benedict.m321.webgateway.service.ChatServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prüft, dass das Gateway Räume und Verlauf an die Oberfläche durchreicht.
 *
 * Der chat-service ist eine Attrappe, die Anmeldung simuliert oidcLogin().
 * RabbitMQ läuft hier nicht, deshalb bleibt der Listener des Zustellwegs aus.
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatServiceClient chatServiceClient;

    @Test
    void passesRoomsThrough() throws Exception {
        UUID lobbyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Room lobby = new Room(lobbyId, "Lobby");
        List<Room> rooms = List.of(lobby);
        when(chatServiceClient.loadRooms()).thenReturn(rooms);

        MockHttpServletRequestBuilder request = get("/api/rooms").with(oidcLogin());

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Lobby"));
    }

    @Test
    void answersServiceUnavailableWhenChatServiceIsDown() throws Exception {
        when(chatServiceClient.loadHistory(any())).thenThrow(new ResourceAccessException("chat-service not reachable"));

        UUID roomId = UUID.randomUUID();
        MockHttpServletRequestBuilder request = get("/api/rooms/" + roomId + "/messages").with(oidcLogin());

        mockMvc.perform(request)
                .andExpect(status().isServiceUnavailable());
    }
}
