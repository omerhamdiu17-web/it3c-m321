package ch.benedict.m321.loadgenerator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Startpunkt des load-generator.
 *
 * Er schickt eine einstellbare Menge Nachrichten pro Minute direkt an den
 * chat-service und schreibt jede Sekunde mit, wie viele angekommen sind.
 * Danach beendet er sich. Gestartet wird er nur auf Wunsch:
 * "docker compose --profile load up load-generator".
 *
 * ConfigurationPropertiesScan findet unsere LoadProperties.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LoadGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoadGeneratorApplication.class, args);
    }
}
