package ch.benedict.m321.webgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Die Zugriffsregeln des Gateways: der einzige Wachposten im System.
 *
 * Alles ausser /auth verlangt einen angemeldeten Benutzer. Wer nicht
 * angemeldet ist, wird zu Keycloak geschickt (PLANUNG.md, Abschnitt 3.3).
 * Die inneren Dienste prüfen nichts mehr, sie sind von aussen nicht erreichbar.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {

        // /auth ist der Proxy auf Keycloak. Dort MUSS man ohne Anmeldung hin,
        // sonst könnte sich niemand anmelden.
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/auth/**").permitAll()
                .anyRequest().authenticated());

        // Das Login-Formular von Keycloak schickt ein POST durch den Proxy.
        // Das ist kein Formular des Gateways, also kein CSRF-Token dafür.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/auth/**"));

        // Login als OpenID-Connect-Client; die Registrierung "keycloak"
        // kommt aus KeycloakClientConfig.
        http.oauth2Login(Customizer.withDefaults());

        // Abmelden per einfachem Link (GET). Spring empfiehlt POST mit
        // CSRF-Token; wir nehmen den Link, weil die Oberfläche dann kein
        // Token verwalten muss. Für ein Schulprojekt ist das vertretbar.
        // Nach dem Abmelden im Gateway wird auch die Sitzung bei Keycloak
        // beendet, sonst wäre man beim nächsten Klick sofort wieder drin.
        OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");
        RequestMatcher logoutLink = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/logout");
        http.logout(logout -> logout
                .logoutRequestMatcher(logoutLink)
                .logoutSuccessHandler(logoutSuccessHandler));

        return http.build();
    }
}
