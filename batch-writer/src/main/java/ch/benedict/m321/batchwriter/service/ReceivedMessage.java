package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;

/**
 * Eine gelesene Nachricht zusammen mit ihrem Lieferschein.
 *
 * Der deliveryTag ist die Nummer, unter der RabbitMQ die Nachricht auf
 * dieser Verbindung ausgeliefert hat. Nur mit dieser Nummer können wir
 * sie später bestätigen (ack) oder zurückgeben (nack).
 *
 * @param deliveryTag die Liefernummer von RabbitMQ
 * @param chatMessage der Inhalt, schon aus dem JSON gelesen
 */
public record ReceivedMessage(long deliveryTag, ChatMessage chatMessage) {
}
