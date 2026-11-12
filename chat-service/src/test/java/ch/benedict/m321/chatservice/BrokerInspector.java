package ch.benedict.m321.chatservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragt RabbitMQ im Test mit rabbitmqctl, genau so, wie es die Abnahme tut.
 *
 * Warum nicht über Spring: Spring kennt nur, was es selbst angelegt hat. Ob eine
 * Queue wirklich am Fanout hängt oder nach dem Stopp wirklich weg ist, weiss nur
 * der Broker.
 */
public final class BrokerInspector {

    /** Liest die JSON-Ausgabe von rabbitmqctl. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Reine Sammlung von Hilfsmethoden, wird nie erzeugt. */
    private BrokerInspector() {
    }

    /** Die Namen aller Queues, die am Exchange hängen (wie S4 sie zählt). */
    public static List<String> queuesBoundTo(RabbitMQContainer rabbitMq, String exchangeName) throws Exception {
        JsonNode bindings = runAsJson(rabbitMq, "list_bindings", "source_name", "destination_name");
        List<String> queueNames = new ArrayList<>();
        for (JsonNode binding : bindings) {
            String source = binding.get("source_name").asText();
            if (source.equals(exchangeName)) {
                String destination = binding.get("destination_name").asText();
                queueNames.add(destination);
            }
        }
        return queueNames;
    }

    /**
     * Name, Lebensdauer, Argumente und Füllstand einer Queue, oder null, wenn
     * es sie nicht gibt. "messages" zählt wie in S6: wartende plus
     * ausgelieferte, noch nicht bestätigte Nachrichten.
     */
    public static JsonNode queueDetails(RabbitMQContainer rabbitMq, String queueName) throws Exception {
        JsonNode queues = runAsJson(rabbitMq, "list_queues",
                "name", "durable", "auto_delete", "exclusive", "arguments", "messages", "consumers");
        for (JsonNode queue : queues) {
            String name = queue.get("name").asText();
            if (name.equals(queueName)) {
                return queue;
            }
        }
        return null;
    }

    /**
     * Ein Argument einer Queue, z. B. "x-max-length". rabbitmqctl liefert die
     * Argumente als Liste von Dreiern: [Name, Typ, Wert]. null, wenn es fehlt.
     */
    public static JsonNode argument(JsonNode queue, String argumentName) {
        JsonNode arguments = queue.get("arguments");
        for (JsonNode argument : arguments) {
            String name = argument.get(0).asText();
            if (name.equals(argumentName)) {
                return argument.get(2);
            }
        }
        return null;
    }

    /** Führt rabbitmqctl im Container aus und liest die Ausgabe als JSON. */
    private static JsonNode runAsJson(RabbitMQContainer rabbitMq, String command, String... columns) throws Exception {
        List<String> arguments = new ArrayList<>();
        arguments.add("rabbitmqctl");
        arguments.add(command);
        arguments.add("--formatter");
        arguments.add("json");
        arguments.add("--quiet");
        for (String column : columns) {
            arguments.add(column);
        }
        String[] commandLine = arguments.toArray(new String[0]);
        ExecResult result = rabbitMq.execInContainer(commandLine);
        String output = result.getStdout();
        return OBJECT_MAPPER.readTree(output);
    }
}
