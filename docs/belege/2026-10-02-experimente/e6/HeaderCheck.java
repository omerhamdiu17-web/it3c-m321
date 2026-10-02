import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;

/** Prüft, welche Header WebSocket.Builder annimmt (ohne Server). */
public class HeaderCheck {
    /** Versucht jeden Header einzeln und meldet angenommen oder abgelehnt. */
    public static void main(String[] args) {
        String[] names = {"Cookie", "Origin", "Authorization", "Host", "Sec-WebSocket-Key"};
        HttpClient client = HttpClient.newHttpClient();
        for (String name : names) {
            WebSocket.Builder builder = client.newWebSocketBuilder();
            try {
                builder.header(name, "x");
                System.out.println(name + ": angenommen");
            } catch (IllegalArgumentException exception) {
                System.out.println(name + ": abgelehnt (" + exception.getMessage() + ")");
            }
        }
    }
}
