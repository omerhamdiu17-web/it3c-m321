package ch.benedict.m321.desktopclient.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ein winziger Webserver auf 127.0.0.1, der genau EINE Anfrage erwartet:
 * die Rückleitung von Keycloak nach dem Login (RFC 8252, Abschnitt 7.3).
 *
 * Der Port ist zufällig (0 = "such dir einen freien aus"). Der Server ist
 * nur vom eigenen Rechner aus erreichbar und läuft nur, solange der Login
 * dauert. "AutoCloseable" heisst: in einem try-with-resources wird er am
 * Ende sicher wieder gestoppt.
 */
public class LoopbackReceiver implements AutoCloseable {

    private static final String CALLBACK_PATH = "/callback";

    private final HttpServer server;
    private final String expectedState;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();

    /** Startet den Server. expectedState ist der Zufallswert, den wir Keycloak mitgegeben haben. */
    public LoopbackReceiver(String expectedState) throws IOException {
        this.expectedState = expectedState;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetSocketAddress anyFreePort = new InetSocketAddress(loopback, 0);
        this.server = HttpServer.create(anyFreePort, 0);
        this.server.createContext(CALLBACK_PATH, this::handleCallback);
        this.server.start();
    }

    /** Die Adresse, die wir Keycloak als redirect_uri nennen. */
    public String redirectUri() {
        int port = server.getAddress().getPort();
        return "http://127.0.0.1:" + port + CALLBACK_PATH;
    }

    /** Wartet, bis Keycloak zurückgeleitet hat, und liefert den Code. */
    public String waitForCode(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
        return codeFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Keycloak leitet den Browser hierher: /callback?code=...&state=...
     * Stimmt state nicht, stammt die Anfrage nicht aus UNSEREM Login.
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> parameters = parseQuery(rawQuery);
        String state = parameters.get("state");
        String code = parameters.get("code");

        if (!expectedState.equals(state)) {
            answer(exchange, 400, "Unerwartete Anmeldung. Bitte im Desktop-Client neu starten.");
            codeFuture.completeExceptionally(new IllegalStateException("state does not match"));
            return;
        }
        if (code == null) {
            // Den Fehlertext aus der Adresse NICHT in die Seite schreiben:
            // wer die Adresse baut, könnte sonst HTML einschleusen.
            String error = parameters.get("error");
            answer(exchange, 400, "Anmeldung abgebrochen. Details stehen im Desktop-Client.");
            codeFuture.completeExceptionally(new IllegalStateException("login failed: " + error));
            return;
        }

        answer(exchange, 200, "Anmeldung abgeschlossen. Dieses Fenster kann geschlossen werden.");
        codeFuture.complete(code);
    }

    /** Zerlegt "a=1&b=2" in eine Map. */
    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null) {
            return parameters;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex > 0) {
                String name = pair.substring(0, equalsIndex);
                String encodedValue = pair.substring(equalsIndex + 1);
                String value = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8);
                parameters.put(name, value);
            }
        }
        return parameters;
    }

    /** Eine kleine HTML-Seite für den Browser, damit der Benutzer weiss, dass es geklappt hat. */
    private void answer(HttpExchange exchange, int statusCode, String text) throws IOException {
        String html = "<!doctype html><html lang=\"de\"><meta charset=\"utf-8\"><title>M321 Chat</title>"
                + "<p>" + text + "</p></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    /** Stoppt den Server. Die eine Anfrage ist durch, er wird nicht mehr gebraucht. */
    @Override
    public void close() {
        server.stop(0);
    }
}
