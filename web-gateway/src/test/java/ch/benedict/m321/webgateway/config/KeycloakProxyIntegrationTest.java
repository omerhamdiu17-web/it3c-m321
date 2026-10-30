package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.FixedValue;
import ch.benedict.m321.webgateway.TestBrowser;
import ch.benedict.m321.webgateway.TestHttpServer;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Proxy unter /auth (Spezifikation 2.1, F15, P10) mit einem
 * Mini-Server, der Keycloak spielt. Der Mini-Server merkt sich jede Anfrage;
 * so zeigt der Test auch, was NICHT bei Keycloak ankommt.
 *
 * Das Gateway läuft auf einem zufälligen Port, wie ein echter Server: Nur so
 * gehen die Anfragen wirklich durch den Proxy und die Firewall von Spring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:gateway-test.properties")
class KeycloakProxyIntegrationTest {

    /** Discovery des Realms chat, gekürzt auf das, was der Test prüft. */
    private static final String DISCOVERY = "/auth/realms/chat/.well-known/openid-configuration";
    private static final String DISCOVERY_JSON = "{\"issuer\":\"http://localhost:8080/auth/realms/chat\"}";

    /** CSS der Login-Seite, Pfad wie in E4. */
    private static final String STYLES = "/auth/resources/j6b6d/login/keycloak.v2/css/styles.css";

    /** Ziel des Login-Formulars (E4); Keycloak antwortet mit Umleitung und Cookie. */
    private static final String AUTHENTICATE = "/auth/realms/chat/login-actions/authenticate";
    private static final String CALLBACK = "http://localhost:8080/login/oauth2/code/keycloak?state=s1&code=c1";
    private static final String KEYCLOAK_COOKIE =
            "AUTH_SESSION_ID=abc.123; Version=1; Path=/auth/realms/chat/; HttpOnly; SameSite=Lax";

    /** Spielt Keycloak. Läuft schon, bevor Spring das Gateway startet. */
    private static final TestHttpServer fakeKeycloak = startFakeKeycloak();

    @LocalServerPort
    private int port;

    /** Startet den Mini-Server mit Antworten wie in E4, gekürzt. */
    private static TestHttpServer startFakeKeycloak() {
        TestHttpServer server = TestHttpServer.start();
        Map<String, String> json = Map.of("Content-Type", "application/json");
        Map<String, String> css = Map.of("Content-Type", "text/css");
        Map<String, String> redirect = Map.of("Location", CALLBACK, "Set-Cookie", KEYCLOAK_COOKIE);
        TestHttpServer.Answer discovery = new TestHttpServer.Answer(200, json, DISCOVERY_JSON);
        TestHttpServer.Answer styles = new TestHttpServer.Answer(200, css, "body { margin: 0; }");
        TestHttpServer.Answer loggedIn = new TestHttpServer.Answer(302, redirect, "");
        server.answer(DISCOVERY, discovery);
        server.answer(STYLES, styles);
        server.answer(AUTHENTICATE, loggedIn);
        return server;
    }

    /** Das Gateway reicht an den Mini-Server weiter statt an keycloak:8080. */
    @DynamicPropertySource
    static void keycloakAddress(DynamicPropertyRegistry registry) {
        String internalUrl = fakeKeycloak.url("/auth");
        FixedValue value = new FixedValue(internalUrl);
        registry.add("KEYCLOAK_INTERNAL_URL", value);
    }

    /** Jeder Test zählt nur die Anfragen, die er selbst ausgelöst hat. */
    @BeforeEach
    void forgetEarlierRequests() {
        fakeKeycloak.clear();
    }

    /** Am Schluss den Port des Mini-Servers wieder freigeben. */
    @AfterAll
    static void stopFakeKeycloak() {
        fakeKeycloak.stop();
    }

    /** Die Discovery von Realm chat kommt unverändert durch; Keycloak sieht genau diese eine Anfrage. */
    @Test
    void passesDiscoveryOfRealmChatThrough() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(DISCOVERY);

        HttpResponse<String> response = browser.get(url);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        assertEquals(200, response.statusCode());
        assertEquals(DISCOVERY_JSON, response.body());
        assertEquals(DISCOVERY, received.path());
    }

    /** CSS, JavaScript und Bilder der Login-Seite liegen unter /auth/resources/ (E4). */
    @Test
    void passesLoginPageResourcesThrough() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(STYLES);

        HttpResponse<String> response = browser.get(url);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        assertEquals(200, response.statusCode());
        assertEquals(STYLES, received.path());
    }

    /**
     * Das Login-Formular schickt ein POST an Keycloak, ohne CSRF-Token des
     * Gateways (Spezifikation 2.1). Es kommt mit Query und Feldern an, und die
     * Umleitung zurück zum Gateway geht unverändert an den Browser.
     */
    @Test
    void passesLoginFormPostWithoutCsrfToken() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(AUTHENTICATE + "?session_code=sc1&client_id=web-gateway&tab_id=t1");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", "alice");
        fields.put("password", "alice-demo");

        HttpResponse<String> response = browser.postForm(url, fields);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        String location = TestBrowser.location(response);
        String body = received.body();
        assertEquals(302, response.statusCode());
        assertEquals(CALLBACK, location);
        assertEquals("POST", received.method());
        assertEquals("session_code=sc1&client_id=web-gateway&tab_id=t1", received.query());
        assertTrue(body.contains("username=alice"), body);
        assertTrue(body.contains("password=alice-demo"), body);
    }

    /** Cookies gehen in beide Richtungen unverändert durch (Spezifikation 3.1, Punkt 4). */
    @Test
    void passesCookiesBothWaysUnchanged() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(AUTHENTICATE);
        Map<String, String> cookie = Map.of("Cookie", "AUTH_SESSION_ID=abc.123; KC_RESTART=xyz");

        HttpResponse<String> response = browser.get(url, cookie);

        HttpHeaders headers = response.headers();
        List<String> setCookies = headers.allValues("Set-Cookie");
        TestHttpServer.Request received = onlyRequestAtKeycloak();
        Headers receivedHeaders = received.headers();
        assertEquals(List.of(KEYCLOAK_COOKIE), setCookies);
        assertEquals("AUTH_SESSION_ID=abc.123; KC_RESTART=xyz", receivedHeaders.getFirst("Cookie"));
    }

    /**
     * Admin-Konsole, Realm master und Willkommensseite beantwortet das Gateway
     * selbst mit 404; bei Keycloak kommt nichts an (W8, F15).
     */
    @Test
    void answersNotFoundForEverythingElseUnderAuth() throws Exception {
        TestBrowser browser = new TestBrowser();
        List<String> paths = List.of("/auth/admin/", "/auth/admin/master/console/",
                "/auth/realms/master/.well-known/openid-configuration", "/auth/", "/auth");

        for (String path : paths) {
            String url = gateway(path);
            HttpResponse<String> response = browser.get(url);
            assertEquals(404, response.statusCode(), path);
        }

        List<TestHttpServer.Request> received = fakeKeycloak.requests();
        assertTrue(received.isEmpty(), "bei Keycloak angekommen: " + received);
    }

    /**
     * Ein Pfad mit kodiertem ".." darf den Realm chat nicht verlassen. Der Proxy
     * gäbe ihn unverändert weiter, und Keycloak löste ihn zu /auth/admin/ auf.
     * Die Firewall von Spring Security weist ihn vorher ab.
     */
    @Test
    void dotSegmentsNeverReachKeycloak() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway("/auth/realms/chat/%2e%2e/%2e%2e/admin/");

        HttpResponse<String> response = browser.get(url);

        List<TestHttpServer.Request> received = fakeKeycloak.requests();
        assertEquals(400, response.statusCode());
        assertTrue(received.isEmpty(), "bei Keycloak angekommen: " + received);
    }

    /**
     * P10: X-Forwarded-*-Header und Forwarded aus dem Browser kommen nicht mit
     * ihrem gefälschten Wert bei Keycloak an. Keycloak wertet sie heute nicht
     * aus (kein KC_PROXY_HEADERS), aber das soll nicht davon abhängen.
     */
    @Test
    void forgedForwardedHeadersNeverReachKeycloak() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(DISCOVERY);
        Map<String, String> forged = new LinkedHashMap<>();
        forged.put("X-Forwarded-Host", "evil.example");
        forged.put("X-Forwarded-Proto", "https");
        forged.put("X-Forwarded-Port", "65000");
        forged.put("X-Forwarded-For", "203.0.113.7");
        forged.put("X-Forwarded-Prefix", "/evil");
        forged.put("Forwarded", "for=203.0.113.7;host=evil.example;proto=https");

        HttpResponse<String> response = browser.get(url, forged);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        Headers receivedHeaders = received.headers();
        assertEquals(200, response.statusCode());
        for (Map.Entry<String, String> header : forged.entrySet()) {
            String arrived = receivedHeaders.getFirst(header.getKey());
            boolean forgedValueArrived = arrived != null && arrived.contains(header.getValue());
            assertFalse(forgedValueArrived, "P10: " + header.getKey() + " kam an als " + arrived);
        }
    }

    /** Adresse eines Pfads auf dem Gateway. */
    private String gateway(String path) {
        return "http://localhost:" + port + path;
    }

    /** Genau eine Anfrage kam bei Keycloak an; der Test bekommt sie zum Prüfen. */
    private TestHttpServer.Request onlyRequestAtKeycloak() {
        List<TestHttpServer.Request> received = fakeKeycloak.requests();
        assertEquals(1, received.size(), "Anfragen bei Keycloak: " + received);
        return received.get(0);
    }
}
