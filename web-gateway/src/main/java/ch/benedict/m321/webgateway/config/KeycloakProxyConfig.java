package ch.benedict.m321.webgateway.config;

import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Reicht alles unter /auth an Keycloak durch.
 *
 * Der Browser läuft ausserhalb des Docker-Netzes und kann Keycloak nicht
 * direkt erreichen, Keycloak darf aber keinen eigenen Port haben. Also
 * nimmt das Gateway die Anfrage an und schickt sie unverändert weiter.
 * Die X-Forwarded-Header setzt das Gateway selbst, damit Keycloak weiss,
 * unter welcher äusseren Adresse es angesprochen wurde.
 */
@Configuration
public class KeycloakProxyConfig {

    /**
     * Eine einzige Route: Pfad /auth/** geht per HTTP an die interne
     * Keycloak-Adresse. Der Pfad bleibt dabei erhalten, weil Keycloak
     * selbst unter /auth läuft (KC_HTTP_RELATIVE_PATH in docker-compose).
     */
    @Bean
    public RouterFunction<ServerResponse> keycloakRoute(KeycloakProperties keycloakProperties) {
        return GatewayRouterFunctions.route("keycloak")
                .route(RequestPredicates.path("/auth/**"), HandlerFunctions.http())
                .before(BeforeFilterFunctions.uri(keycloakProperties.internalBaseUrl()))
                .build();
    }
}
