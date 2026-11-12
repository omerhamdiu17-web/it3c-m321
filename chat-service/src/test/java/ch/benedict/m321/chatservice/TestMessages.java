package ch.benedict.m321.chatservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Schickt in den Tests Nachrichten über POST /messages, wie es ein Absender
 * tut, über echtes HTTP an eine laufende Instanz.
 */
public final class TestMessages {

    /** Ein HttpClient für alle Tests reicht. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** Liest die Antwort mit der vergebenen ID. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Reine Sammlung von Hilfsmethoden, wird nie erzeugt. */
    private TestMessages() {
    }

    /**
     * Schickt eine Nachricht und gibt die ID zurück, die der chat-service
     * vergeben hat. Alles ausser 202 ist im Test ein Fehler.
     */
    public static String post(int port, UUID roomId, String senderId, String content) throws Exception {
        String body = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"" + senderId + "\","
                + "\"senderName\":\"" + senderId + "\",\"content\":\"" + content + "\"}";
        URI uri = URI.create("http://localhost:" + port + "/messages");
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(bodyPublisher)
                .build();

        HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> response = HTTP_CLIENT.send(request, bodyHandler);
        if (response.statusCode() != 202) {
            throw new IllegalStateException("POST /messages gab " + response.statusCode());
        }
        String responseBody = response.body();
        JsonNode accepted = OBJECT_MAPPER.readTree(responseBody);
        return accepted.get("id").asText();
    }

    /** Fragt GET mit beliebigem Pfad und gibt nur den Status zurück, z. B. für 400. */
    public static int statusOfGet(int port, String pathAndQuery) throws Exception {
        URI uri = URI.create("http://localhost:" + port + pathAndQuery);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse.BodyHandler<Void> bodyHandler = HttpResponse.BodyHandlers.discarding();
        HttpResponse<Void> response = HTTP_CLIENT.send(request, bodyHandler);
        return response.statusCode();
    }
}
