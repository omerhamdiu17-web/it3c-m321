package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.dto.AcceptedResponse;
import ch.benedict.m321.webgateway.dto.ChatMessage;
import ch.benedict.m321.webgateway.dto.Room;
import ch.benedict.m321.webgateway.dto.SendMessageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Der Sendeweg: reicht eine Nachricht per REST an den chat-service weiter.
 *
 * Das Gateway legt selbst NICHTS in eine Queue (PLANUNG.md, Abschnitt 3.4).
 * Es gibt genau eine Stelle, die Nachrichten annimmt und die Regeln kennt,
 * und das ist der chat-service.
 */
@Service
@Slf4j
public class ChatServiceClient {

    private final RestClient restClient;

    /**
     * Den Konstruktor schreiben wir hier von Hand, weil die Adresse des
     * chat-service aus der application.yml kommt (@Value). Der Builder
     * kommt von Spring Boot und bringt die JSON-Umwandlung schon mit.
     */
    public ChatServiceClient(RestClient.Builder restClientBuilder,
                             @Value("${chat-service.base-url}") String chatServiceBaseUrl) {
        RestClient.Builder builderWithBaseUrl = restClientBuilder.baseUrl(chatServiceBaseUrl);
        this.restClient = builderWithBaseUrl.build();
    }

    /**
     * Schickt die Nachricht an POST /messages und liefert die Bestätigung.
     *
     * Antwortet der chat-service mit einem Fehler (z.B. 503, weil der
     * Broker weg ist) oder ist er gar nicht erreichbar, wirft der
     * RestClient eine RestClientException. Die fängt der Aufrufer und
     * meldet dem Benutzer ehrlich: nicht gesendet.
     */
    public AcceptedResponse send(SendMessageRequest request) {
        AcceptedResponse acceptedResponse = restClient.post()
                .uri("/messages")
                .body(request)
                .retrieve()
                .body(AcceptedResponse.class);

        log.debug("chat-service accepted message for room {}", request.roomId());
        return acceptedResponse;
    }

    /**
     * Holt alle Räume vom chat-service (GET /rooms).
     *
     * Eine Liste lässt sich nicht einfach mit List.class abholen, weil Java
     * zur Laufzeit nicht mehr weiss, WAS in der Liste steckt. Die
     * ParameterizedTypeReference hält diese Information fest.
     */
    public List<Room> loadRooms() {
        ParameterizedTypeReference<List<Room>> roomListType = new ParameterizedTypeReference<>() {
        };
        return restClient.get()
                .uri("/rooms")
                .retrieve()
                .body(roomListType);
    }

    /** Holt die letzten Nachrichten eines Raums vom chat-service (GET /rooms/{id}/messages). */
    public List<ChatMessage> loadHistory(UUID roomId) {
        ParameterizedTypeReference<List<ChatMessage>> messageListType = new ParameterizedTypeReference<>() {
        };
        return restClient.get()
                .uri("/rooms/{roomId}/messages", roomId)
                .retrieve()
                .body(messageListType);
    }
}
