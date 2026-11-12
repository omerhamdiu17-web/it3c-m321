package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.DeliveredMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Liest jede Nachricht aus der eigenen Queue dieser Instanz und gibt sie an
 * die offenen Verbindungen ihres Raums weiter.
 *
 * Welche Queue, wie viele auf Vorrat und wie viele Leser: das steht in
 * RabbitConfig beim Container, der diese Klasse aufruft. Hier steht nur, was
 * mit EINER Nachricht passiert.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeliveryListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final RoomSubscribers roomSubscribers;

    /**
     * Wird vom Container für jede Nachricht aufgerufen, eine nach der anderen.
     *
     * Wir lesen den Body selbst mit dem ObjectMapper von Spring Boot und nicht
     * über den Header __TypeId__. So funktioniert auch eine Nachricht, die
     * jemand von Hand in den Fanout gelegt hat.
     */
    @Override
    public void onMessage(Message message) {
        byte[] body = message.getBody();
        String json = new String(body, StandardCharsets.UTF_8);
        DeliveredMessage deliveredMessage = read(json);
        if (deliveredMessage == null) {
            return;
        }
        roomSubscribers.deliver(deliveredMessage);
    }

    /**
     * Holt ID und Raum aus dem JSON. Gibt null zurück, wenn das nicht geht.
     *
     * Kein Fehler werfen ist Absicht: Ein Fehler hiesse für Spring "nochmal
     * versuchen", und dieselbe kaputte Nachricht käme immer wieder. Darum
     * eine Warnung ins Log und weg damit.
     */
    private DeliveredMessage read(String json) {
        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(json, ChatMessage.class);
        } catch (JsonProcessingException exception) {
            log.warn("Dropped unreadable message from delivery queue: {}", exception.getOriginalMessage());
            return null;
        }
        UUID messageId = chatMessage.id();
        UUID roomId = chatMessage.roomId();
        if (messageId == null || roomId == null) {
            log.warn("Dropped message without id or roomId from delivery queue");
            return null;
        }
        return new DeliveredMessage(messageId, roomId, json);
    }
}
