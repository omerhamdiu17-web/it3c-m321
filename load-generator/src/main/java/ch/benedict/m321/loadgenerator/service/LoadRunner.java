package ch.benedict.m321.loadgenerator.service;

import ch.benedict.m321.loadgenerator.LoadProperties;
import ch.benedict.m321.loadgenerator.dto.SendMessageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Die eigentliche Lastschleife: jede Sekunde N Anfragen an den chat-service.
 *
 * Jede Anfrage läuft in einem eigenen VIRTUELLEN Thread (Java 21). Ein
 * normaler Thread kostet rund ein Megabyte Speicher, ein virtueller fast
 * nichts — man darf tausende davon haben. Während ein virtueller Thread auf
 * die Antwort wartet, gibt er seinen echten Thread für andere frei.
 *
 * Damit der Generator nicht selbst zum Engpass wird, begrenzt eine
 * Semaphore die Anfragen, die gleichzeitig unterwegs sind. Kommt der
 * chat-service nicht nach, wartet die Schleife — und die tatsächliche Rate
 * sinkt unter das Ziel. Genau das zeigt das Protokoll: Soll und Ist.
 */
@Service
@Slf4j
public class LoadRunner {

    /** Derselbe Header wie im MessageController des chat-service. */
    public static final String INSTANCE_HEADER = "X-Chat-Service-Instance";

    private static final String SENDER_ID = "load-generator";
    private static final String SENDER_NAME = "Lastgenerator";

    private final RestClient restClient;
    private final LoadProperties loadProperties;

    /**
     * Den Konstruktor schreiben wir von Hand, weil die Adresse des
     * chat-service aus den LoadProperties kommt. Der Builder kommt von
     * Spring Boot und bringt die JSON-Umwandlung schon mit.
     */
    public LoadRunner(RestClient.Builder restClientBuilder, LoadProperties loadProperties) {
        RestClient.Builder builderWithBaseUrl = restClientBuilder.baseUrl(loadProperties.chatServiceBaseUrl());
        this.restClient = builderWithBaseUrl.build();
        this.loadProperties = loadProperties;
    }

    /**
     * Führt den ganzen Lastversuch durch und liefert am Ende die Zahlen.
     *
     * "try (ExecutorService ...)" schliesst den Executor am Ende und wartet
     * dabei, bis auch die letzte Anfrage zurück ist.
     */
    public LoadStatistics run() throws InterruptedException {
        int messagesPerSecond = loadProperties.messagesPerSecond();
        int durationSeconds = loadProperties.durationSeconds();
        log.info("Starting load: {} messages per minute ({} per second) for {} seconds into room {}",
                loadProperties.messagesPerMinute(), messagesPerSecond, durationSeconds, loadProperties.roomId());

        LoadStatistics statistics = new LoadStatistics();
        Semaphore inFlight = new Semaphore(loadProperties.maxInFlight());
        long messageNumber = 0;
        long startNanos = System.nanoTime();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int second = 1; second <= durationSeconds; second++) {
                long secondStartNanos = System.nanoTime();
                long acceptedBefore = statistics.acceptedCount();
                long failedBefore = statistics.failedCount();

                for (int i = 0; i < messagesPerSecond; i++) {
                    // Wartet, wenn schon maxInFlight Anfragen unterwegs sind.
                    inFlight.acquire();
                    messageNumber = messageNumber + 1;
                    long number = messageNumber;
                    executor.submit(() -> sendOne(number, statistics, inFlight));
                }

                waitForRestOfSecond(secondStartNanos);
                logSecond(second, messagesPerSecond, statistics, acceptedBefore, failedBefore, inFlight);
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        logSummary(statistics, elapsedNanos);
        return statistics;
    }

    /**
     * Schickt eine Nachricht und zählt das Ergebnis. Läuft in einem
     * virtuellen Thread. Das "finally" gibt den Platz in der Semaphore in
     * JEDEM Fall wieder frei, auch bei einem Fehler.
     */
    private void sendOne(long messageNumber, LoadStatistics statistics, Semaphore inFlight) {
        try {
            SendMessageRequest request = new SendMessageRequest(
                    loadProperties.roomId(), SENDER_ID, SENDER_NAME, "Lastnachricht " + messageNumber);

            ResponseEntity<Void> response = restClient.post()
                    .uri("/messages")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            String instanceName = response.getHeaders().getFirst(INSTANCE_HEADER);
            statistics.recordAccepted(instanceName);
        } catch (RestClientException exception) {
            statistics.recordFailed();
        } finally {
            inFlight.release();
        }
    }

    /** Schläft bis zum Ende der angefangenen Sekunde. War die Sekunde schon um, geht es sofort weiter. */
    private void waitForRestOfSecond(long secondStartNanos) throws InterruptedException {
        long elapsedNanos = System.nanoTime() - secondStartNanos;
        long remainingNanos = TimeUnit.SECONDS.toNanos(1) - elapsedNanos;
        if (remainingNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(remainingNanos);
        }
    }

    /** Eine Zeile pro Sekunde: Soll, was in dieser Sekunde beantwortet wurde, und was noch unterwegs ist. */
    private void logSecond(int second, int target, LoadStatistics statistics,
                           long acceptedBefore, long failedBefore, Semaphore inFlight) {
        long acceptedThisSecond = statistics.acceptedCount() - acceptedBefore;
        long failedThisSecond = statistics.failedCount() - failedBefore;
        int stillInFlight = loadProperties.maxInFlight() - inFlight.availablePermits();
        log.info("second {}: target {}, accepted {}, failed {}, in flight {}",
                second, target, acceptedThisSecond, failedThisSecond, stillInFlight);
    }

    /** Die Zusammenfassung am Ende, inklusive der Verteilung auf die chat-service-Instanzen. */
    private void logSummary(LoadStatistics statistics, long elapsedNanos) {
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        long accepted = statistics.acceptedCount();
        double acceptedPerMinute = accepted / elapsedSeconds * 60;

        log.info("Load finished after {} s: accepted {}, failed {}, that is {} messages per minute",
                String.format("%.1f", elapsedSeconds), accepted, statistics.failedCount(),
                String.format("%.0f", acceptedPerMinute));

        Map<String, Long> byInstance = statistics.acceptedByInstance();
        for (Map.Entry<String, Long> entry : byInstance.entrySet()) {
            log.info("chat-service instance {} accepted {}", entry.getKey(), entry.getValue());
        }
    }
}
