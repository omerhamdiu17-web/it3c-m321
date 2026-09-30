package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.springframework.amqp.core.Message;

/**
 * Eine gelesene Nachricht: das Original aus der Queue und der Inhalt daraus.
 *
 * In die Datenbank geht nur der Inhalt. Das Original brauchen wir, falls die
 * Datenbank die Nachricht ablehnt: dann legen wir genau dieses Original,
 * Body und Header unverändert, nach chat.dlq (Spezifikation 3.3, F9).
 *
 * @param original    die Nachricht, wie sie aus chat.persist kam
 * @param chatMessage der Inhalt, schon aus dem JSON gelesen
 */
public record ReceivedMessage(Message original, ChatMessage chatMessage) {
}
