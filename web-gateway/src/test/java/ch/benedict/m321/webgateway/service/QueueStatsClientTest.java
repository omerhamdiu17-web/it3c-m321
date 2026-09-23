package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.dto.QueueStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Prüft, dass das Gateway die richtigen vier Zahlen aus der Antwort der
 * RabbitMQ-Management-API liest. MockRestServiceServer spielt RabbitMQ.
 */
class QueueStatsClientTest {

    private static final String MANAGEMENT_BASE_URL = "http://rabbitmq:15672";

    private MockRestServiceServer rabbitMqMock;
    private QueueStatsClient queueStatsClient;

    @BeforeEach
    void createClientWithMockedServer() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        rabbitMqMock = MockRestServiceServer.bindTo(restClientBuilder).build();
        queueStatsClient = new QueueStatsClient(restClientBuilder, MANAGEMENT_BASE_URL, "chat", "geheim");
    }

    @Test
    void readsDepthConsumersAndRates() {
        // Ein Ausschnitt dessen, was RabbitMQ wirklich liefert. Alles andere wird ignoriert.
        String responseJson = """
                {
                  "name": "chat.persist",
                  "messages": 12345,
                  "consumers": 2,
                  "message_stats": {
                    "publish_details": { "rate": 1666.4 },
                    "ack_details": { "rate": 1500.0 }
                  }
                }
                """;
        rabbitMqMock.expect(requestTo(MANAGEMENT_BASE_URL + "/api/queues/%2F/chat.persist"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic Y2hhdDpnZWhlaW0="))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseJson));

        QueueStats queueStats = queueStatsClient.loadPersistQueueStats();

        assertEquals("chat.persist", queueStats.queueName());
        assertEquals(12345, queueStats.messages());
        assertEquals(2, queueStats.consumers());
        assertEquals(1666.4, queueStats.publishRatePerSecond(), 0.01);
        assertEquals(1500.0, queueStats.ackRatePerSecond(), 0.01);
        rabbitMqMock.verify();
    }

    @Test
    void treatsMissingRatesAsZero() {
        // Frisch angelegte Queue: noch keine message_stats.
        String responseJson = """
                { "name": "chat.persist", "messages": 0, "consumers": 1 }
                """;
        rabbitMqMock.expect(requestTo(MANAGEMENT_BASE_URL + "/api/queues/%2F/chat.persist"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseJson));

        QueueStats queueStats = queueStatsClient.loadPersistQueueStats();

        assertEquals(0, queueStats.messages());
        assertEquals(0.0, queueStats.publishRatePerSecond(), 0.01);
    }
}
