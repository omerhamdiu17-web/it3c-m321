package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.Room;
import ch.benedict.m321.chatservice.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Der Lesepfad der internen REST-Schnittstelle: Räume und Verlauf.
 *
 * Wie POST /messages nur aus dem Docker-Netz erreichbar. Das Gateway ruft
 * diese Endpunkte auf, wenn die Oberfläche einen Raum öffnet.
 */
@RestController
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    /** Alle Räume. */
    @GetMapping("/rooms")
    public List<Room> listRooms() {
        return roomService.listRooms();
    }

    /** Die letzten 50 Nachrichten eines Raums, die älteste zuerst. */
    @GetMapping("/rooms/{roomId}/messages")
    public List<ChatMessage> loadHistory(@PathVariable UUID roomId) {
        return roomService.loadHistory(roomId);
    }
}
