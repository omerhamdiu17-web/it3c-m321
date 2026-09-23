package ch.benedict.m321.desktopclient.api;

import ch.benedict.m321.desktopclient.auth.AccessTokenProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Die Schnittstelle zum Gateway — dieselbe, die auch die Web-UI benutzt:
 * /api/me, /api/rooms, /api/rooms/{id}/messages und der WebSocket /ws/chat.
 *
 * Einziger Unterschied zum Browser: statt eines Session-Cookies geht bei
 * jeder Anfrage "Authorization: Bearer <Access-Token>" mit. Das Backend
 * braucht dafür keine Sonderlogik (PLANUNG.md, Abschnitt 2.2).
 */
public class ChatApiClient {

    private final String gatewayBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AccessTokenProvider accessTokenProvider;

    /**
     * @param gatewayBaseUrl      z.B. http://localhost:8080
     * @param httpClient          der HTTP-Client aus dem JDK
     * @param objectMapper        siehe createObjectMapper()
     * @param accessTokenProvider liefert bei jeder Anfrage ein gültiges Token
     */
    public ChatApiClient(String gatewayBaseUrl, HttpClient httpClient, ObjectMapper objectMapper,
                         AccessTokenProvider accessTokenProvider) {
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.accessTokenProvider = accessTokenProvider;
    }

    /**
     * Ein ObjectMapper, der Instant im ISO-Format lesen kann (JavaTimeModule)
     * und Felder überspringt, die er nicht kennt. So bricht der Client nicht,
     * wenn das Gateway später ein Feld mehr schickt.
     */
    public static ObjectMapper createObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Wer bin ich? Das Gateway liest es aus dem Token. */
    public CurrentUser loadCurrentUser() throws IOException, InterruptedException {
        String json = get("/api/me");
        return objectMapper.readValue(json, CurrentUser.class);
    }

    /**
     * Alle Räume. Die TypeReference sagt Jackson, WAS in der Liste steckt;
     * zur Laufzeit weiss Java das sonst nicht mehr.
     */
    public List<Room> loadRooms() throws IOException, InterruptedException {
        String json = get("/api/rooms");
        TypeReference<List<Room>> roomListType = new TypeReference<>() {
        };
        return objectMapper.readValue(json, roomListType);
    }

    /** Die letzten 50 Nachrichten eines Raums, die älteste zuerst. */
    public List<ChatMessage> loadHistory(UUID roomId) throws IOException, InterruptedException {
        String json = get("/api/rooms/" + roomId + "/messages");
        TypeReference<List<ChatMessage>> messageListType = new TypeReference<>() {
        };
        return objectMapper.readValue(json, messageListType);
    }

    /**
     * Öffnet den WebSocket für einen Raum. Jedes vollständige Ereignis
     * (JSON-Text) geht an onEvent. Das Token geht beim Verbindungsaufbau
     * mit; danach bleibt die Verbindung offen, auch wenn das Token abläuft.
     */
    public WebSocket connect(UUID roomId, Consumer<String> onEvent) throws IOException, InterruptedException {
        String accessToken = accessTokenProvider.currentAccessToken();
        String socketUrl = gatewayBaseUrl.replaceFirst("^http", "ws") + "/ws/chat?roomId=" + roomId;
        URI socketUri = URI.create(socketUrl);

        TextCollector listener = new TextCollector(onEvent);
        CompletableFuture<WebSocket> connecting = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + accessToken)
                .buildAsync(socketUri, listener);
        return connecting.join();
    }

    /** Schickt eine Nachricht über den offenen WebSocket. Nur Raum und Text, den Absender kennt das Gateway. */
    public void send(WebSocket socket, UUID roomId, String content) throws IOException {
        Map<String, Object> outgoing = new HashMap<>();
        outgoing.put("roomId", roomId);
        outgoing.put("content", content);
        String json = objectMapper.writeValueAsString(outgoing);
        socket.sendText(json, true);
    }

    /** GET mit Bearer-Token. Alles ausser 200 ist ein Fehler. */
    private String get(String path) throws IOException, InterruptedException {
        String accessToken = accessTokenProvider.currentAccessToken();
        URI uri = URI.create(gatewayBaseUrl + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Gateway answered " + path + " with HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Sammelt die Teile eines WebSocket-Textes. Der JDK-Client darf einen
     * langen Text in mehreren Stücken liefern; "last" sagt, wann er komplett ist.
     */
    private static class TextCollector implements WebSocket.Listener {

        private final Consumer<String> onEvent;
        private final StringBuilder buffer = new StringBuilder();

        TextCollector(Consumer<String> onEvent) {
            this.onEvent = onEvent;
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String completeText = buffer.toString();
                buffer.setLength(0);
                onEvent.accept(completeText);
            }
            // Dem JDK sagen: bereit für das nächste Stück.
            webSocket.request(1);
            return null;
        }
    }
}
