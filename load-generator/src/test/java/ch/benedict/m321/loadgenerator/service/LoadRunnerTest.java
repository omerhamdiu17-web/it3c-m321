package ch.benedict.m321.loadgenerator.service;

import ch.benedict.m321.loadgenerator.LoadProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Lastschleife gegen einen kleinen, ECHTEN Webserver, der den
 * chat-service spielt. Der HttpServer steckt im JDK selbst; er nimmt die
 * Anfragen an und merkt sich, was ankam.
 */
class LoadRunnerTest {

    private static final UUID LOAD_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private HttpServer fakeChatService;
    private ExecutorService serverThreads;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopFakeChatService() {
        fakeChatService.stop(0);
        serverThreads.shutdown();
    }

    @Test
    void sendsTargetRateIntoLoadRoom() throws Exception {
        startFakeChatService(202);
        // 600 pro Minute = 10 pro Sekunde, zwei Sekunden lang: 20 Nachrichten.
        LoadRunner loadRunner = createLoadRunner(600, 2);

        LoadStatistics statistics = loadRunner.run();

        assertEquals(20, statistics.acceptedCount());
        assertEquals(0, statistics.failedCount());
        assertEquals(20, receivedBodies.size());
        for (String body : receivedBodies) {
            assertTrue(body.contains(LOAD_ROOM_ID.toString()), "Nachricht ging nicht in den Raum Lasttest");
        }
        Map<String, Long> byInstance = statistics.acceptedByInstance();
        assertEquals(20L, byInstance.get("test-instanz"));
    }

    @Test
    void countsFailuresWhenChatServiceRejects() throws Exception {
        startFakeChatService(503);
        LoadRunner loadRunner = createLoadRunner(600, 1);

        LoadStatistics statistics = loadRunner.run();

        assertEquals(0, statistics.acceptedCount());
        assertEquals(10, statistics.failedCount());
    }

    /** Baut den LoadRunner so, wie Spring ihn bauen würde, nur mit Testwerten. */
    private LoadRunner createLoadRunner(int messagesPerMinute, int durationSeconds) {
        int port = fakeChatService.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        LoadProperties loadProperties = new LoadProperties(baseUrl, messagesPerMinute, durationSeconds, LOAD_ROOM_ID, 50);
        RestClient.Builder restClientBuilder = RestClient.builder();
        return new LoadRunner(restClientBuilder, loadProperties);
    }

    /** Startet den falschen chat-service auf einem freien Port. Er antwortet immer mit dem angegebenen Status. */
    private void startFakeChatService(int statusCode) throws IOException {
        InetSocketAddress anyFreePort = new InetSocketAddress("127.0.0.1", 0);
        fakeChatService = HttpServer.create(anyFreePort, 0);
        fakeChatService.createContext("/messages", exchange -> answer(exchange, statusCode));
        serverThreads = Executors.newFixedThreadPool(8);
        fakeChatService.setExecutor(serverThreads);
        fakeChatService.start();
    }

    /** Liest die Anfrage, merkt sie sich und antwortet wie der chat-service. */
    private void answer(HttpExchange exchange, int statusCode) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        String requestBody = new String(requestBytes, StandardCharsets.UTF_8);
        receivedBodies.add(requestBody);

        String responseJson = "{\"id\":\"" + UUID.randomUUID() + "\",\"sentAt\":\"2026-09-23T10:00:00Z\"}";
        byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add(LoadRunner.INSTANCE_HEADER, "test-instanz");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(responseBytes);
        }
    }
}
