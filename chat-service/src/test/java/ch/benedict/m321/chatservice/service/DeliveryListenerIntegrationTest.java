package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.QueueNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Der Weg vom POST bis zur offenen Verbindung, mit echtem RabbitMQ: POST →
 * Fanout → eigene Queue → DeliveryListener → RoomSubscribers (Spezifikation 1, 2.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DeliveryListenerIntegrationTest {

    /** Der Raum, in dem der Test-Client sitzt. */
    private static final UUID ROOM_ID = UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001");

    /** Ein echter Broker, damit der Fanout wirklich verteilt. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomSubscribers roomSubscribers;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    /** Der Test-Client in ROOM_ID, für jeden Test neu. */
    private RecordingEmitter client;

    /** Jeder Test beginnt mit einem neuen Client und einer leeren chat.persist. */
    @BeforeEach
    void subscribeClient() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        client = new RecordingEmitter();
        roomSubscribers.subscribe(ROOM_ID, client);
    }

    /** Der Client darf in den nächsten Test nicht mitgenommen werden. */
    @AfterEach
    void unsubscribeClient() {
        roomSubscribers.unsubscribe(ROOM_ID, client);
    }

    /**
     * Nach einem POST bekommt der Client genau ein Ereignis: die ID aus der
     * Antwort und als data dasselbe JSON, das in chat.persist liegt.
     */
    @Test
    void postedMessageReachesClientInItsRoom() throws Exception {
        String messageId = postMessage("Hallo live");

        client.waitForEvents(1);

        Message persisted = rabbitTemplate.receive(QueueNames.PERSIST_QUEUE, 5000);
        String persistedJson = new String(persisted.getBody(), StandardCharsets.UTF_8);
        String expectedEvent = "id:" + messageId + "\ndata:" + persistedJson + "\n\n";
        assertEquals(1, client.events.size());
        assertEquals(expectedEvent, client.events.get(0));
    }

    /**
     * Ein unlesbarer Body am Fanout wird verworfen. Er darf den Listener nicht
     * blockieren: die nächste gültige Nachricht kommt trotzdem an.
     */
    @Test
    void unreadableMessageIsDroppedAndNextOneStillArrives() throws Exception {
        byte[] garbage = "kein JSON".getBytes(StandardCharsets.UTF_8);
        Message unreadable = new Message(garbage);
        rabbitTemplate.send(QueueNames.DELIVERY_EXCHANGE, "", unreadable);
        String messageId = postMessage("nach dem Müll");

        client.waitForEvents(1);

        assertEquals(1, client.events.size());
        String receivedId = client.receivedIds().get(0);
        assertEquals(messageId, receivedId);
    }

    /** Schickt eine Nachricht per POST /messages und gibt die vergebene ID zurück. */
    private String postMessage(String content) throws Exception {
        String requestBody = "{\"roomId\":\"" + ROOM_ID + "\",\"senderId\":\"anna\","
                + "\"senderName\":\"Anna Muster\",\"content\":\"" + content + "\"}";
        MvcResult result = mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andReturn();
        String responseBody = result.getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(responseBody);
        return response.get("id").asText();
    }
}
