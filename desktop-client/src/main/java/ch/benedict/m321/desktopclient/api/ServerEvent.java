package ch.benedict.m321.desktopclient.api;

/**
 * Ein Ereignis vom Gateway über den WebSocket (siehe ServerEvent.java im
 * web-gateway). Je nach Art ist genau eines der beiden Felder gefüllt.
 *
 * @param type      "message", "accepted" oder "error"
 * @param message   bei "message": die zugestellte Nachricht, sonst null
 * @param errorText bei "error": der Fehlertext, sonst null
 */
public record ServerEvent(String type, ChatMessage message, String errorText) {

    public static final String MESSAGE = "message";
    public static final String ACCEPTED = "accepted";
    public static final String ERROR = "error";
}
