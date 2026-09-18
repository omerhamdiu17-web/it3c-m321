package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.config.RabbitConfig;
import ch.benedict.m321.webgateway.dto.ChatMessage;
import ch.benedict.m321.webgateway.dto.ServerEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Prüft den Zustellweg gegen ein ECHTES RabbitMQ: was in den Fanout
 * chat.delivery geht, muss bei den verbundenen Browsern ankommen.
 *
 * Die Browser ersetzen wir durch eine Attrappe der ChatSessionRegistry.
 */
@SpringBootTest
@Testcontainers
class DeliveryListenerIntegrationTest {

    /** So heisst die Klasse im chat-service. Das Gateway kennt sie nicht. */
    private static final String FOREIGN_CLASS_NAME = "ch.benedict.m321.chatservice.dto.ChatMessage";

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ChatSessionRegistry chatSessionRegistry;

    @Test
    void deliversMessageFromFanoutToConnectedBrowsers() {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        ChatMessage chatMessage = new ChatMessage(
                messageId, roomId, "sub-alice", "Alice Muster", "Hallo Zustellweg", Instant.now());

        // Wir spielen den chat-service. Der schreibt den Namen SEINER Klasse
        // in den Header __TypeId__. Das Gateway darf sich davon nicht stören
        // lassen: es liest das JSON in seine eigene Kopie.
        MessagePostProcessor pretendToBeChatService = message -> {
            MessageProperties messageProperties = message.getMessageProperties();
            messageProperties.setHeader("__TypeId__", FOREIGN_CLASS_NAME);
            return message;
        };
        rabbitTemplate.convertAndSend(RabbitConfig.DELIVERY_EXCHANGE, "", chatMessage, pretendToBeChatService);

        // Die Zustellung läuft in einem anderen Thread, deshalb warten wir kurz.
        ArgumentCaptor<ServerEvent> eventCaptor = ArgumentCaptor.forClass(ServerEvent.class);
        verify(chatSessionRegistry, timeout(10000)).sendToAll(eventCaptor.capture());

        ServerEvent deliveredEvent = eventCaptor.getValue();
        assertEquals("message", deliveredEvent.type());
        ChatMessage deliveredMessage = (ChatMessage) deliveredEvent.payload();
        assertEquals(messageId, deliveredMessage.id());
        assertEquals("Hallo Zustellweg", deliveredMessage.content());
    }
}
