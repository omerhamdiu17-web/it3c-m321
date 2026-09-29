package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;

/**
 * Eine gelesene Nachricht zusammen mit ihrem Lieferschein.
 *
 * Der deliveryTag ist die Nummer, unter der RabbitMQ die Nachricht auf diesem
 * Channel ausgeliefert hat. Nur mit dieser Nummer lässt sie sich bestätigen
 * (ACK), ablehnen (Reject) oder zurückgeben (NACK).
 *
 * @param deliveryTag die Liefernummer von RabbitMQ
 * @param chatMessage der Inhalt, schon aus dem JSON gelesen
 */
public record ReceivedMessage(long deliveryTag, ChatMessage chatMessage) {
}
