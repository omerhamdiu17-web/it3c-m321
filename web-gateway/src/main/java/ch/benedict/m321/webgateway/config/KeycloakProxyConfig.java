package ch.benedict.m321.webgateway.config;

import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Function;

/**
 * Reicht die Login-Seiten von Keycloak durch (Spezifikation 2.1, PLANUNG.md 3.2).
 *
 * Der Browser läuft ausserhalb des Docker-Netzes und erreicht Keycloak nicht,
 * und Keycloak darf keinen eigenen Port haben. Also nimmt das Gateway die
 * Anfragen an und schickt sie unverändert an Keycloak im Docker-Netz weiter.
 *
 * Durchgereicht wird nur, was die Login-Seite braucht (E4). Alles andere unter
 * /auth, also Admin-Konsole, Realm master und Willkommensseite, findet hier
 * keine Route und bekommt von Spring MVC 404 (W8, F15). Es gibt keine
 * Sperrliste, die man nachführen müsste: Gesperrt ist alles, was nicht
 * ausdrücklich durchgeht.
 */
@Configuration
public class KeycloakProxyConfig {

    /** Der Realm unseres Chats: Login-Formular, Token, JWKS, Userinfo, Abmelden. */
    private static final String REALM_CHAT = "/auth/realms/chat/**";

    /** CSS, JavaScript und Bilder der Login-Seite (E4). */
    private static final String LOGIN_RESOURCES = "/auth/resources/**";

    /**
     * Die Route zu Keycloak, für genau zwei Präfixe.
     *
     * Ziel sind nur Schema, Host und Port aus KEYCLOAK_INTERNAL_URL
     * (http://keycloak:8080). Den Pfad nimmt der Proxy unverändert aus der
     * Anfrage des Browsers, denn Keycloak läuft selbst unter /auth
     * (KC_HTTP_RELATIVE_PATH in docker-compose.yml).
     */
    @Bean
    public RouterFunction<ServerResponse> keycloakRoute(GatewayProperties gatewayProperties)
            throws URISyntaxException {
        URI internalUrl = URI.create(gatewayProperties.keycloakInternalUrl());
        URI target = new URI(internalUrl.getScheme(), null, internalUrl.getHost(), internalUrl.getPort(),
                null, null, null);
        RequestPredicate realmChat = RequestPredicates.path(REALM_CHAT);
        RequestPredicate loginResources = RequestPredicates.path(LOGIN_RESOURCES);
        RequestPredicate passedThrough = realmChat.or(loginResources);
        HandlerFunction<ServerResponse> forward = HandlerFunctions.http();
        Function<ServerRequest, ServerRequest> toKeycloak = BeforeFilterFunctions.uri(target);
        return GatewayRouterFunctions.route("keycloak")
                .route(passedThrough, forward)
                .before(toKeycloak)
                .build();
    }
}
