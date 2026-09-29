package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Macht aus dem Body einer Nachricht eine ChatMessage.
 *
 * Gelesen wird NUR der Body. Header wie __TypeId__ oder content_type spielen
 * keine Rolle: __TypeId__ nennt eine Klasse des chat-service, die es hier
 * nicht gibt, und von Hand eingelegte Nachrichten haben ihn gar nicht
 * (Spezifikation 2.3).
 */
@Component
@RequiredArgsConstructor
public class ChatMessageReader {

    /** Der ObjectMapper von Spring Boot: kennt OffsetDateTime und ignoriert unbekannte Felder. */
    private final ObjectMapper objectMapper;

    /**
     * Liest eine Nachricht. Gibt null zurück, wenn der Body kein passendes
     * JSON ist oder ein Feld fehlt: eine solche Nachricht kann der
     * batch-writer nie speichern, egal wie oft er es versucht.
     */
    public ChatMessage read(byte[] body) {
        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            return null;
        }

        if (isComplete(chatMessage)) {
            return chatMessage;
        }
        return null;
    }

    /** Alle Spalten der Tabelle message sind NOT NULL, also muss jedes Feld da sein. */
    private boolean isComplete(ChatMessage chatMessage) {
        if (chatMessage == null) {
            return false;
        }
        return chatMessage.id() != null
                && chatMessage.roomId() != null
                && chatMessage.senderId() != null
                && chatMessage.senderName() != null
                && chatMessage.content() != null
                && chatMessage.sentAt() != null;
    }
}
