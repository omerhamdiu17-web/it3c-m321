package ch.benedict.m321.desktopclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein kleiner, ECHTER Webserver für die Tests. Er spielt Keycloak oder das
 * Gateway: auf einem Pfad antwortet er mit festem JSON und merkt sich, was
 * ankam (Kopfzeilen und Inhalt), damit der Test es prüfen kann.
 */
public class FakeServer implements AutoCloseable {

    /** Was der Server empfangen hat. */
    public record ReceivedRequest(String method, String path, String authorization, String body) {
    }

    private final HttpServer server;
    private final List<ReceivedRequest> receivedRequests = new CopyOnWriteArrayList<>();

    /** Startet den Server auf einem freien Port; unter path antwortet er mit status und json. */
    public FakeServer(String path, int status, String json) throws IOException {
        InetSocketAddress anyFreePort = new InetSocketAddress("127.0.0.1", 0);
        server = HttpServer.create(anyFreePort, 0);
        server.createContext(path, exchange -> answer(exchange, status, json));
        server.start();
    }

    /** z.B. http://127.0.0.1:54321 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<ReceivedRequest> receivedRequests() {
        return receivedRequests;
    }

    private void answer(HttpExchange exchange, int status, String json) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        String requestBody = new String(requestBytes, StandardCharsets.UTF_8);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath();
        receivedRequests.add(new ReceivedRequest(exchange.getRequestMethod(), path, authorization, requestBody));

        byte[] responseBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(responseBytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
