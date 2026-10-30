package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.TestBrowser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P11: Was der Browser sieht, wenn Keycloak nicht erreichbar ist (F1), zum
 * Beispiel weil der Container gerade neu startet. gateway-test.properties
 * zeigt mit KEYCLOAK_INTERNAL_URL auf localhost:9, wo niemand zuhört.
 *
 * Eigene Klasse neben KeycloakProxyIntegrationTest: Hier darf das Gateway
 * keinen Mini-Server als Keycloak haben, und Spring baut dafür einen eigenen
 * Kontext. So hängt kein Test von der Reihenfolge der anderen ab.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:gateway-test.properties")
class KeycloakProxyDownIntegrationTest {

    @LocalServerPort
    private int port;

    /** Innert 5 s eine Antwort mit einem Status 5xx, keine hängende Seite. */
    @Test
    void answersServerErrorWithinFiveSeconds() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = "http://localhost:" + port + "/auth/realms/chat/.well-known/openid-configuration";
        long start = System.nanoTime();

        HttpResponse<String> response = browser.get(url);

        long end = System.nanoTime();
        long millis = (end - start) / 1_000_000;
        int status = response.statusCode();
        assertTrue(status >= 500 && status <= 599, "P11: Status " + status);
        assertTrue(millis < 5000, "P11: Antwort nach " + millis + " ms");
    }
}
