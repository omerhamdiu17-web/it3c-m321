package ch.benedict.m321.webgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Die Adressen aus der Umgebung, an genau einer Stelle (Spezifikation 4.1).
 *
 * Spring Boot füllt den record aus dem Abschnitt "gateway" der
 * application.yml, und dort stehen nur Umgebungsvariablen. Wer eine Adresse
 * braucht, bekommt diesen record und baut sie nicht selbst aus einer Variablen
 * zusammen. So steht jede Adresse nur einmal im Code.
 *
 * @param keycloakInternalUrl Keycloak im Docker-Netz, mit /auth am Ende
 *                            (KEYCLOAK_INTERNAL_URL, z. B. http://keycloak:8080/auth)
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String keycloakInternalUrl) {
}
