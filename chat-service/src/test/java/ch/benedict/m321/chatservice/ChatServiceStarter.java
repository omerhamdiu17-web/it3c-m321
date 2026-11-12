package ch.benedict.m321.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Startet im Test eine Instanz des chat-service so, wie docker compose es tut:
 * eigener Spring-Kontext, eigener freier Port, derselbe RabbitMQ.
 *
 * Mit @SpringBootTest gäbe es nur EINEN Kontext pro Testklasse. Für die Tests
 * mit zwei Instanzen und für "Instanz stoppt" brauchen wir Kontexte, die wir
 * selbst starten und schliessen.
 */
public final class ChatServiceStarter {

    /** Reine Sammlung von Hilfsmethoden, wird nie erzeugt. */
    private ChatServiceStarter() {
    }

    /**
     * Startet eine Instanz gegen den RabbitMQ-Testcontainer. server.port=0
     * heisst: Spring sucht sich einen freien Port, so stören sich zwei
     * Instanzen nicht.
     */
    public static ConfigurableApplicationContext start(RabbitMQContainer rabbitMq) {
        String host = "--spring.rabbitmq.host=" + rabbitMq.getHost();
        String port = "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort();
        String username = "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername();
        String password = "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword();
        String serverPort = "--server.port=0";

        SpringApplication application = new SpringApplication(ChatServiceApplication.class);
        return application.run(host, port, username, password, serverPort);
    }

    /** Der Port, den sich die Instanz beim Start ausgesucht hat. */
    public static int portOf(ConfigurableApplicationContext instance) {
        String port = instance.getEnvironment().getProperty("local.server.port");
        return Integer.parseInt(port);
    }
}
