package ch.benedict.m321.webgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Die Zugriffsregeln des Gateways, des einzigen Wachpostens im System
 * (PLANUNG.md 3.1, Spezifikation 2.1).
 *
 * Erste Fassung: Frei ist nur der Proxy unter /auth und die Fehlerseite. Alles
 * andere verlangt eine Anmeldung; die gibt es aber erst mit dem Login über
 * Keycloak (Task 6). Bis dahin bekommt jeder ohne Anmeldung 403.
 *
 * Lambdas stehen in diesem Projekt nur hier: So konfiguriert man Spring
 * Security seit Version 6.1, die alte Schreibweise mit and() fällt in 7.0 weg.
 */
@Configuration
public class SecurityConfig {

    /**
     * Baut die Kette von Sicherheitsfiltern, durch die jede Anfrage läuft.
     *
     * /auth und /auth/** sind frei, sonst könnte sich niemand anmelden. /auth
     * steht ausdrücklich da, damit auch die Wurzel ohne Schrägstrich ihr 404
     * bekommt. /error ist frei, damit eine Fehlerseite nicht selbst zur
     * Anmeldung führt (sonst sähe W8 kein 404).
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/auth", "/auth/**", "/error").permitAll()
                .anyRequest().authenticated());

        // Das Login-Formular von Keycloak schickt ein POST durch den Proxy. Ein
        // CSRF-Token des Gateways kennt Keycloak nicht; es schützt sein Formular
        // selbst (session_code, tab_id und eigene Cookies, E4).
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/auth/**"));

        return http.build();
    }
}
