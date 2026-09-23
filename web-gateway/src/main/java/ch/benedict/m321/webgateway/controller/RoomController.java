package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.dto.ChatMessage;
import ch.benedict.m321.webgateway.dto.Room;
import ch.benedict.m321.webgateway.service.ChatServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

/**
 * Räume und Verlauf für die Web-Oberfläche.
 *
 * Das Gateway entscheidet hier nichts, es reicht nur durch: der
 * chat-service ist von aussen nicht erreichbar, also fragt die Oberfläche
 * das Gateway, und das Gateway fragt den chat-service. Die Anmeldung
 * prüft Spring Security vorher (SecurityConfig).
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class RoomController {

    private final ChatServiceClient chatServiceClient;

    /** Alle Räume für die Raumliste. */
    @GetMapping("/api/rooms")
    public List<Room> listRooms() {
        return chatServiceClient.loadRooms();
    }

    /** Die letzten 50 Nachrichten eines Raums, die älteste zuerst. */
    @GetMapping("/api/rooms/{roomId}/messages")
    public List<ChatMessage> loadHistory(@PathVariable UUID roomId) {
        return chatServiceClient.loadHistory(roomId);
    }

    /**
     * Der chat-service ist weg oder meldet einen Fehler (z.B. Datenbank weg).
     * Die Oberfläche bekommt ein ehrliches 503 statt einer 500.
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<String> handleChatServiceNotAvailable(RestClientException exception) {
        log.warn("chat-service did not answer the read request", exception);

        String body = "Verlauf nicht verfügbar.";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
