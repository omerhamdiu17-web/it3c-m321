package ch.benedict.m321.webgateway.service;

import ch.benedict.m321.webgateway.dto.QueueStats;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Fragt die Management-API von RabbitMQ, wie voll die Queue chat.persist ist.
 *
 * Die Management-API läuft im Container rabbitmq auf Port 15672 und ist nur
 * im Docker-Netz erreichbar. Die Management-OBERFLÄCHE bleibt zu: die
 * Vorgabe "nur die Web-App nach aussen" gilt auch für bequeme Werkzeuge
 * (PLANUNG.md, Abschnitt 4.2). Das Gateway holt nur diese eine Zahl.
 */
@Service
@Slf4j
public class QueueStatsClient {

    /** Die Queue, an der man sieht, ob der batch-writer hinterherkommt. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /**
     * Adresse der Queue in der Management-API. "%2F" ist der virtuelle Host
     * "/" — schon kodiert, weil ein Schrägstrich sonst als Pfad gelesen würde.
     */
    private static final String QUEUE_PATH = "/api/queues/%2F/" + PERSIST_QUEUE;

    private final RestClient restClient;
    private final URI queueUri;

    /**
     * Den Konstruktor schreiben wir von Hand, weil Adresse und Zugang aus
     * der application.yml kommen. Die Management-API verlangt denselben
     * Benutzer wie der Broker, per HTTP Basic Auth.
     */
    public QueueStatsClient(RestClient.Builder restClientBuilder,
                            @Value("${rabbitmq-management.base-url}") String managementBaseUrl,
                            @Value("${spring.rabbitmq.username}") String username,
                            @Value("${spring.rabbitmq.password}") String password) {
        BasicAuthenticationInterceptor basicAuthentication = new BasicAuthenticationInterceptor(username, password);
        RestClient.Builder builderWithLogin = restClientBuilder.requestInterceptor(basicAuthentication);
        this.restClient = builderWithLogin.build();
        // Als fertige URI und nicht als Vorlage: sonst würde aus "%2F" ein "%252F".
        this.queueUri = URI.create(managementBaseUrl + QUEUE_PATH);
    }

    /**
     * Holt die aktuellen Zahlen der Queue chat.persist.
     *
     * Die Antwort der Management-API hat Dutzende Felder, wir brauchen vier.
     * Deshalb lesen wir sie als JsonNode und picken heraus, was wir brauchen.
     * path(...) liefert bei fehlenden Feldern 0 statt eines Fehlers — die
     * Raten fehlen z.B., solange noch keine Nachricht durchgelaufen ist.
     */
    public QueueStats loadPersistQueueStats() {
        JsonNode queue = restClient.get()
                .uri(queueUri)
                .retrieve()
                .body(JsonNode.class);

        long messages = queue.path("messages").asLong();
        int consumers = queue.path("consumers").asInt();
        JsonNode messageStats = queue.path("message_stats");
        double publishRate = messageStats.path("publish_details").path("rate").asDouble();
        double ackRate = messageStats.path("ack_details").path("rate").asDouble();

        return new QueueStats(PERSIST_QUEUE, messages, consumers, publishRate, ackRate);
    }
}
