# Schritt 7: Desktop-Client — Implementation Plan

> **Für agentische Mitarbeiter:** Umsetzung Aufgabe für Aufgabe, jede mit Test zuerst. Die Kästchen (`- [x]`) zeigen den Stand.

**Ziel:** Ein zweiter Client in JavaFX benutzt **dieselbe** Schnittstelle wie der Browser: Räume, Verlauf, WebSocket. Er beweist, dass das Backend clientneutral ist (PLANUNG.md, Abschnitt 2.2). Das ist die Kür der Umsetzungsreihenfolge.

**Architektur:** Der Desktop-Client läuft auf dem Host, nicht in Docker, und spricht wie der Browser nur `localhost:8080` an — der eine offene Port reicht. Er hat aber kein Session-Cookie. Stattdessen meldet er sich selbst bei Keycloak an (Authorization Code Flow mit PKCE, System-Browser, Rückleitung auf `127.0.0.1`) und schickt das Access-Token bei jeder Anfrage mit: `Authorization: Bearer <JWT>`. Das Gateway prüft dieses Token als **OAuth2 Resource Server** — genau wie in PLANUNG.md, Abschnitt 3.3 beschrieben.

**Tech-Stack:** Java 21, JavaFX 21, `java.net.http` (HttpClient und WebSocket aus dem JDK), Jackson, Spring Security OAuth2 Resource Server im Gateway.

**Spec:** [`../PLANUNG.md`](../PLANUNG.md) — Abschnitt 2.2 (Clients), 3.3 (Login-Ablauf), offener Punkt 4 (Login im JavaFX-Client).

## Globale Vorgaben

Wie in den Plänen zu Schritt 4 und 5. Zusätzlich:

- **Kein Passwort im Client.** Der Desktop-Client sieht das Passwort nie; die Anmeldung passiert im System-Browser bei Keycloak (RFC 8252, "OAuth 2.0 for Native Apps").
- **Kein Secret im Client.** Ein Programm auf dem Rechner der Benutzer kann nichts geheim halten. Der Client `desktop-client` im Realm ist deshalb `publicClient`, geschützt durch PKCE.
- **Tests ohne Bildschirm.** `mvn test` läuft auch auf einem Server ohne Anzeige. Kein Test startet JavaFX; getestet werden die Klassen ohne Oberfläche (PKCE, Token-Tausch, API-Aufrufe).
- **Keine Sonderlogik im Backend.** Das Gateway kennt keinen "Desktop-Modus". Es akzeptiert zusätzlich zum Cookie ein Bearer-Token, mehr nicht.

---

## Abgrenzung

| Bewusst **nicht** in diesem Schritt | Warum |
|---|---|
| Queue-Balken im Desktop-Client | Der Desktop-Client zeigt, dass die Chat-Schnittstelle clientneutral ist. Die Admin-Anzeige bleibt im Browser |
| Installer, signierte Pakete | Start über Maven (`javafx:run`) reicht für den Unterricht |
| Abmelden bei Keycloak aus dem Client | Fenster zu = Token weg. Die Sitzung im System-Browser bleibt, wie bei jeder anderen Web-Anmeldung |

---

## Entscheidungen in diesem Schritt

### Zwei Arten, angemeldet zu sein — ein Gateway

| | Browser | Desktop-Client |
|---|---|---|
| Anmeldung | Gateway leitet zu Keycloak um (Cookie-Sitzung) | Client öffnet den System-Browser, Keycloak leitet auf `http://127.0.0.1:<Port>/callback` zurück |
| Keycloak-Client | `web-gateway` (vertraulich, mit Secret) | `desktop-client` (öffentlich, ohne Secret, PKCE Pflicht) |
| Bei jeder Anfrage | Session-Cookie | `Authorization: Bearer <JWT>` |
| Prüfung im Gateway | Spring-Sitzung | JWT-Signatur mit dem öffentlichen Schlüssel von Keycloak (JWKS) und Aussteller `http://localhost:8080/auth/realms/chat` |

Beide landen im Gateway bei denselben Controllern. Was sie brauchen — Kennung, Name, Rollen — liest `LoggedInUser` aus den Claims, egal ob diese aus der Cookie-Sitzung (`OidcUser`) oder aus dem Bearer-Token (`Jwt`) stammen.

### Token läuft ab

Ein Access-Token von Keycloak gilt 5 Minuten. Der Desktop-Client holt sich mit dem Refresh-Token rechtzeitig ein neues, bevor er eine Anfrage schickt. Eine offene WebSocket-Verbindung bleibt bestehen; das Token wird nur beim Verbindungsaufbau geprüft.

---

## Dateistruktur

```
keycloak/realm-chat.json                         # + Client desktop-client (öffentlich, PKCE)
web-gateway/
    ├── pom.xml                                  # + spring-boot-starter-oauth2-resource-server
    ├── config/JwtConfig.java                    # prüft Bearer-Tokens: JWKS intern, Aussteller öffentlich
    ├── config/SecurityConfig.java               # + oauth2ResourceServer
    ├── controller/CurrentUserController.java    # ClaimAccessor statt OidcUser
    └── controller/QueueStatsController.java     # ClaimAccessor statt OidcUser
desktop-client/
├── pom.xml                                      # JavaFX, Jackson, javafx-maven-plugin
└── src/main/java/ch/benedict/m321/desktopclient/
    ├── Launcher.java                            # main(), startet JavaFX
    ├── DesktopClientApplication.java            # das Fenster
    ├── auth/Pkce.java                           # Code-Verifier und Code-Challenge (S256)
    ├── auth/LoopbackReceiver.java               # nimmt die Rückleitung von Keycloak an
    ├── auth/KeycloakLogin.java                  # Login-Adresse bauen, Code gegen Tokens tauschen, erneuern
    ├── auth/Tokens.java
    ├── auth/AccessTokenProvider.java            # liefert ein gültiges Access-Token, erneuert bei Bedarf
    ├── api/ChatApiClient.java                   # /api/me, /api/rooms, Verlauf, WebSocket
    ├── api/ChatMessage.java, Room.java, CurrentUser.java
    └── api/ServerEventParser.java               # liest "message", "accepted", "error"
```

---

## Task 1: Realm-Client desktop-client

- [x] Öffentlicher Client, Standard Flow, PKCE S256, Rückleitung `http://127.0.0.1/*`, derselbe Protocol Mapper `roles` wie beim Gateway.

## Task 2: Gateway akzeptiert Bearer-Tokens

- [x] Test `BearerTokenTest` — `/api/rooms` mit gültigem JWT (simuliert mit `jwt()`) → 200; `/api/me` mit JWT liefert den Namen aus dem Token.
- [x] Test `SecurityConfigTest` bleibt grün: ohne Anmeldung weiterhin Weiterleitung zu Keycloak.
- [x] `JwtConfig` (JwtDecoder: JWKS intern, Aussteller öffentlich), `oauth2ResourceServer` in `SecurityConfig`.
- [x] Controller lesen `ClaimAccessor` statt `OidcUser`.

## Task 3: Desktop-Client ohne Oberfläche

- [x] Test `PkceTest` — Beispiel aus RFC 7636, Anhang B.
- [x] Test `KeycloakLoginTest` — Login-Adresse enthält client_id, redirect_uri, code_challenge; Token-Tausch gegen einen Test-Webserver schickt code_verifier mit und liest die Tokens.
- [x] Test `ChatApiClientTest` — schickt `Authorization: Bearer`, liest Räume und Verlauf.
- [x] Test `ServerEventParserTest` — erkennt die drei Ereignisarten.

## Task 4: Oberfläche

- [x] Fenster mit Anmelde-Knopf, Raumliste, Nachrichtenliste, Eingabefeld.
- [x] Raumwechsel: WebSocket neu verbinden, dann Verlauf laden (gleiche Reihenfolge wie im Browser).

## Task 5: Prüfung gegen das echte System

- [x] `scripts/smoke-test.py`: Login als `desktop-client` mit PKCE und Rückleitung auf `127.0.0.1`, dann Räume, `/api/me` und WebSocket mit `Authorization: Bearer`. Das ist genau der Weg des Desktop-Clients, nur ohne Fenster.

## Task 6: Dokumentation

- [x] README: Desktop-Client starten.
- [x] PLANUNG.md: offener Punkt 4 erledigt.

---

## Prüfen von Hand

```bash
docker compose up -d --build          # das System muss laufen
mvn -pl desktop-client javafx:run     # der Client auf dem Host
```

"Anmelden" öffnet den System-Browser mit der Keycloak-Anmeldung. Nach dem Login zeigt der Browser
"Anmeldung abgeschlossen", der Client lädt die Räume. Was im Desktop-Client geschrieben wird,
erscheint im Browser im selben Raum und umgekehrt.
