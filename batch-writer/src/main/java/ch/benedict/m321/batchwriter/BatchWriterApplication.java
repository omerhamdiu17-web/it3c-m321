package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Dieser Dienst ist der EINZIGE, der Chat-Nachrichten in die Datenbank
 * schreibt. Er holt sie stapelweise aus der Queue chat.persist, damit aus
 * 1'667 Nachrichten pro Sekunde rund drei Transaktionen werden
 * (PLANUNG.md, Abschnitt 4.1).
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Startet Spring. Mehr passiert hier nicht: den Rest erledigen die
     * Konfiguration und der Listener, die Spring selbst findet.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
