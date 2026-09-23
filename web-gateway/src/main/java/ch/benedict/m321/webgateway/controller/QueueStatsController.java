package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.dto.QueueStats;
import ch.benedict.m321.webgateway.service.LoggedInUser;
import ch.benedict.m321.webgateway.service.QueueStatsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/**
 * Die Queue-Tiefe für den Balken in der Oberfläche. Nur für die Rolle admin
 * (PLANUNG.md, offener Punkt 6).
 *
 * Die Rolle prüft das Gateway selbst. Dass die Oberfläche den Balken bei
 * anderen Benutzern gar nicht erst zeigt, ist Bequemlichkeit, kein Schutz:
 * den Browser kann jeder verändern.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class QueueStatsController {

    private final QueueStatsClient queueStatsClient;

    /** Liefert die Zahlen der Queue chat.persist, oder 403 für alle ausser admin. */
    @GetMapping("/api/admin/queue")
    public ResponseEntity<QueueStats> persistQueueStats(@AuthenticationPrincipal OidcUser user) {
        LoggedInUser loggedInUser = LoggedInUser.fromClaims(user);
        if (!loggedInUser.admin()) {
            log.warn("User {} without role {} asked for queue stats", loggedInUser.username(), LoggedInUser.ADMIN_ROLE);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        QueueStats queueStats = queueStatsClient.loadPersistQueueStats();
        return ResponseEntity.ok(queueStats);
    }

    /** RabbitMQ oder seine Management-API ist nicht erreichbar: ehrliches 503 statt 500. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<String> handleManagementApiNotAvailable(RestClientException exception) {
        log.warn("RabbitMQ management API did not answer", exception);

        String body = "Queue-Tiefe nicht verfügbar.";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
