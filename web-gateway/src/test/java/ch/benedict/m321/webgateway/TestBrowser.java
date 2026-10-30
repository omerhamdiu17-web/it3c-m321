package ch.benedict.m321.webgateway;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein Browser für Tests, gebaut aus dem HTTP-Client des JDK.
 *
 * Er merkt sich Cookies wie ein Browser, folgt Umleitungen aber nur, wenn der
 * Test follow() aufruft. So kann ein Test jede einzelne Antwort prüfen, auch
 * die Umleitungen dazwischen (Spezifikation 3.1). Jeder neue TestBrowser
 * beginnt ohne Cookies, wie ein frisches privates Fenster.
 *
 * Wie ein echter Browser schickt er Cookies mit "Secure" auch an
 * http://localhost (siehe LocalhostCookieHandler unten).
 */
public final class TestBrowser {

    /** Ziel des ersten Formulars einer Seite, z. B. des Login-Formulars von Keycloak. */
    private static final Pattern FORM_ACTION = Pattern.compile("action=\"([^\"]*)\"");

    /** Länger wartet kein Test auf eine einzelne Antwort. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Mehr Umleitungen hintereinander deuten auf eine Schleife. */
    private static final int MAX_REDIRECTS = 10;

    private final CookieManager cookieManager;
    private final HttpClient client;

    /** Ein Browser ohne Cookies, der Umleitungen nicht von selbst folgt. */
    public TestBrowser() {
        cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        LocalhostCookieHandler cookieHandler = new LocalhostCookieHandler(cookieManager);
        client = HttpClient.newBuilder()
                .cookieHandler(cookieHandler)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /** GET, wie beim Eintippen einer Adresse. */
    public HttpResponse<String> get(String url) throws IOException, InterruptedException {
        Map<String, String> noHeaders = Map.of();
        return get(url, noHeaders);
    }

    /** GET mit zusätzlichen Headern, z. B. Authorization, Cookie oder X-Forwarded-Host. */
    public HttpResponse<String> get(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        URI address = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(address);
        builder.timeout(TIMEOUT);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        builder.GET();
        HttpRequest request = builder.build();
        return send(request);
    }

    /** POST eines Formulars, wie nach einem Klick auf «Anmelden». */
    public HttpResponse<String> postForm(String url, Map<String, String> fields)
            throws IOException, InterruptedException {
        URI address = URI.create(url);
        String body = formBody(fields);
        HttpRequest.BodyPublisher content = HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(address)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(content)
                .build();
        return send(request);
    }

    /** Folgt Umleitungen, bis eine Antwort keine Umleitung mehr ist. */
    public HttpResponse<String> follow(HttpResponse<String> response) throws IOException, InterruptedException {
        HttpResponse<String> current = response;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            String target = location(current);
            if (target.isEmpty()) {
                return current;
            }
            current = get(target);
        }
        URI start = response.uri();
        throw new IllegalStateException("Mehr als " + MAX_REDIRECTS + " Umleitungen ab " + start);
    }

    /** Namen der Cookies mit dem Pfad "/" (Spezifikation W3: dort steht nur JSESSIONID). */
    public List<String> cookieNamesForRootPath() {
        CookieStore store = cookieManager.getCookieStore();
        List<HttpCookie> cookies = store.getCookies();
        List<String> names = new ArrayList<>();
        for (HttpCookie cookie : cookies) {
            String path = cookie.getPath();
            if ("/".equals(path)) {
                names.add(cookie.getName());
            }
        }
        return names;
    }

    /** Wert eines gespeicherten Cookies, oder null, wenn es keines mit diesem Namen gibt. */
    public String cookieValue(String name) {
        CookieStore store = cookieManager.getCookieStore();
        List<HttpCookie> cookies = store.getCookies();
        for (HttpCookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Ziel einer Umleitung als vollständige Adresse; leer, wenn die Antwort keine
     * Umleitung ist. Tomcat schreibt Ziele wie "/" ohne Host, deshalb wird gegen
     * die Adresse der Anfrage aufgelöst.
     */
    public static String location(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 300 || status > 399) {
            return "";
        }
        HttpHeaders headers = response.headers();
        Optional<String> location = headers.firstValue("Location");
        if (location.isEmpty()) {
            return "";
        }
        URI base = response.uri();
        URI target = base.resolve(location.get());
        return target.toString();
    }

    /** Ziel des ersten Formulars einer Seite; im HTML steht "&" als "&amp;". */
    public static String formAction(String html) {
        Matcher matcher = FORM_ACTION.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("Kein Formular auf der Seite");
        }
        String action = matcher.group(1);
        return action.replace("&amp;", "&");
    }

    /** Schickt die Anfrage und liest die Antwort als Text. */
    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse.BodyHandler<String> asText = HttpResponse.BodyHandlers.ofString();
        return client.send(request, asText);
    }

    /** Baut "name=wert&name=wert", jeder Teil URL-kodiert wie in einem Browser. */
    private static String formBody(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            String name = URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8);
            String value = URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8);
            body.append(name);
            body.append('=');
            body.append(value);
        }
        return body.toString();
    }

    /**
     * Schickt Cookies mit "Secure" auch an http://localhost, wie es Browser tun.
     *
     * Browser behandeln localhost als sichere Umgebung («secure context»).
     * Keycloak tut das auch: Fragt man es unter localhost an, setzt es seine
     * Login-Cookies mit "Secure; SameSite=None", unter keycloak:8080 dagegen mit
     * "SameSite=Lax" (notizen.md, Task 3). Der CookieManager des JDK schickt
     * Secure-Cookies nur über https. Für localhost fragt ihn diese Klasse
     * deshalb mit der https-Adresse; gespeichert wird unverändert.
     */
    private static final class LocalhostCookieHandler extends CookieHandler {

        private final CookieManager cookieManager;

        /** Arbeitet mit dem CookieManager des TestBrowser, damit beide dieselben Cookies sehen. */
        private LocalhostCookieHandler(CookieManager cookieManager) {
            this.cookieManager = cookieManager;
        }

        /** Welche Cookies eine Anfrage mitnimmt; für http://localhost auch die mit "Secure". */
        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders)
                throws IOException {
            URI lookup = uri;
            boolean httpToLocalhost = "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost());
            if (httpToLocalhost) {
                String address = uri.toString();
                String asHttps = "https" + address.substring("http".length());
                lookup = URI.create(asHttps);
            }
            return cookieManager.get(lookup, requestHeaders);
        }

        /** Speichert die Cookies einer Antwort, ohne etwas daran zu ändern. */
        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
            cookieManager.put(uri, responseHeaders);
        }
    }
}
