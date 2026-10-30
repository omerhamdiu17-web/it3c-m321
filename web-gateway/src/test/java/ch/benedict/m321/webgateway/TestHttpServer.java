package ch.benedict.m321.webgateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein kleiner HTTP-Server aus dem JDK, der im Test einen anderen Dienst spielt,
 * zum Beispiel Keycloak (Spezifikation 4.4: Mini-Server aus dem JDK, wie in E6).
 *
 * Er antwortet auf festgelegte Pfade mit festen Antworten, auf alle anderen mit
 * 404, und merkt sich jede Anfrage. So prüft ein Test nicht nur, was das Gateway
 * antwortet, sondern auch, was beim Dienst dahinter ankommt und was nicht.
 */
public final class TestHttpServer {

    /** Eine feste Antwort: Status, Header und Text. */
    public record Answer(int status, Map<String, String> headers, String body) {
    }

    /** Eine Anfrage, wie sie beim Server ankam; die Header ohne Gross- und Kleinschreibung. */
    public record Request(String method, String path, String query, Headers headers, String body) {
    }

    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /** Bindet einen freien Port auf localhost; gestartet wird in start(). */
    private TestHttpServer() throws IOException {
        InetSocketAddress anyFreePort = new InetSocketAddress("localhost", 0);
        server = HttpServer.create(anyFreePort, 0);
        RecordingHandler handler = new RecordingHandler(this);
        server.createContext("/", handler);
    }

    /** Startet einen Server auf einem freien Port von localhost. */
    public static TestHttpServer start() {
        try {
            TestHttpServer testServer = new TestHttpServer();
            testServer.server.start();
            return testServer;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Vollständige Adresse eines Pfads auf diesem Server. */
    public String url(String path) {
        InetSocketAddress address = server.getAddress();
        int port = address.getPort();
        return "http://localhost:" + port + path;
    }

    /** Legt fest, was der Server auf einen Pfad antwortet (Pfad ohne Query, wie er ankommt). */
    public void answer(String path, Answer answer) {
        answers.put(path, answer);
    }

    /** Alle Anfragen seit dem letzten clear(), in der Reihenfolge ihres Eingangs. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Vergisst die bisherigen Anfragen; die festen Antworten bleiben. */
    public void clear() {
        requests.clear();
    }

    /** Hält den Server sofort an und gibt den Port frei. */
    public void stop() {
        server.stop(0);
    }

    /** Nimmt jede Anfrage an: merken, Antwort suchen, senden. */
    private static final class RecordingHandler implements HttpHandler {

        private final TestHttpServer owner;

        /** Der Handler legt die Anfragen beim Server ab, zu dem er gehört. */
        private RecordingHandler(TestHttpServer owner) {
            this.owner = owner;
        }

        /** Wird vom JDK-Server für jede Anfrage aufgerufen. */
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Request request = read(exchange);
            owner.requests.add(request);
            Answer answer = owner.answers.get(request.path());
            if (answer == null) {
                Map<String, String> noHeaders = Map.of();
                answer = new Answer(404, noHeaders, "");
            }
            send(exchange, answer);
        }

        /** Liest Methode, Pfad, Query, Header und Body der Anfrage. */
        private static Request read(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            Headers received = exchange.getRequestHeaders();
            Headers headers = new Headers();
            headers.putAll(received);
            // var statt des Typnamens: Er enthält das Wort, nach dem W11 sucht.
            var requestBody = exchange.getRequestBody();
            byte[] bytes = requestBody.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            return new Request(method, path, query, headers, body);
        }

        /** Schickt Status, Header und Body der festen Antwort. */
        private static void send(HttpExchange exchange, Answer answer) throws IOException {
            Headers responseHeaders = exchange.getResponseHeaders();
            Map<String, String> headers = answer.headers();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                responseHeaders.add(header.getKey(), header.getValue());
            }
            String text = answer.body();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            long length = bytes.length;
            if (bytes.length == 0) {
                // -1 heisst beim JDK-Server: Diese Antwort hat keinen Body.
                length = -1;
            }
            exchange.sendResponseHeaders(answer.status(), length);
            if (bytes.length > 0) {
                // var statt des Typnamens, wie oben.
                var responseBody = exchange.getResponseBody();
                responseBody.write(bytes);
            }
            exchange.close();
        }
    }
}
