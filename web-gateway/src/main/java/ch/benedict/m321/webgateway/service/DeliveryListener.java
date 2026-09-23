package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.dto.ChatMessage;
import ch.benedict.m321.webgateway.dto.ServerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/**
 * Der Zustellweg: liest aus RabbitMQ und bedient die verbundenen Browser.
 *
 * Auf dem Rückweg ist das Gateway am Broker, auf dem Sendeweg nicht.
 * Es ist Consumer, aber kein Producer (PLANUNG.md, Abschnitt 3.4).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeliveryListener {

    private final ChatSessionRegistry chatSessionRegistry;

    /**
     * Wird von Spring für jede Nachricht aus der eigenen Queue aufgerufen.
     *
     * Der Name der Queue ist zufällig (siehe RabbitConfig), deshalb steht
     * hier kein fester Text, sondern ein Verweis auf die Bean deliveryQueue.
     * Das JSON wandelt Spring anhand des Parametertyps in unsere eigene
     * Kopie von ChatMessage um.
     *
     * Zugestellt wird nur an die Browser, die den Raum der Nachricht offen
     * haben. Wer in der Lobby sitzt, merkt vom Lasttest nichts.
     */
    @RabbitListener(queues = "#{deliveryQueue.name}")
    public void deliver(ChatMessage chatMessage) {
        log.debug("Delivering message {} for room {}", chatMessage.id(), chatMessage.roomId());

        ServerEvent messageEvent = ServerEvent.message(chatMessage);
        chatSessionRegistry.sendToRoom(chatMessage.roomId(), messageEvent);
    }
}
