import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;

/** Startet einen Mini-Server, der die Handshake-Header ausgibt und mit 403 antwortet. */
public class HeaderEcho {

    /** Gibt die empfangenen Header aus und lehnt den Handshake ab. */
    static class Echo implements HttpHandler {
        /** Antwortet immer 403, nachdem die Header ausgegeben sind. */
        public void handle(HttpExchange exchange) throws IOException {
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                System.out.println("  Server sah: " + entry.getKey() + " = " + entry.getValue());
            }
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        }
    }

    /** Baut je einen Handshake mit den drei Headern und einen mit Sec-WebSocket-Key. */
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new Echo());
        server.start();
        int port = server.getAddress().getPort();
        URI uri = URI.create("ws://127.0.0.1:" + port + "/ws/chat?roomId=x");
        HttpClient client = HttpClient.newHttpClient();
        System.out.println("### Cookie, Origin, Authorization");
        try {
            WebSocket.Builder builder = client.newWebSocketBuilder();
            builder.header("Cookie", "JSESSIONID=abc");
            builder.header("Origin", "http://evil.example");
            builder.header("Authorization", "Bearer xyz");
            builder.buildAsync(uri, new WebSocket.Listener() {}).join();
        } catch (Exception exception) {
            System.out.println("  Client: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        System.out.println("### Sec-WebSocket-Key (Gegenprobe)");
        try {
            WebSocket.Builder builder = client.newWebSocketBuilder();
            builder.header("Sec-WebSocket-Key", "x");
            builder.buildAsync(uri, new WebSocket.Listener() {}).join();
        } catch (Exception exception) {
            System.out.println("  Client: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        server.stop(0);
    }
}
