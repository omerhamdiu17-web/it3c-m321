package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Holt Stapel aus chat.persist, schreibt sie in die Datenbank und bestätigt
 * sie erst DANACH (PLANUNG.md 3.6, Spezifikation 3.1).
 *
 * Stürzt der batch-writer vor dem ACK ab, liefert RabbitMQ den Stapel erneut:
 * At-least-once. Das zweite Mal verwirft die Datenbank (ON CONFLICT DO NOTHING).
 *
 * Fehler gehören in eine von zwei Klassen (Spezifikation 3.3):
 * - Die Nachricht ist schuld: sie geht sofort nach chat.dlq, ein zweiter
 *   Versuch würde genauso scheitern.
 * - Die Umgebung ist schuld, z. B. die Datenbank ist weg: der Stapel geht
 *   nach einer Pause zurück in die Queue und kommt später wieder.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchListener {

    /** Unter diesem Namen findet man den Listener, z. B. im Test, um ihn anzuhalten. */
    public static final String LISTENER_ID = "messageBatchListener";

    /** Pause, bevor ein Stapel zurückgeht. Ohne sie kreiste er ohne Halt zwischen Queue und Listener. */
    private static final long PAUSE_BEFORE_RETRY_MILLISECONDS = 2000;

    private final ChatMessageReader chatMessageReader;
    private final MessageRepository messageRepository;

    /**
     * Wird von Spring für jeden Stapel aufgerufen: bis zu 500 Nachrichten oder
     * was innerhalb von 200 ms kam (siehe RabbitConfig).
     *
     * Wir bekommen die ROHEN Nachrichten und keine fertigen Objekte: so lesen
     * wir nur den Body und können eine einzelne unlesbare Nachricht
     * aussortieren, ohne den ganzen Stapel zu verlieren.
     */
    @RabbitListener(id = LISTENER_ID, queues = QueueNames.PERSIST_QUEUE,
            containerFactory = RabbitConfig.BATCH_LISTENER_FACTORY)
    public void onBatch(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = readAll(batch, channel);
        if (readableMessages.isEmpty()) {
            return;
        }
        store(readableMessages, channel);
    }

    /**
     * Liest jede Nachricht des Stapels. Was sich nicht lesen lässt, lehnen wir
     * sofort ab. RabbitMQ legt es über die Argumente der Queue nach chat.dlq.
     */
    private List<ReceivedMessage> readAll(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message message : batch) {
            MessageProperties properties = message.getMessageProperties();
            long deliveryTag = properties.getDeliveryTag();
            byte[] body = message.getBody();
            ChatMessage chatMessage = chatMessageReader.read(body);

            if (chatMessage == null) {
                log.warn("Message with delivery tag {} is not a readable chat message, rejecting it to {}",
                        deliveryTag, QueueNames.DEAD_LETTER_QUEUE);
                channel.basicReject(deliveryTag, false);
            } else {
                ReceivedMessage receivedMessage = new ReceivedMessage(deliveryTag, chatMessage);
                readableMessages.add(receivedMessage);
            }
        }
        return readableMessages;
    }

    /**
     * Schreibt den Stapel in einer Transaktion und bestätigt ihn danach mit
     * EINEM ACK. "multiple = true" heisst: alle noch offenen Nachrichten bis
     * einschliesslich dieses Tags sind erledigt.
     */
    private void store(List<ReceivedMessage> messages, Channel channel) throws IOException {
        List<ChatMessage> chatMessages = toChatMessages(messages);
        long lastDeliveryTag = lastDeliveryTag(messages);
        int batchSize = chatMessages.size();

        try {
            messageRepository.insertBatch(chatMessages);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Database refused a message in a batch of {}, storing the batch one by one", batchSize);
            storeOneByOne(messages, channel);
            return;
        } catch (RuntimeException exception) {
            returnToQueue(lastDeliveryTag, channel, exception);
            return;
        }

        // Erst hier, nach dem COMMIT, bestätigen wir den ganzen Stapel.
        channel.basicAck(lastDeliveryTag, true);
        log.info("Stored batch of {} messages", batchSize);
    }

    /**
     * Die Datenbank hat eine Zeile des Stapels abgelehnt, z. B. wegen des
     * Zeichens NUL. Jetzt schreiben wir jede Nachricht einzeln: gute werden
     * bestätigt, nur die abgelehnte geht nach chat.dlq (Spezifikation 3.3, F9).
     * Fällt dabei die Datenbank aus, geht der Rest mit einem NACK zurück.
     */
    private void storeOneByOne(List<ReceivedMessage> messages, Channel channel) throws IOException {
        long lastDeliveryTag = lastDeliveryTag(messages);
        try {
            for (ReceivedMessage message : messages) {
                storeOne(message, channel);
            }
        } catch (RuntimeException exception) {
            returnToQueue(lastDeliveryTag, channel, exception);
        }
    }

    /**
     * Schreibt eine einzelne Nachricht in ihrer eigenen Transaktion. Lehnt die
     * Datenbank sie ab, ist die Nachricht schuld: Reject, sie geht nach
     * chat.dlq. Jeder andere Fehler geht weiter an storeOneByOne.
     */
    private void storeOne(ReceivedMessage message, Channel channel) throws IOException {
        ChatMessage chatMessage = message.chatMessage();
        long deliveryTag = message.deliveryTag();
        List<ChatMessage> single = List.of(chatMessage);

        try {
            messageRepository.insertBatch(single);
        } catch (DataIntegrityViolationException exception) {
            UUID messageId = chatMessage.id();
            log.warn("Database refused message {}, rejecting it to {}", messageId, QueueNames.DEAD_LETTER_QUEUE);
            channel.basicReject(deliveryTag, false);
            return;
        }
        channel.basicAck(deliveryTag, false);
    }

    /**
     * Die Datenbank ist nicht erreichbar, oder etwas anderes Unerwartetes ist
     * passiert. Die Nachrichten sind nicht schuld und gehören nicht in die DLQ.
     * Wir warten kurz und geben den Stapel an RabbitMQ zurück, das ihn erneut
     * liefert (PLANUNG.md 3.6: NACK mit requeue).
     */
    private void returnToQueue(long lastDeliveryTag, Channel channel, RuntimeException exception) throws IOException {
        String reason = exception.getMessage();
        log.warn("Could not store batch, returning it to {} after {} ms: {}",
                QueueNames.PERSIST_QUEUE, PAUSE_BEFORE_RETRY_MILLISECONDS, reason);
        pauseBeforeRetry();
        channel.basicNack(lastDeliveryTag, true, true);
    }

    /**
     * Wartet vor dem NACK. Wird der Thread dabei unterbrochen, weil der
     * batch-writer herunterfährt, hören wir sofort auf zu warten und merken
     * uns die Unterbrechung für Spring.
     */
    private void pauseBeforeRetry() {
        try {
            Thread.sleep(PAUSE_BEFORE_RETRY_MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Nimmt aus den gelesenen Nachrichten den Inhalt, der in die Datenbank geht. */
    private List<ChatMessage> toChatMessages(List<ReceivedMessage> messages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ReceivedMessage message : messages) {
            ChatMessage chatMessage = message.chatMessage();
            chatMessages.add(chatMessage);
        }
        return chatMessages;
    }

    /**
     * Der Tag der LETZTEN lesbaren Nachricht, nie der einer abgelehnten:
     * RabbitMQ würde ein ACK dafür mit "unknown delivery tag" beantworten und
     * den Channel schliessen (Spezifikation 3.3, F12).
     */
    private long lastDeliveryTag(List<ReceivedMessage> messages) {
        int lastIndex = messages.size() - 1;
        ReceivedMessage lastMessage = messages.get(lastIndex);
        return lastMessage.deliveryTag();
    }
}
