package ch.benedict.m321.desktopclient.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Liest ein Ereignis vom WebSocket.
 *
 * Über dieselbe Verbindung kommen drei Arten von Ereignissen, und das Feld
 * "payload" hat je nach Art eine andere Form. Deshalb lesen wir zuerst
 * "type" und entscheiden danach, was im payload steckt.
 */
public class ServerEventParser {

    private final ObjectMapper objectMapper;

    public ServerEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Macht aus dem JSON-Text ein ServerEvent. */
    public ServerEvent parse(String json) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(json);
        String type = event.path("type").asText();
        JsonNode payload = event.path("payload");

        if (ServerEvent.MESSAGE.equals(type)) {
            ChatMessage chatMessage = objectMapper.treeToValue(payload, ChatMessage.class);
            return new ServerEvent(type, chatMessage, null);
        }
        if (ServerEvent.ERROR.equals(type)) {
            String errorText = payload.asText();
            return new ServerEvent(type, null, errorText);
        }
        return new ServerEvent(type, null, null);
    }
}
