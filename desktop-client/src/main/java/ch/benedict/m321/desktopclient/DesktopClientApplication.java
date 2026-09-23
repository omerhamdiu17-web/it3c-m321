package ch.benedict.m321.desktopclient;

import ch.benedict.m321.desktopclient.api.ChatApiClient;
import ch.benedict.m321.desktopclient.api.ChatMessage;
import ch.benedict.m321.desktopclient.api.CurrentUser;
import ch.benedict.m321.desktopclient.api.Room;
import ch.benedict.m321.desktopclient.api.ServerEvent;
import ch.benedict.m321.desktopclient.api.ServerEventParser;
import ch.benedict.m321.desktopclient.auth.AccessTokenProvider;
import ch.benedict.m321.desktopclient.auth.KeycloakLogin;
import ch.benedict.m321.desktopclient.auth.LoopbackReceiver;
import ch.benedict.m321.desktopclient.auth.Pkce;
import ch.benedict.m321.desktopclient.auth.Tokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Das Fenster des Desktop-Clients: Anmelden, Raumliste, Nachrichten, Eingabe.
 *
 * Eine Regel von JavaFX bestimmt den Aufbau: die Oberfläche darf NUR im
 * JavaFX-Thread verändert werden, und dieser Thread darf nie warten (sonst
 * friert das Fenster ein). Alles, was übers Netz geht, läuft deshalb in
 * einem eigenen (virtuellen) Thread; das Ergebnis kommt mit
 * Platform.runLater(...) zurück in den JavaFX-Thread.
 */
public class DesktopClientApplication extends Application {

    /** Der eine offene Port. Keycloak liegt dahinter unter /auth. */
    private static final String GATEWAY_URL = "http://localhost:8080";
    private static final String REALM_URL = GATEWAY_URL + "/auth/realms/chat";

    /** So lange darf die Anmeldung im Browser dauern. */
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = ChatApiClient.createObjectMapper();
    private final ServerEventParser serverEventParser = new ServerEventParser(objectMapper);

    private final Label statusLabel = new Label("Nicht angemeldet");
    private final Button loginButton = new Button("Anmelden");
    private final ListView<Room> roomList = new ListView<>();
    private final ObservableList<ChatMessage> messages = FXCollections.observableArrayList();
    private final ListView<ChatMessage> messageList = new ListView<>(messages);
    private final TextField inputField = new TextField();
    private final Button sendButton = new Button("Senden");

    private ChatApiClient chatApiClient;
    private WebSocket currentSocket;
    private Room currentRoom;

    /** Baut das Fenster auf. Wird von JavaFX im JavaFX-Thread aufgerufen. */
    @Override
    public void start(Stage stage) {
        loginButton.setOnAction(event -> startLogin());
        sendButton.setOnAction(event -> sendMessage());
        inputField.setOnAction(event -> sendMessage());
        inputField.setPromptText("Nachricht schreiben …");
        roomList.getSelectionModel().selectedItemProperty().addListener(
                (observable, previousRoom, selectedRoom) -> openRoom(selectedRoom));
        setChatEnabled(false);

        HBox topBar = new HBox(10, loginButton, statusLabel);
        HBox bottomBar = new HBox(10, inputField, sendButton);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        roomList.setPrefWidth(160);

        BorderPane layout = new BorderPane();
        layout.setTop(topBar);
        layout.setLeft(roomList);
        layout.setCenter(messageList);
        layout.setBottom(bottomBar);
        layout.setPadding(new Insets(10));
        BorderPane.setMargin(topBar, new Insets(0, 0, 10, 0));
        BorderPane.setMargin(bottomBar, new Insets(10, 0, 0, 0));
        BorderPane.setMargin(roomList, new Insets(0, 10, 0, 0));

        stage.setTitle("M321 Chat — Desktop");
        stage.setScene(new Scene(layout, 800, 500));
        stage.show();
    }

    /** Fenster zu: die WebSocket-Verbindung sauber schliessen. */
    @Override
    public void stop() {
        closeCurrentSocket();
    }

    /** Knopf "Anmelden": der Login läuft im Hintergrund, das Fenster bleibt bedienbar. */
    private void startLogin() {
        loginButton.setDisable(true);
        statusLabel.setText("Anmeldung im Browser …");
        Thread.startVirtualThread(this::login);
    }

    /**
     * Der ganze Login (PLANUNG.md, offener Punkt 4):
     * 1. PKCE-Zufallswort und state ausdenken,
     * 2. kleinen Webserver auf 127.0.0.1 starten,
     * 3. System-Browser mit der Keycloak-Anmeldung öffnen,
     * 4. auf die Rückleitung mit dem Code warten,
     * 5. Code gegen Tokens tauschen, Benutzer und Räume laden.
     */
    private void login() {
        try {
            String codeVerifier = Pkce.generateVerifier();
            String codeChallenge = Pkce.challengeFor(codeVerifier);
            String state = UUID.randomUUID().toString();
            KeycloakLogin keycloakLogin = new KeycloakLogin(REALM_URL, httpClient, objectMapper);

            try (LoopbackReceiver loopbackReceiver = new LoopbackReceiver(state)) {
                String redirectUri = loopbackReceiver.redirectUri();
                URI loginUri = keycloakLogin.authorizationUri(redirectUri, codeChallenge, state);
                Platform.runLater(() -> getHostServices().showDocument(loginUri.toString()));

                String code = loopbackReceiver.waitForCode(LOGIN_TIMEOUT);
                Tokens tokens = keycloakLogin.exchangeCode(code, redirectUri, codeVerifier);
                AccessTokenProvider accessTokenProvider = new AccessTokenProvider(keycloakLogin, tokens);
                chatApiClient = new ChatApiClient(GATEWAY_URL, httpClient, objectMapper, accessTokenProvider);
            }

            CurrentUser currentUser = chatApiClient.loadCurrentUser();
            List<Room> rooms = chatApiClient.loadRooms();
            Platform.runLater(() -> showLoggedIn(currentUser, rooms));
        } catch (Exception exception) {
            Platform.runLater(() -> showLoginFailed(exception));
        }
    }

    /** Nach dem Login: Name anzeigen, Räume füllen, den ersten Raum öffnen. */
    private void showLoggedIn(CurrentUser currentUser, List<Room> rooms) {
        statusLabel.setText("Angemeldet als " + currentUser.displayName() + " (" + currentUser.username() + ")");
        roomList.getItems().setAll(rooms);
        setChatEnabled(true);
        if (!rooms.isEmpty()) {
            roomList.getSelectionModel().selectFirst();
        }
    }

    private void showLoginFailed(Exception exception) {
        statusLabel.setText("Anmeldung fehlgeschlagen: " + exception.getMessage());
        loginButton.setDisable(false);
    }

    /**
     * Raum wechseln. Gleiche Reihenfolge wie im Browser: ERST die neue
     * Verbindung, DANN der Verlauf. So geht keine Nachricht verloren, die
     * dazwischen geschrieben wird; doppelte fallen über die ID heraus.
     */
    private void openRoom(Room room) {
        if (room == null) {
            return;
        }
        closeCurrentSocket();
        currentRoom = room;
        messages.clear();

        Thread.startVirtualThread(() -> {
            try {
                WebSocket socket = chatApiClient.connect(room.id(), this::onServerEvent);
                List<ChatMessage> history = chatApiClient.loadHistory(room.id());
                Platform.runLater(() -> {
                    // Hat der Benutzer inzwischen schon wieder den Raum
                    // gewechselt, wird diese Verbindung nicht mehr gebraucht.
                    if (currentRoom != room) {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "Raumwechsel");
                        return;
                    }
                    currentSocket = socket;
                    for (ChatMessage message : history) {
                        addMessage(message);
                    }
                });
            } catch (Exception exception) {
                Platform.runLater(() -> statusLabel.setText("Raum konnte nicht geöffnet werden: " + exception.getMessage()));
            }
        });
    }

    /**
     * Ein Ereignis vom WebSocket. Kommt im Thread des HTTP-Clients an,
     * deshalb geht die Änderung an der Liste über Platform.runLater.
     */
    private void onServerEvent(String json) {
        try {
            ServerEvent event = serverEventParser.parse(json);
            if (ServerEvent.MESSAGE.equals(event.type())) {
                Platform.runLater(() -> addMessage(event.message()));
            }
            if (ServerEvent.ERROR.equals(event.type())) {
                Platform.runLater(() -> statusLabel.setText(event.errorText()));
            }
        } catch (Exception exception) {
            Platform.runLater(() -> statusLabel.setText("Unlesbares Ereignis vom Server"));
        }
    }

    /**
     * Fügt eine Nachricht ein, wenn sie zum offenen Raum gehört und noch
     * nicht da ist, und sortiert nach dem Zeitstempel des Servers
     * (PLANUNG.md, offener Punkt 3). Läuft immer im JavaFX-Thread.
     */
    private void addMessage(ChatMessage incoming) {
        if (currentRoom == null || !currentRoom.id().equals(incoming.roomId())) {
            return;
        }
        for (ChatMessage existing : messages) {
            if (existing.id().equals(incoming.id())) {
                return;
            }
        }
        messages.add(incoming);
        messages.sort(Comparator.comparing(ChatMessage::sentAt));
        messageList.scrollTo(messages.size() - 1);
    }

    /** Knopf "Senden" oder Enter im Eingabefeld. Die eigene Nachricht erscheint erst über den Zustellweg. */
    private void sendMessage() {
        String content = inputField.getText().trim();
        if (content.isEmpty() || currentSocket == null || currentRoom == null) {
            return;
        }
        try {
            chatApiClient.send(currentSocket, currentRoom.id(), content);
            inputField.clear();
        } catch (Exception exception) {
            statusLabel.setText("Nachricht nicht gesendet: " + exception.getMessage());
        }
    }

    private void closeCurrentSocket() {
        if (currentSocket != null) {
            currentSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Raumwechsel");
            currentSocket = null;
        }
    }

    private void setChatEnabled(boolean enabled) {
        roomList.setDisable(!enabled);
        inputField.setDisable(!enabled);
        sendButton.setDisable(!enabled);
    }
}
