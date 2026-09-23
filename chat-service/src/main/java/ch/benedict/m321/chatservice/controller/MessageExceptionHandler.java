package ch.benedict.m321.chatservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Übersetzt Broker- und Datenbank-Fehler in eine ehrliche HTTP-Antwort.
 *
 * Ohne diese Klasse würde Spring eine 500 zurückgeben — "unser Fehler,
 * keine Ahnung". 503 sagt dem Aufrufer, dass es sich lohnt, es später
 * erneut zu versuchen.
 */
@RestControllerAdvice
@Slf4j
public class MessageExceptionHandler {

    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<String> handleBrokerNotAvailable(AmqpException exception) {
        log.error("Broker not reachable, message rejected", exception);

        String body = "Nachricht nicht gesendet: der Broker ist nicht erreichbar.";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Die Datenbank ist weg: der Verlauf kann nicht gelesen werden.
     * Senden geht trotzdem weiter — der Sendeweg braucht die Datenbank nicht.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> handleDatabaseNotAvailable(DataAccessException exception) {
        log.error("Database not reachable, history not loaded", exception);

        String body = "Verlauf nicht verfügbar: die Datenbank ist nicht erreichbar.";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
