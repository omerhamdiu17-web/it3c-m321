package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.ChatDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Der Lesepfad einmal ganz durch: Zeilen in der Datenbank, JSON über HTTP.
 * Gegen ein ECHTES Postgres mit den echten Init-Skripten (Schema und Räume).
 *
 * Die Nachrichten legt der Test selbst per SQL an. Im Betrieb macht das nur
 * der batch-writer; der chat-service liest nur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RoomControllerIntegrationTest {

    private static final UUID LOBBY_ROOM_ID = UUID.fromString(ChatDatabase.LOBBY_ROOM_ID);

    @Container
    static PostgreSQLContainer<?> postgres = ChatDatabase.createContainer();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        ChatDatabase.registerProperties(postgres, registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test beginnt mit einer leeren Tabelle message. Die Räume bleiben stehen. */
    @BeforeEach
    void emptyMessageTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    @Test
    void listsSeededRooms() throws Exception {
        mockMvc.perform(get("/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(ChatDatabase.LOBBY_ROOM_ID))
                .andExpect(jsonPath("$[0].name").value("Lobby"));
    }

    @Test
    void returnsLatestMessagesOldestFirst() throws Exception {
        // 55 Nachrichten, jede eine Sekunde nach der vorigen.
        Instant start = Instant.parse("2026-09-23T10:00:00Z");
        for (int i = 1; i <= 55; i++) {
            Instant sentAt = start.plusSeconds(i);
            insertMessage(LOBBY_ROOM_ID, "Nachricht " + i, sentAt);
        }

        // Erwartet: nur die letzten 50, also 6 bis 55, die älteste zuerst.
        String url = "/rooms/" + LOBBY_ROOM_ID + "/messages";
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[0].content").value("Nachricht 6"))
                .andExpect(jsonPath("$[49].content").value("Nachricht 55"))
                .andExpect(jsonPath("$[49].senderName").value("Alice Muster"));
    }

    @Test
    void returnsEmptyHistoryForQuietRoom() throws Exception {
        String url = "/rooms/" + LOBBY_ROOM_ID + "/messages";
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Legt eine Nachricht direkt in der Datenbank an, wie es der batch-writer tun würde. */
    private void insertMessage(UUID roomId, String content, Instant sentAt) {
        UUID messageId = UUID.randomUUID();
        Timestamp sentAtTimestamp = Timestamp.from(sentAt);
        jdbcTemplate.update("""
                INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, messageId, roomId, "sub-alice", "Alice Muster", content, sentAtTimestamp);
    }
}
