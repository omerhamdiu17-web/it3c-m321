package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Dieser Dienst ist der EINZIGE, der in die Datenbank schreibt. Er holt
 * Nachrichten stapelweise aus der Queue chat.persist und schreibt jeden
 * Stapel mit einem einzigen INSERT. So werden aus 1'667 Nachrichten pro
 * Sekunde rund drei Datenbank-Transaktionen (PLANUNG.md, Abschnitt 4.1).
 */
@SpringBootApplication
public class BatchWriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
