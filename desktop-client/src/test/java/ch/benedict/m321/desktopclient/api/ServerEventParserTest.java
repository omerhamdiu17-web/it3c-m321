package ch.benedict.m321.desktopclient.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Prüft, dass die drei Ereignisarten vom WebSocket richtig erkannt werden.
 * Das JSON ist genau das, was ServerEvent.java im Gateway schreibt.
 */
class ServerEventParserTest {

    private final ServerEventParser parser = new ServerEventParser(ChatApiClient.createObjectMapper());

    @Test
    void readsDeliveredMessage() throws Exception {
        String json = """
                {"type": "message", "payload": {
                  "id": "3f2b1c4e-0000-0000-0000-000000000009",
                  "roomId": "00000000-0000-0000-0000-000000000001",
                  "senderId": "sub-bob", "senderName": "Bob Beispiel",
                  "content": "Hallo", "sentAt": "2026-09-23T10:00:00Z"}}
                """;

        ServerEvent event = parser.parse(json);

        assertEquals(ServerEvent.MESSAGE, event.type());
        assertEquals("Bob Beispiel", event.message().senderName());
    }

    @Test
    void readsAcceptedConfirmation() throws Exception {
        String json = """
                {"type": "accepted", "payload": {"id": "3f2b1c4e-0000-0000-0000-000000000009", "sentAt": "2026-09-23T10:00:00Z"}}
                """;

        ServerEvent event = parser.parse(json);

        assertEquals(ServerEvent.ACCEPTED, event.type());
        assertNull(event.message());
    }

    @Test
    void readsError() throws Exception {
        String json = """
                {"type": "error", "payload": "Nachricht nicht gesendet."}
                """;

        ServerEvent event = parser.parse(json);

        assertEquals(ServerEvent.ERROR, event.type());
        assertEquals("Nachricht nicht gesendet.", event.errorText());
    }
}
