package ch.benedict.m321.webgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.Map;

/**
 * Meldet Keycloak bei Spring Security als OpenID-Connect-Anbieter an.
 *
 * Warum von Hand und nicht über spring.security.oauth2.client.* in der
 * application.yml: Mit einer issuer-uri würde Spring Boot beim Start die
 * Konfiguration von Keycloak abrufen (Discovery). Der issuer ist aber die
 * öffentliche Adresse http://localhost:8080/auth, also das Gateway selbst,
 * das in diesem Moment noch gar nicht läuft. Deshalb stehen hier alle
 * Endpunkte ausdrücklich: die für den Browser mit der öffentlichen, die
 * für das Gateway mit der internen Adresse.
 */
@Configuration
public class KeycloakClientConfig {

    /** Kennung dieser Registrierung; taucht in den Spring-Pfaden auf, z.B. /login/oauth2/code/keycloak. */
    public static final String REGISTRATION_ID = "keycloak";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(KeycloakProperties keycloakProperties) {
        String publicRealmUrl = keycloakProperties.publicRealmUrl();
        String internalRealmUrl = keycloakProperties.internalRealmUrl();

        // Der Abmelde-Endpunkt ist kein Feld der Registrierung, sondern
        // steckt in den Metadaten des Anbieters. Der Browser ruft ihn auf,
        // deshalb die öffentliche Adresse.
        Map<String, Object> providerMetadata = Map.of(
                "end_session_endpoint", publicRealmUrl + "/protocol/openid-connect/logout");

        // PKCE ist im Realm Pflicht (S256). Spring macht es bei einem Client
        // mit Secret nur, wenn man es ausdrücklich verlangt.
        ClientRegistration.ClientSettings clientSettings = ClientRegistration.ClientSettings.builder()
                .requireProofKey(true)
                .build();

        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Keycloak")
                .clientId(keycloakProperties.clientId())
                .clientSecret(keycloakProperties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                // Browser-Seite: Login-Formular über das Gateway
                .authorizationUri(publicRealmUrl + "/protocol/openid-connect/auth")
                // Gateway-Seite: Code tauschen, Schlüssel und Profil holen
                .tokenUri(internalRealmUrl + "/protocol/openid-connect/token")
                .jwkSetUri(internalRealmUrl + "/protocol/openid-connect/certs")
                .userInfoUri(internalRealmUrl + "/protocol/openid-connect/userinfo")
                .userNameAttributeName("preferred_username")
                // Gegen diesen Wert prüft Spring den iss-Claim jedes Tokens.
                .issuerUri(publicRealmUrl)
                .providerConfigurationMetadata(providerMetadata)
                .clientSettings(clientSettings)
                .build();

        return new InMemoryClientRegistrationRepository(registration);
    }
}
