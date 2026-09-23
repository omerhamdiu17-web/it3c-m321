package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.Room;
import ch.benedict.m321.chatservice.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Die Regeln für den Lesepfad.
 *
 * Hier steht, WIE VIELE Nachrichten jemand beim Öffnen eines Raums sieht
 * und in welcher Reihenfolge. Das Repository weiss nur, wie man fragt.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoomService {

    /** "Die letzten 50 Nachrichten eines Raums" (PLANUNG.md, Abschnitt 3.7). */
    public static final int HISTORY_LIMIT = 50;

    private final RoomRepository roomRepository;

    /** Alle Räume für die Raumliste. */
    public List<Room> listRooms() {
        return roomRepository.findAllRooms();
    }

    /**
     * Die letzten 50 Nachrichten eines Raums, die ÄLTESTE zuerst.
     *
     * Die Datenbank liefert die neueste zuerst, weil nur so "die letzten 50"
     * zu einer einfachen Abfrage wird. Ein Chat liest sich aber von oben
     * nach unten, also drehen wir die Liste um.
     */
    public List<ChatMessage> loadHistory(UUID roomId) {
        List<ChatMessage> newestFirst = roomRepository.findLatestMessages(roomId, HISTORY_LIMIT);

        List<ChatMessage> oldestFirst = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            oldestFirst.add(newestFirst.get(i));
        }

        log.debug("Loaded {} messages of history for room {}", oldestFirst.size(), roomId);
        return oldestFirst;
    }
}
