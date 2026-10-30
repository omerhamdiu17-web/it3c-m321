package ch.benedict.m321.webgateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die ECHTE Realm-Datei keycloak/realm-chat.json in einem echten Keycloak
 * (Spezifikation 2.6 und 4.2, offene Prüfungen P2, P3, P4 und P7).
 *
 * Ohne Gateway: Der Test spricht Keycloak direkt über den Port an, den
 * Testcontainers nach aussen legt. Keycloak bekommt aber dieselbe öffentliche
 * Adresse wie im Betrieb (http://localhost:8080). Seiten und Umleitungen zeigen
 * deshalb dorthin; wo der Test einer solchen Adresse folgen muss (Ziel des
 * Login-Formulars), setzt er die Adresse des Containers ein.
 */
@Testcontainers
class RealmImportIntegrationTest {

    /** Öffentliche Adresse wie in docker-compose.yml. */
    private static final String PUBLIC_URL = "http://localhost:8080";

    /** So steht der Issuer in jedem Token (E3). */
    private static final String ISSUER = PUBLIC_URL + "/auth/realms/chat";

    /** Die einzige Redirect-URI des Clients web-gateway. */
    private static final String GATEWAY_REDIRECT = PUBLIC_URL + "/login/oauth2/code/keycloak";

    /** Rückleitung des Desktop-Clients, mit dem Port aus der Abnahme (Spezifikation 6.1). */
    private static final String DESKTOP_REDIRECT = "http://127.0.0.1:53682/callback";

    /** Dieses Passwort bekommt bob über die Umgebung statt des Beispielwerts. */
    private static final String BOB_PASSWORD = "bob-aus-dem-test";

    /** Feste IDs aus der Realm-Datei (Spezifikation 4.2). */
    private static final String ALICE_ID = "a11ce000-0000-4000-8000-000000000001";
    private static final String BOB_ID = "b0b00000-0000-4000-8000-000000000002";
    private static final String ADMIN_ID = "ad000000-0000-4000-8000-000000000003";

    /** Keycloak mit der echten Realm-Datei. */
    @Container
    static GenericContainer<?> keycloak = createKeycloak();

    /**
     * Baut den Container. Nur DEMO_PASSWORD_BOB ist gesetzt: bob zeigt, dass
     * Keycloak die Variable einsetzt, alice und admin zeigen, dass ohne Variable
     * der Beispielwert nach dem Doppelpunkt gilt (Spezifikation 2.6, E1).
     */
    private static GenericContainer<?> createKeycloak() {
        GenericContainer<?> container = TestKeycloak.createContainer(PUBLIC_URL);
        container.withEnv("DEMO_PASSWORD_BOB", BOB_PASSWORD);
        return container;
    }

    /** Die Discovery nennt die öffentliche Adresse als Issuer, nicht die des Containers (E3). */
    @Test
    void discoveryNamesPublicIssuer() throws Exception {
        TestBrowser browser = new TestBrowser();
        String keycloakUrl = TestKeycloak.url(keycloak);

        HttpResponse<String> discovery = browser.get(keycloakUrl + "/realms/chat/.well-known/openid-configuration");

        String body = discovery.body();
        assertEquals(200, discovery.statusCode());
        assertTrue(body.contains("\"issuer\":\"" + ISSUER + "\""), body);
    }

    /**
     * alice meldet sich mit dem Beispielwert ihres Passworts an. Beide Tokens
     * tragen ihre feste id als sub (P4), Name, E-Mail und genau die Rolle user
     * aus dem Mapper roles; das Access-Token ist für den Client web-gateway.
     */
    @Test
    void aliceGetsFixedIdNameEmailAndRoles() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("alice", "alice-demo");

        JsonNode idToken = KeycloakFlow.claimsOf(tokens, "id_token");
        JsonNode accessToken = KeycloakFlow.claimsOf(tokens, "access_token");
        String fromIdToken = identityOf(idToken);
        String fromAccessToken = identityOf(accessToken);
        String issuer = text(accessToken, "iss");
        String authorizedParty = text(accessToken, "azp");
        String expected = ALICE_ID + "|alice|Alice Muster|alice@example.org|[user]";
        assertEquals(expected, fromIdToken, "ID-Token");
        assertEquals(expected, fromAccessToken, "Access-Token");
        assertEquals(ISSUER, issuer);
        assertEquals("web-gateway", authorizedParty);
    }

    /** bob hat sein Passwort aus der Umgebung; der Beispielwert gilt dann nicht mehr. */
    @Test
    void bobUsesPasswordFromEnvironment() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("bob", BOB_PASSWORD);
        TestBrowser browser = new TestBrowser();
        String challenge = newChallenge();

        HttpResponse<String> withExample = submitLoginForm(browser, "web-gateway", GATEWAY_REDIRECT, challenge,
                "bob", "bob-demo");

        JsonNode idToken = KeycloakFlow.claimsOf(tokens, "id_token");
        String identity = identityOf(idToken);
        String page = withExample.body();
        assertEquals(BOB_ID + "|bob|Bob Muster|bob@example.org|[user]", identity);
        assertEquals(200, withExample.statusCode(), "falsches Passwort: das Formular kommt wieder");
        assertTrue(page.contains("kc-form-login"));
    }

    /** admin hat zusätzlich die Rolle admin und ist sonst ein Chat-Benutzer wie die anderen. */
    @Test
    void adminHasRolesAdminAndUser() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("admin", "admin-demo");

        JsonNode accessToken = KeycloakFlow.claimsOf(tokens, "access_token");
        String identity = identityOf(accessToken);
        assertEquals(ADMIN_ID + "|admin|Ada Admin|admin@example.org|[admin, user]", identity);
    }

    /**
     * Das Secret kommt aus der Umgebung und steht nicht wörtlich in der Datei:
     * Der Platzhalter selbst als Secret wird abgelehnt (E1).
     */
    @Test
    void placeholderIsNotTheSecret() throws Exception {
        TestBrowser browser = new TestBrowser();
        String verifier = KeycloakFlow.newVerifier();
        String challenge = KeycloakFlow.challengeFor(verifier);
        String code = logInAndGetCode(browser, "web-gateway", GATEWAY_REDIRECT, challenge, "alice", "alice-demo");
        String keycloakUrl = TestKeycloak.url(keycloak);

        HttpResponse<String> answer = KeycloakFlow.redeemCode(browser, keycloakUrl, "web-gateway",
                "${KEYCLOAK_CLIENT_SECRET}", GATEWAY_REDIRECT, code, verifier);

        String body = answer.body();
        assertEquals(401, answer.statusCode(), body);
        assertTrue(body.contains("unauthorized_client"), body);
    }

    /** Ohne code_challenge gibt es für keinen der beiden Clients ein Login-Formular (2.6, W7). */
    @Test
    void loginWithoutPkceIsRejected() throws Exception {
        assertNoLoginForm("web-gateway", GATEWAY_REDIRECT, null);
        assertNoLoginForm("desktop-client", DESKTOP_REDIRECT, null);
    }

    /** Nur die registrierte Redirect-URI gilt; ein anderer Port oder Host bekommt kein Formular (E1). */
    @Test
    void onlyRegisteredRedirectUrisAreAccepted() throws Exception {
        String challenge = newChallenge();

        assertNoLoginForm("web-gateway", "http://localhost:9999/login/oauth2/code/keycloak", challenge);
        assertNoLoginForm("desktop-client", "http://evil.example/callback", challenge);
    }

    /** P2: Beim desktop-client gilt jeder Port auf 127.0.0.1 (RFC 8252, Abschnitt 7.3). */
    @Test
    void desktopClientAcceptsAnyLoopbackPort() throws Exception {
        assertLoginForm("desktop-client", "http://127.0.0.1:53682/callback");
        assertLoginForm("desktop-client", "http://127.0.0.1:61234/callback");
    }

    /** Ein Token für den desktop-client trägt dessen Namen in azp; genau das prüft das Gateway (Task 7). */
    @Test
    void desktopClientGetsTokenForItself() throws Exception {
        TestBrowser browser = new TestBrowser();
        String verifier = KeycloakFlow.newVerifier();
        String challenge = KeycloakFlow.challengeFor(verifier);
        String code = logInAndGetCode(browser, "desktop-client", DESKTOP_REDIRECT, challenge, "alice", "alice-demo");
        String keycloakUrl = TestKeycloak.url(keycloak);

        HttpResponse<String> tokens = KeycloakFlow.redeemCode(browser, keycloakUrl, "desktop-client", null,
                DESKTOP_REDIRECT, code, verifier);

        assertEquals(200, tokens.statusCode(), tokens.body());
        JsonNode accessToken = KeycloakFlow.claimsOf(tokens, "access_token");
        String identity = identityOf(accessToken);
        String issuer = text(accessToken, "iss");
        String authorizedParty = text(accessToken, "azp");
        assertEquals(ALICE_ID + "|alice|Alice Muster|alice@example.org|[user]", identity);
        assertEquals(ISSUER, issuer);
        assertEquals("desktop-client", authorizedParty);
    }

    /**
     * P3: Auch in der Post-Logout-URI ersetzt Keycloak ${PUBLIC_URL}. Mit dem
     * ID-Token als Hinweis meldet es ab und leitet genau dorthin zurück; eine
     * andere Adresse lehnt es ab, ohne abzumelden.
     */
    @Test
    void postLogoutRedirectUsesPublicUrl() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("alice", "alice-demo");
        String idToken = KeycloakFlow.tokenOf(tokens, "id_token");
        String wrongTarget = logoutUrl(idToken, "http://localhost:9999/");
        String rightTarget = logoutUrl(idToken, PUBLIC_URL + "/");
        TestBrowser browser = new TestBrowser();

        HttpResponse<String> wrong = browser.get(wrongTarget);
        HttpResponse<String> right = browser.get(rightTarget);

        String location = TestBrowser.location(right);
        assertEquals(400, wrong.statusCode(), "fremde Post-Logout-URI");
        assertEquals(302, right.statusCode(), right.body());
        assertEquals(PUBLIC_URL + "/", location, "P3");
    }

    /** Unsere Clients kennen keinen Password-Grant (PLANUNG.md 3.2), Keycloak lehnt ab. */
    @Test
    void ourClientsHaveNoPasswordGrant() throws Exception {
        HttpResponse<String> gateway = passwordGrant("web-gateway", TestKeycloak.CLIENT_SECRET);
        HttpResponse<String> desktop = passwordGrant("desktop-client", null);

        assertEquals(400, gateway.statusCode(), gateway.body());
        assertEquals(400, desktop.statusCode(), desktop.body());
    }

    /**
     * P7: admin-cli legt Keycloak in jedem Realm selbst an, mit Password-Grant.
     * Das Token trägt dann azp=admin-cli; genau daran lehnt das Gateway es ab
     * (Task 7, W7). Lehnt Keycloak schon den Grant ab, ist P7 anders entschieden.
     */
    @Test
    void adminCliPasswordGrantGivesTokenForAdminCli() throws Exception {
        HttpResponse<String> answer = passwordGrant("admin-cli", null);

        assertEquals(200, answer.statusCode(), "P7: " + answer.body());
        JsonNode accessToken = KeycloakFlow.claimsOf(answer, "access_token");
        String authorizedParty = text(accessToken, "azp");
        assertEquals("admin-cli", authorizedParty);
    }

    /** Ganzer Login über den Client web-gateway, wie ihn das Gateway macht; gibt die Antwort mit den Tokens zurück. */
    private HttpResponse<String> logInAsWebGateway(String username, String password) throws Exception {
        TestBrowser browser = new TestBrowser();
        String verifier = KeycloakFlow.newVerifier();
        String challenge = KeycloakFlow.challengeFor(verifier);
        String code = logInAndGetCode(browser, "web-gateway", GATEWAY_REDIRECT, challenge, username, password);
        String keycloakUrl = TestKeycloak.url(keycloak);
        HttpResponse<String> tokens = KeycloakFlow.redeemCode(browser, keycloakUrl, "web-gateway",
                TestKeycloak.CLIENT_SECRET, GATEWAY_REDIRECT, code, verifier);
        assertEquals(200, tokens.statusCode(), tokens.body());
        return tokens;
    }

    /** Meldet sich an und liest den Code aus der Umleitung zum Client. */
    private String logInAndGetCode(TestBrowser browser, String clientId, String redirectUri, String challenge,
                                   String username, String password) throws Exception {
        HttpResponse<String> answer = submitLoginForm(browser, clientId, redirectUri, challenge, username, password);
        assertEquals(302, answer.statusCode(), "Anmeldung von " + username + " abgelehnt");
        String location = TestBrowser.location(answer);
        return KeycloakFlow.codeFrom(location);
    }

    /**
     * Holt das Login-Formular und schickt Benutzer und Passwort ab. Das Ziel des
     * Formulars nennt die öffentliche Adresse (E4); der Test setzt die des
     * Containers ein. Die Cookies von Keycloak gehen dabei mit.
     */
    private HttpResponse<String> submitLoginForm(TestBrowser browser, String clientId, String redirectUri,
                                                 String challenge, String username, String password)
            throws Exception {
        String keycloakUrl = TestKeycloak.url(keycloak);
        String url = KeycloakFlow.authorizationUrl(keycloakUrl, clientId, redirectUri, challenge);
        HttpResponse<String> form = browser.get(url);
        assertEquals(200, form.statusCode(), "Login-Formular erwartet: " + url);
        String publicAction = TestBrowser.formAction(form.body());
        String action = publicAction.replace(PUBLIC_URL + "/auth", keycloakUrl);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", username);
        fields.put("password", password);
        return browser.postForm(action, fields);
    }

    /** Keycloak zeigt für diese Anfrage mit PKCE das Login-Formular. */
    private void assertLoginForm(String clientId, String redirectUri) throws Exception {
        TestBrowser browser = new TestBrowser();
        String challenge = newChallenge();
        String keycloakUrl = TestKeycloak.url(keycloak);
        String url = KeycloakFlow.authorizationUrl(keycloakUrl, clientId, redirectUri, challenge);

        HttpResponse<String> answer = browser.get(url);

        String page = answer.body();
        assertEquals(200, answer.statusCode(), redirectUri);
        assertTrue(page.contains("kc-form-login"), redirectUri);
    }

    /**
     * Keycloak lehnt die Login-Anfrage ab: Status 400 oder eine Umleitung mit
     * error=invalid_request, und auf keinen Fall das Login-Formular (W7 d).
     */
    private void assertNoLoginForm(String clientId, String redirectUri, String challenge) throws Exception {
        TestBrowser browser = new TestBrowser();
        String keycloakUrl = TestKeycloak.url(keycloak);
        String url = KeycloakFlow.authorizationUrl(keycloakUrl, clientId, redirectUri, challenge);

        HttpResponse<String> answer = browser.get(url);

        int status = answer.statusCode();
        String location = TestBrowser.location(answer);
        String page = answer.body();
        boolean refused = status == 400 || location.contains("error=invalid_request");
        assertTrue(refused, clientId + ": Status " + status + ", Ziel " + location);
        assertFalse(page.contains("kc-form-login"), clientId + ": Login-Formular trotz Ablehnung");
    }

    /** Password-Grant für alice, so wie ihn PLANUNG.md 3.2 für unsere Anwendung verwirft. */
    private HttpResponse<String> passwordGrant(String clientId, String clientSecret) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "password");
        fields.put("client_id", clientId);
        if (clientSecret != null) {
            fields.put("client_secret", clientSecret);
        }
        fields.put("username", "alice");
        fields.put("password", "alice-demo");
        String keycloakUrl = TestKeycloak.url(keycloak);
        TestBrowser browser = new TestBrowser();
        return browser.postForm(keycloakUrl + "/realms/chat/protocol/openid-connect/token", fields);
    }

    /** Abmelde-Adresse von Keycloak mit dem ID-Token als Hinweis und dem Ziel danach. */
    private static String logoutUrl(String idToken, String postLogoutRedirectUri) {
        String keycloakUrl = TestKeycloak.url(keycloak);
        String encodedTarget = URLEncoder.encode(postLogoutRedirectUri, StandardCharsets.UTF_8);
        return keycloakUrl + "/realms/chat/protocol/openid-connect/logout"
                + "?id_token_hint=" + idToken
                + "&post_logout_redirect_uri=" + encodedTarget;
    }

    /** Eine gültige code_challenge, wenn der Test den Code danach nicht einlöst. */
    private static String newChallenge() throws Exception {
        String verifier = KeycloakFlow.newVerifier();
        return KeycloakFlow.challengeFor(verifier);
    }

    /** sub, Benutzername, Name, E-Mail und Rollen eines Tokens in einer Zeile. */
    private static String identityOf(JsonNode claims) {
        String subject = text(claims, "sub");
        String username = text(claims, "preferred_username");
        String name = text(claims, "name");
        String email = text(claims, "email");
        List<String> roles = rolesOf(claims);
        return subject + "|" + username + "|" + name + "|" + email + "|" + roles;
    }

    /** Die Werte des Claims roles, sortiert; die Reihenfolge legt Keycloak nicht fest. */
    private static List<String> rolesOf(JsonNode claims) {
        List<String> roles = new ArrayList<>();
        JsonNode values = claims.path("roles");
        for (JsonNode value : values) {
            roles.add(value.asText());
        }
        Collections.sort(roles);
        return roles;
    }

    /** Ein Claim als Text; fehlt er, ein leerer Text. */
    private static String text(JsonNode claims, String name) {
        JsonNode value = claims.path(name);
        return value.asText();
    }
}
