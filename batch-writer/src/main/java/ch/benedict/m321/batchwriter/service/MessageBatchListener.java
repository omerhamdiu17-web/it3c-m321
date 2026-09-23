package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holt Stapel aus chat.persist, schreibt sie in die Datenbank und
 * bestätigt sie erst DANACH (PLANUNG.md, Abschnitt 3.6).
 *
 * Bestätigt wird erst nach dem COMMIT. Stürzt der batch-writer vorher ab,
 * liefert RabbitMQ den Stapel erneut. Das ist At-least-once: es geht nichts
 * verloren, aber eine Nachricht kann zweimal kommen. Das zweite Mal
 * verwirft die Datenbank (ON CONFLICT DO NOTHING).
 *
 * Zwei Arten von Fehlern werden unterschieden:
 * - Die Nachricht ist kaputt: sie kommt sofort in die Dead-Letter-Queue.
 *   Ein zweiter Versuch würde genauso scheitern.
 * - Die Datenbank ist weg: der Stapel geht zurück in die Queue und wird
 *   später erneut geliefert.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchListener {

    /** Pause, bevor ein Stapel zurückgegeben wird. Sonst kreist er ohne Halt zwischen Queue und Datenbank. */
    private static final long PAUSE_BEFORE_RETRY_MILLISECONDS = 1000;

    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    /**
     * Wird von Spring für jeden Stapel aufgerufen: bis zu 500 Nachrichten
     * oder alles, was bis zu einer Pause von 200 ms kam (siehe RabbitConfig).
     *
     * Wir bekommen die rohen Nachrichten und nicht schon fertige
     * ChatMessage-Objekte. Nur so können wir eine einzelne unlesbare
     * Nachricht aussortieren, ohne den ganzen Stapel zu verlieren.
     */
    @RabbitListener(queues = QueueNames.PERSIST_QUEUE, containerFactory = RabbitConfig.BATCH_LISTENER_FACTORY)
    public void onBatch(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = readAll(batch, channel);
        if (readableMessages.isEmpty()) {
            return;
        }
        save(readableMessages, channel);
    }

    /**
     * Liest jede Nachricht aus ihrem JSON. Was sich nicht lesen lässt,
     * wird sofort abgelehnt und landet in der Dead-Letter-Queue.
     */
    private List<ReceivedMessage> readAll(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message amqpMessage : batch) {
            long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
            ChatMessage chatMessage = read(amqpMessage);

            if (chatMessage == null) {
                log.warn("Message with delivery tag {} is not a valid chat message, sending it to {}",
                        deliveryTag, QueueNames.DEAD_LETTER_QUEUE);
                channel.basicReject(deliveryTag, false);
            } else {
                ReceivedMessage receivedMessage = new ReceivedMessage(deliveryTag, chatMessage);
                readableMessages.add(receivedMessage);
            }
        }
        return readableMessages;
    }

    /** Liest eine Nachricht. Gibt null zurück, wenn sie kein vollständiges ChatMessage-JSON ist. */
    private ChatMessage read(Message amqpMessage) {
        ChatMessage chatMessage;
        try {
            byte[] body = amqpMessage.getBody();
            chatMessage = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            return null;
        }

        if (isComplete(chatMessage)) {
            return chatMessage;
        }
        return null;
    }

    /** Alle Spalten der Tabelle message sind NOT NULL. Fehlt ein Feld, ist die Nachricht kaputt. */
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

    /**
     * Schreibt den Stapel mit einem INSERT und bestätigt ihn mit einem ACK.
     *
     * "multiple = true" beim ACK heisst: alles bis einschliesslich dieser
     * Liefernummer ist erledigt. Ein ACK für den ganzen Stapel statt 500
     * einzelne.
     */
    private void save(List<ReceivedMessage> messages, Channel channel) throws IOException {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ReceivedMessage receivedMessage : messages) {
            chatMessages.add(receivedMessage.chatMessage());
        }
        int lastIndex = messages.size() - 1;
        long lastDeliveryTag = messages.get(lastIndex).deliveryTag();

        try {
            messageRepository.insertBatch(chatMessages);
            channel.basicAck(lastDeliveryTag, true);
            log.debug("Stored batch of {} messages", messages.size());
        } catch (DataIntegrityViolationException exception) {
            log.warn("Batch of {} messages contains one that cannot be stored, retrying one by one",
                    messages.size());
            saveOneByOne(messages, channel);
        } catch (DataAccessException exception) {
            log.error("Database not reachable, returning batch of {} messages to the queue",
                    messages.size(), exception);
            pauseBeforeRetry();
            channel.basicNack(lastDeliveryTag, true, true);
        }
    }

    /**
     * Der Stapel ist an einer kaputten Nachricht gescheitert, z.B. an einem
     * Raum, den es nicht gibt. Jetzt einzeln: die guten kommen in die
     * Datenbank, nur die kaputten in die Dead-Letter-Queue.
     */
    private void saveOneByOne(List<ReceivedMessage> messages, Channel channel) throws IOException {
        for (ReceivedMessage receivedMessage : messages) {
            ChatMessage chatMessage = receivedMessage.chatMessage();
            long deliveryTag = receivedMessage.deliveryTag();
            try {
                messageRepository.insertOne(chatMessage);
                channel.basicAck(deliveryTag, false);
            } catch (DataIntegrityViolationException exception) {
                log.warn("Message {} cannot be stored, sending it to {}: {}",
                        chatMessage.id(), QueueNames.DEAD_LETTER_QUEUE, exception.getMessage());
                channel.basicReject(deliveryTag, false);
            } catch (DataAccessException exception) {
                log.error("Database not reachable, returning message {} to the queue", chatMessage.id(), exception);
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }

    /** Kurz warten, damit ein Datenbank-Ausfall nicht zu einer Endlosschleife im Log wird. */
    private void pauseBeforeRetry() {
        try {
            Thread.sleep(PAUSE_BEFORE_RETRY_MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
