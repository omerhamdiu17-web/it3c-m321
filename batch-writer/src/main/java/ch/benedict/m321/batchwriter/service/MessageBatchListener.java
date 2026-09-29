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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holt Stapel aus chat.persist, schreibt sie in die Datenbank und bestätigt
 * sie erst DANACH (PLANUNG.md 3.6, Spezifikation 3.1).
 *
 * Stürzt der batch-writer vor dem ACK ab, liefert RabbitMQ den Stapel erneut:
 * At-least-once. Das zweite Mal verwirft die Datenbank (ON CONFLICT DO NOTHING).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchListener {

    /** Unter diesem Namen findet man den Listener, z. B. im Test, um ihn anzuhalten. */
    public static final String LISTENER_ID = "messageBatchListener";

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

        messageRepository.insertBatch(chatMessages);

        // Erst hier, nach dem COMMIT, bestätigen wir den ganzen Stapel.
        channel.basicAck(lastDeliveryTag, true);
        log.info("Stored batch of {} messages", batchSize);
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
