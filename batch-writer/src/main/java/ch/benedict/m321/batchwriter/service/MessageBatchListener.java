package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Holt Stapel aus chat.persist und schreibt sie in die Datenbank
 * (PLANUNG.md 3.6, Spezifikation 3.1).
 *
 * Bestätigen muss diese Klasse nichts selbst, das macht Spring (AUTO, siehe
 * RabbitConfig):
 * - onBatch endet ohne Fehler: Spring schickt EIN ACK für den ganzen Stapel.
 *   Dann ist der COMMIT schon passiert.
 * - onBatch wirft einen Fehler: Spring schickt ein NACK mit requeue, und
 *   RabbitMQ liefert den ganzen Stapel später noch einmal.
 *
 * Stürzt der batch-writer vor dem ACK ab, kommt der Stapel ebenfalls noch
 * einmal: At-least-once. Das zweite Mal verwirft die Datenbank die Zeilen
 * (ON CONFLICT DO NOTHING).
 *
 * Fehler gehören in eine von zwei Klassen (Spezifikation 3.3):
 * - Die Nachricht ist schuld: sie kommt nach chat.dlq, ein zweiter Versuch
 *   würde genauso scheitern. Der Rest des Stapels wird gespeichert.
 * - Die Umgebung ist schuld, z. B. die Datenbank ist weg: nach einer Pause
 *   geht der Fehler an Spring, und der Stapel kommt später wieder.
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
    private final RabbitTemplate rabbitTemplate;

    /**
     * Wird von Spring für jeden Stapel aufgerufen: bis zu 500 Nachrichten oder
     * was innerhalb von 200 ms kam (siehe RabbitConfig).
     *
     * Drei Schritte: lesen, speichern, Unspeicherbares nach chat.dlq. In die
     * Liste deadLetters kommt alles, was nach chat.dlq gehört. Wir schicken sie
     * erst am Schluss ab: fällt vorher die Datenbank aus, kommt der ganze
     * Stapel wieder, und jede dieser Nachrichten läge sonst nach jedem Versuch
     * ein weiteres Mal in chat.dlq.
     */
    @RabbitListener(id = LISTENER_ID, queues = QueueNames.PERSIST_QUEUE,
            containerFactory = RabbitConfig.BATCH_LISTENER_FACTORY)
    public void onBatch(List<Message> batch) {
        try {
            List<Message> deadLetters = new ArrayList<>();
            List<ReceivedMessage> readableMessages = readAll(batch, deadLetters);
            store(readableMessages, deadLetters);
            sendToDeadLetterQueue(deadLetters);
        } catch (RuntimeException exception) {
            String reason = exception.getMessage();
            log.warn("Could not process batch, returning it to {} after {} ms: {}",
                    QueueNames.PERSIST_QUEUE, PAUSE_BEFORE_RETRY_MILLISECONDS, reason);
            pauseBeforeRetry();
            // Weiterwerfen ist Absicht: nur daran erkennt Spring, dass der
            // Stapel zurück in die Queue muss (PLANUNG.md 3.6: NACK mit requeue).
            throw exception;
        }
    }

    /**
     * Liest jede Nachricht des Stapels. Was sich nicht lesen lässt, kommt in
     * die Liste für chat.dlq: ein zweiter Versuch würde genauso scheitern (F8).
     */
    private List<ReceivedMessage> readAll(List<Message> batch, List<Message> deadLetters) {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message message : batch) {
            byte[] body = message.getBody();
            ChatMessage chatMessage = chatMessageReader.read(body);

            if (chatMessage == null) {
                log.warn("Message is not a readable chat message, moving it to {}", QueueNames.DEAD_LETTER_QUEUE);
                deadLetters.add(message);
            } else {
                ReceivedMessage receivedMessage = new ReceivedMessage(message, chatMessage);
                readableMessages.add(receivedMessage);
            }
        }
        return readableMessages;
    }

    /**
     * Schreibt alle lesbaren Nachrichten in EINER Transaktion. Lehnt die
     * Datenbank eine Zeile ab, schreiben wir den Stapel einzeln (F9). Jede
     * andere Exception, z. B. "Datenbank nicht erreichbar", fliegt weiter bis
     * in onBatch.
     */
    private void store(List<ReceivedMessage> readableMessages, List<Message> deadLetters) {
        // Waren alle unlesbar, gibt es nichts zu schreiben. Dann auch keine
        // Transaktion, die bei einem Datenbank-Ausfall scheitern könnte.
        if (readableMessages.isEmpty()) {
            return;
        }
        List<ChatMessage> chatMessages = toChatMessages(readableMessages);
        int batchSize = chatMessages.size();

        try {
            messageRepository.insertBatch(chatMessages);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Database refused a message in a batch of {}, storing the batch one by one", batchSize);
            storeOneByOne(readableMessages, deadLetters);
            return;
        }
        log.info("Stored batch of {} messages", batchSize);
    }

    /**
     * Die Datenbank hat eine Zeile des Stapels abgelehnt, z. B. wegen des
     * Zeichens NUL. Jetzt schreiben wir jede Nachricht in ihrer eigenen
     * Transaktion. Nur die abgelehnte kommt in die Liste für chat.dlq (F9).
     * Fällt dabei die Datenbank aus, fliegt die Exception weiter bis in
     * onBatch, und der ganze Stapel kommt wieder. Schon gespeicherte verwirft
     * die Datenbank beim nächsten Mal (ON CONFLICT DO NOTHING).
     */
    private void storeOneByOne(List<ReceivedMessage> readableMessages, List<Message> deadLetters) {
        for (ReceivedMessage receivedMessage : readableMessages) {
            ChatMessage chatMessage = receivedMessage.chatMessage();
            List<ChatMessage> single = List.of(chatMessage);
            try {
                messageRepository.insertBatch(single);
            } catch (DataIntegrityViolationException exception) {
                UUID messageId = chatMessage.id();
                log.warn("Database refused message {}, moving it to {}", messageId, QueueNames.DEAD_LETTER_QUEUE);
                Message original = receivedMessage.original();
                deadLetters.add(original);
            }
        }
    }

    /**
     * Legt jede Nachricht, die wir nie speichern können, unverändert nach
     * chat.dlq: gleicher Body, gleiche Header. Ohne Exchange-Namen geht sie
     * über den Standard-Exchange direkt in die Queue mit diesem Namen, wie
     * im chat-service. Scheitert das Senden, fliegt die Exception bis in
     * onBatch: der Stapel kommt wieder, und die schon gespeicherten Zeilen
     * verwirft die Datenbank dann (ON CONFLICT DO NOTHING).
     */
    private void sendToDeadLetterQueue(List<Message> deadLetters) {
        for (Message deadLetter : deadLetters) {
            // Beim Empfang merkt sich Spring nur, WIE die Nachricht kam
            // (receivedDeliveryMode), und lässt deliveryMode leer. Ohne diese
            // Zeile wäre die Kopie nicht persistent, und ein Neustart von
            // RabbitMQ löschte sie aus chat.dlq.
            MessageProperties properties = deadLetter.getMessageProperties();
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            rabbitTemplate.send(QueueNames.DEAD_LETTER_QUEUE, deadLetter);
        }
    }

    /**
     * Wartet, bevor der Stapel zurückgeht. Wird der Thread dabei unterbrochen,
     * weil der batch-writer herunterfährt, hören wir sofort auf zu warten und
     * merken uns die Unterbrechung für Spring.
     */
    private void pauseBeforeRetry() {
        try {
            Thread.sleep(PAUSE_BEFORE_RETRY_MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Nimmt aus den gelesenen Nachrichten den Inhalt, der in die Datenbank geht. */
    private List<ChatMessage> toChatMessages(List<ReceivedMessage> readableMessages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ReceivedMessage receivedMessage : readableMessages) {
            ChatMessage chatMessage = receivedMessage.chatMessage();
            chatMessages.add(chatMessage);
        }
        return chatMessages;
    }
}
