package ch.benedict.m321.webgateway.config;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen ein ECHTES Keycloak, dass /auth durchgereicht wird.
 *
 * Der Master-Realm reicht dafür: er ist in jedem Keycloak vorhanden und
 * liefert unter .well-known seine OpenID-Konfiguration. Kommt die durch
 * das Gateway an, funktioniert der Proxy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KeycloakProxyIntegrationTest {

    /** Dasselbe Image wie in docker-compose, mit demselben Pfad /auth. */
    @Container
    static KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.7.3")
            .withContextPath("/auth");

    /** Zeigt die interne Adresse des Gateways auf den Test-Container. */
    @DynamicPropertySource
    static void keycloakAddress(DynamicPropertyRegistry registry) {
        registry.add("keycloak.internal-base-url", () -> {
            String host = keycloak.getHost();
            Integer port = keycloak.getHttpPort();
            return "http://" + host + ":" + port;
        });
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void forwardsAuthRequestsToKeycloak() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/auth/realms/master/.well-known/openid-configuration", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"issuer\""));
    }
}
