package ch.benedict.m321.webgateway.dto;

/**
 * Alles, was das Gateway über den WebSocket an den Browser schickt.
 *
 * Über dieselbe Verbindung kommen drei verschiedene Dinge. Das Feld type
 * sagt dem Browser, was im Feld payload steckt:
 * - "message":  eine zugestellte Nachricht (ChatMessage)
 * - "accepted": die eigene Nachricht wurde angenommen (AcceptedResponse)
 * - "error":    die eigene Nachricht wurde NICHT gesendet (Text)
 *
 * @param type    die Art des Ereignisses
 * @param payload der Inhalt, passend zur Art
 */
public record ServerEvent(String type, Object payload) {

    /** Eine Nachricht aus dem Zustellweg. */
    public static ServerEvent message(ChatMessage chatMessage) {
        return new ServerEvent("message", chatMessage);
    }

    /** Die Bestätigung an den Absender: angenommen, aber noch nicht gespeichert. */
    public static ServerEvent accepted(AcceptedResponse acceptedResponse) {
        return new ServerEvent("accepted", acceptedResponse);
    }

    /**
     * Der ehrliche Fehler an den Absender. Es gibt keinen Puffer auf dem
     * Sendeweg (PLANUNG.md, offener Punkt 9), also muss der Benutzer sehen,
     * dass seine Nachricht nicht angekommen ist.
     */
    public static ServerEvent error(String errorText) {
        return new ServerEvent("error", errorText);
    }
}
