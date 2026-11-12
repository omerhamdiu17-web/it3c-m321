package ch.benedict.m321.chatservice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Ein echter SSE-Client für die Tests: öffnet GET /stream über HTTP und liest
 * die Antwort Zeile für Zeile mit, genau wie curl -N in der Abnahme.
 *
 * Er ist zugleich BodyHandler (bekommt Status und Header) und Flow.Subscriber
 * (bekommt jede Zeile). So kommen wir ohne Java-Streams aus (CLAUDE.md).
 */
public final class SseTestClient implements BodyHandler<Void>, Flow.Subscriber<String> {

    /** Alle Werte der "id:"-Zeilen, in der Reihenfolge des Empfangs. */
    private final List<String> ids = new CopyOnWriteArrayList<>();

    /** Alle Werte der "data:"-Zeilen, in der Reihenfolge des Empfangs. */
    private final List<String> data = new CopyOnWriteArrayList<>();

    /** Wird geöffnet, sobald Status und Header da sind, also die Verbindung steht. */
    private final CountDownLatch connected = new CountDownLatch(1);

    /** Der HTTP-Status der Antwort, z. B. 200. */
    private volatile int statusCode;

    /** Der Content-Type der Antwort, z. B. "text/event-stream". */
    private volatile String contentType;

    /** Damit lässt sich die Verbindung von unserer Seite schliessen. */
    private volatile Flow.Subscription subscription;

    /** Nur über connect erzeugen. */
    private SseTestClient() {
    }

    /** Öffnet einen Raum ohne Last-Event-ID und wartet, bis die Verbindung steht. */
    public static SseTestClient connect(int port, UUID roomId) throws Exception {
        return connect(port, roomId, null);
    }

    /**
     * Öffnet einen Raum und wartet höchstens 5 Sekunden, bis die Verbindung
     * steht. lastEventId darf null sein; sonst geht sie als Header mit.
     */
    public static SseTestClient connect(int port, UUID roomId, String lastEventId) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = requestBuilder.build();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        SseTestClient client = new SseTestClient();
        httpClient.sendAsync(request, client);
        boolean isConnected = client.connected.await(5, TimeUnit.SECONDS);
        if (!isConnected) {
            throw new IllegalStateException("Keine Antwort von " + uri);
        }
        return client;
    }

    /** Status und Header sind da: merken und einen Zeilenleser zurückgeben, der an uns liefert. */
    @Override
    public BodySubscriber<Void> apply(ResponseInfo responseInfo) {
        statusCode = responseInfo.statusCode();
        contentType = responseInfo.headers().firstValue("Content-Type").orElse("");
        connected.countDown();
        return BodySubscribers.fromLineSubscriber(this);
    }

    /** Die Verbindung liefert ab jetzt Zeilen. Wir wollen alle, ohne Grenze. */
    @Override
    public void onSubscribe(Flow.Subscription newSubscription) {
        subscription = newSubscription;
        newSubscription.request(Long.MAX_VALUE);
    }

    /** Eine Zeile kam an: "id:" und "data:" merken wir uns, den Rest nicht. */
    @Override
    public void onNext(String line) {
        if (line.startsWith("id:")) {
            String id = line.substring(3);
            ids.add(id);
        }
        if (line.startsWith("data:")) {
            String json = line.substring(5);
            data.add(json);
        }
    }

    /** Die Verbindung ist mit einem Fehler zu Ende, z. B. weil die Instanz weg ist. */
    @Override
    public void onError(Throwable throwable) {
        connected.countDown();
    }

    /** Die Verbindung ist normal zu Ende. */
    @Override
    public void onComplete() {
        connected.countDown();
    }

    /** Schliesst die Verbindung von der Client-Seite, wie ein Netzabbruch. */
    public void close() {
        if (subscription != null) {
            subscription.cancel();
        }
    }

    /** Wartet höchstens timeoutMilliseconds, bis mindestens expectedCount IDs da sind. */
    public void waitForIds(int expectedCount, long timeoutMilliseconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMilliseconds;
        while (ids.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /** Eine Kopie der bisher empfangenen IDs. */
    public List<String> ids() {
        return new ArrayList<>(ids);
    }

    /** Eine Kopie der bisher empfangenen data-Zeilen. */
    public List<String> data() {
        return new ArrayList<>(data);
    }

    /** Der HTTP-Status der Antwort. */
    public int statusCode() {
        return statusCode;
    }

    /** Der Content-Type der Antwort. */
    public String contentType() {
        return contentType;
    }
}
