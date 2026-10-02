# web-gateway, Keycloak und Web-UI — Implementation Plan

> **Für Agenten, die diesen Plan ausführen:** Pflicht ist der Sub-Skill superpowers:subagent-driven-development (empfohlen) oder superpowers:executing-plans, Aufgabe für Aufgabe. Die Schritte sind Checkboxen (`- [ ]`) zum Abhaken.

**Ziel:** Baustein 1 macht die Chat-App im Browser benutzbar. Zuerst der Login: Keycloak mit dem Realm `chat`, ein Gateway als einziger offener Port (`127.0.0.1:8080`), Anmeldung mit Authorization Code und PKCE, eine Web-UI, die «Angemeldet als …» zeigt (Meilenstein `schritt-2`). Danach ein Weg durch: eine Nachricht vom Browser über den `chat-service` und RabbitMQ bis in einen zweiten Browser (Meilenstein `schritt-3`). Der Browser bekommt nie ein Token, nur ein Session-Cookie.

**Architektur:** Keycloak läuft ohne Port im Netz `chat-net` und importiert den Realm aus `keycloak/realm-chat.json`. Der `web-gateway` (Spring Boot) reicht nur `/auth/realms/chat/**` und `/auth/resources/**` an Keycloak durch, meldet Browser als OIDC-Client an (Anmeldung in der Session des Gateways) und prüft Bearer-JWTs nur für `/api/**` und `/ws/**`. Schichtung wie in den anderen Diensten: `config` (Route, Sicherheit, Clientregistrierung, Token-Prüfung), `controller` (`/api/me`), `service` (wer angemeldet ist), `dto` (Antworten). Ab Task 11 kommen der Sendeweg (WebSocket → REST an den `chat-service`) und der Zustellweg (eigene Queue an `chat.delivery` → WebSocket) dazu. Die Web-UI (React) liegt in `web-ui/` (PLANUNG.md 5), wird im Image des Gateways gebaut und von ihm ausgeliefert.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring Security 6.5 (OAuth2 Client und Resource Server), Spring Cloud Gateway Server Web MVC (Spring Cloud 2025.0.3), Keycloak 26.7.3, RabbitMQ 3.13, React 19, TypeScript 5.9, Vite 7, Vitest, Playwright, JUnit 5, Testcontainers, Maven Multi-Modul, GitHub Actions.

**Spec:** [`spec-web-gateway.md`](spec-web-gateway.md) — Zweck und Abgrenzung ([1](spec-web-gateway.md#1-zweck-und-abgrenzung)), Vertrag ([2](spec-web-gateway.md#2-vertrag), Realm in [2.6](spec-web-gateway.md#26-keycloak-realm-chat)), Verhalten und Fehlerfälle F1 bis F15 ([3](spec-web-gateway.md#3-verhalten), [3.4](spec-web-gateway.md#34-fehlerfälle)), Konfiguration ([4](spec-web-gateway.md#4-datenmodell-und-konfiguration), Clientregistrierung in [4.6](spec-web-gateway.md#46-clientregistrierung-und-prüfung-der-tokens)), Abweichungen von PLANUNG.md ([5](spec-web-gateway.md#5-abweichungen-von-planungmd)), Abnahmekriterien W1 bis W12, Testklassen und offene Prüfungen P1 bis P14 ([6](spec-web-gateway.md#6-abnahmekriterien), [6.5](spec-web-gateway.md#65-offene-prüfungen)).

## Globale Vorgaben

Diese Punkte gelten für **jede** Aufgabe in diesem Plan:

- **Java 21**, Spring Boot **3.5.16** (Eltern-POM unverändert). Spring Cloud kommt nur über die Stückliste `spring-cloud-dependencies` **2025.0.3** im `dependencyManagement` des Eltern-POM dazu (Spezifikation 4.4).
- **Code auf Englisch** — Klassen, Methoden, Variablen, Dateinamen und **Log-Meldungen**. **Alles andere auf Deutsch** — Kommentare, Javadoc, Meldungen in Assertions, Commit-Messages, Doku.
- **Über jeder Klasse und jeder Methode ein Kommentar**, der erklärt, *warum* es sie gibt: auch über `main`, Konstruktoren, `@Bean`-Methoden, verschachtelten Klassen und Records, Testmethoden, `@BeforeEach`, `@AfterAll` und `@DynamicPropertySource`. Der Javadoc steht über den Annotationen. Prüfung: `bash scripts/kommentare.sh web-gateway/src` und `bash scripts/kommentare.sh scripts` enden mit Exit-Code 0. In TypeScript: ein Kommentar über jeder Funktion und Komponente (ab Task 15 vom selben Skript geprüft).
- **Kein «stream» im Quelltext** von `web-gateway/`, `web-ui/` und `scripts/WebSocketProbe.java` (W11): keine Stream-API, `for` statt Streams, in TypeScript `for…of` statt `map`, `filter` und `forEach`. Das Wort steht auch nicht in Kommentaren, auch nicht als «upstream» oder «downstream». Wo eine JDK-Methode einen Typ zurückgibt, dessen Name das Wort enthält (`HttpExchange.getRequestBody()` und `getResponseBody()` im Mini-Server der Tests), steht `var` statt des Typnamens, mit einem Kommentar dazu. Prüfung wie W11: `grep -rin --exclude-dir=target --exclude-dir=node_modules --exclude-dir=dist stream web-gateway/ web-ui/ scripts/WebSocketProbe.java` ohne Ausgabe.
- **Keine verschachtelten Aufrufe.** Ein Ergebnis pro Zeile, in eine benannte Variable, auch in Tests. Erlaubt sind (wie im `chat-service` und im `batch-writer`):
  - Builder-Ketten (`ClientRegistration.withRegistrationId(…)…build()`, `HttpRequest.newBuilder(…)…build()`), weil jede Zeile darin nur einen Wert setzt;
  - reine Getter als Argument (`assertEquals(200, response.statusCode())`, `log.info(…, request.roomId())`).
  Tests mit MockMvc lesen die Antwort mit `mockMvc.perform(request).andReturn()` und prüfen mit einfachen `assertEquals`, nicht mit verschachtelten `andExpect(status().isOk())`.
- **Lambdas nur in der Konfiguration von Spring Security** (`http.authorizeHttpRequests(requests -> …)`). Grund: Seit Spring Security 6.1 ist diese Form der vorgesehene Weg, die alte Kettenform mit `and()` ist veraltet und fällt in 7.0 weg. Sonst gibt es **keine Lambdas und keine Methodenreferenzen** (`::`). Verlangt Spring ein Funktionsobjekt, gibt es eine kleine benannte Klasse (`FixedValue` für `@DynamicPropertySource`, `AuthorizedPartyValidator`, `ApiBearerTokenResolver`). Prüfung: `grep -rln -- '->' web-gateway/src` nennt nur `SecurityConfig.java`, `grep -rn '::' web-gateway/src` hat keine Ausgabe. In TypeScript benannte Funktionen statt Pfeilfunktionen.
- **Keine Ternaries** (`? :`), weder in Java noch in TypeScript.
- **Keine anonymen Klassen und keine Mock-Frameworks** (kein Mockito, kein `@MockitoBean`). Tests arbeiten gegen echte Container (Keycloak, RabbitMQ), gegen den Mini-Server `TestHttpServer` aus dem JDK, mit `oidcLogin()` aus spring-security-test und mit eigenen, im Test signierten Tokens.
- **Keine Interfaces mit einer einzigen Implementierung**, keine Abstraktion auf Vorrat. Eine Schnittstelle von Spring zu implementieren (`OAuth2TokenValidator`, `BearerTokenResolver`) ist erlaubt.
- **Lombok** für `@Slf4j` und `@RequiredArgsConstructor`. Datenklassen sind `record`.
- **Genau ein `ports:`-Eintrag** in `docker-compose.yml`: `127.0.0.1:8080:8080` am `web-gateway`. Kein `container_name`.
- **Keine Geheimnisse im Repository.** Secret und Passwörter stehen nur in `.env`. Im Repository stehen `.env.example` und Platzhalter in der Realm-Datei. Werte wie `test-secret` in Tests sind keine Geheimnisse, sie gelten nur im Test-Container.
- **Neues Maven-Modul → POM-`COPY` in allen Dockerfiles.** Maven liest alle Module aus dem Eltern-POM; fehlt ein POM, bricht der Image-Bau der anderen Dienste (spec-batch-writer.md 4.7).
- **Commit-Messages** auf Deutsch mit Präfix (`feat:`, `fix:`, `test:`, `chore:`, `ci:`, `docs:`, `refactor:`), ein Thema pro Commit. **Jede endet mit dieser Zeile:**
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- **Skripte** (`scripts/*.sh` und die Prüfschritte in GitHub Actions): `curl` schreibt nie nach `/dev/null`, sondern immer in eine Datei unter `target/` (`-o "$T/<datei>"`). Grund: Unter Git Bash mit `MSYS_NO_PATHCONV=1` kennt das native `curl` von Windows den Pfad `/dev/null` nicht und endet mit Exit-Code 23. Die `.env` wird mit derselben Leseschleife geladen wie in `scripts/abnahme.sh` (Windows-Zeilenende abschneiden), gewartet wird mit `wait_until <Sekunden> <Befehl>`.
- **Voraussetzung:** `docs/spec-web-gateway.md` ist committet (`05d8bab` und die Nachbesserungen nach dem Review), gearbeitet wird auf dem Branch `baustein/web-gateway`. Docker läuft (Testcontainers), Node 22 für die Web-UI, in Git Bash immer `export MSYS_NO_PATHCONV=1`.

## Prüfschwerpunkte

Fälle, die kein Kriterium W1 bis W12 direkt prüft, die aber jemanden treffen würden, der das System benutzt oder angreift. Jeder hat einen Test in der Aufgabe, die den Code dazu baut:

1. **Ein Pfad mit kodiertem `..`** (`/auth/realms/chat/%2e%2e/%2e%2e/admin/`) erreicht die Admin-Konsole nicht. Der Proxy würde den Pfad unverändert weitergeben, Keycloak ihn auflösen → Task 5, `KeycloakProxyIntegrationTest.dotSegmentsNeverReachKeycloak`.
2. **Gefälschte `X-Forwarded-*`-Header** aus dem Browser kommen nicht bei Keycloak an (P10) → Task 5, `forgedForwardedHeadersNeverReachKeycloak`.
3. **Keycloak startet gerade neu:** Der Browser bekommt innert 5 s einen Fehler statt einer hängenden Seite (P11, F1) → Task 5, `KeycloakProxyDownIntegrationTest.answersServerErrorWithinFiveSeconds`.
4. **Das Login-Formular kommt ohne CSRF-Token des Gateways durch**, ausserhalb von `/auth` gilt CSRF weiter → Task 5, `passesLoginFormPostWithoutCsrfToken`; Task 6, `SecurityConfigTest.authPathsNeedNoCsrfToken` und `postOutsideAuthNeedsCsrfToken`.
5. **Falsches Passwort:** Keycloak zeigt das Formular wieder, das Gateway bleibt ohne Anmeldung → Task 6, `LoginIntegrationTest.wrongPasswordShowsFormAgain`.
6. **Session Fixation:** Nach dem Login gilt eine neue Session-ID (Spezifikation 3.1, Punkt 3) → Task 6, `aliceLogsInAndSeesHerName`.
7. **Kein Token im Browser:** Keine Antwort auf `/` oder `/api/me` enthält `eyJ`, für den Pfad `/` gibt es nur `JSESSIONID`, und das Cookie ist `HttpOnly` und `SameSite=Lax`. Die Attribute prüft nur ein echter Port, MockMvc schreibt keinen Header `Set-Cookie: JSESSIONID` (Spezifikation 6.4) → Task 6, `LoginIntegrationTest.aliceLogsInAndSeesHerName` und `sessionCookieIsHttpOnlyAndLax`.
8. **`name` fehlt im Token:** `displayName` ist dann der Benutzername. **`roles` fehlt:** `admin` ist `false` → Task 6, `CurrentUserControllerTest`.
9. **Ein Bearer-Token auf einer Seite** (`/`) zählt nicht; Seiten verlangen die Session (Spezifikation 2.1) → Task 7, `BearerTokenTest.bearerTokenOnPageIsIgnored`.
10. **Abgelaufen trotz Toleranz:** Spring lässt bei `exp` 60 s Spielraum. Der Test nimmt ein Token, das seit 10 min abgelaufen ist → Task 7, `expiredTokenIsRejected`.
11. **Redirect-URI mit anderem Port oder fremdem Host** lehnt Keycloak ab, ohne Login-Formular → Task 3, `onlyRegisteredRedirectUrisAreAccepted`.
12. **Passwort aus `.env` oder Beispielwert:** Eine gesetzte Variable gilt, ohne Variable gilt der Beispielwert → Task 3, `bobUsesPasswordFromEnvironment` und `aliceGetsFixedIdNameEmailAndRoles`.
13. **Ältere `.env` ohne die neuen Schlüssel:** Die Abnahme bricht mit einer klaren Meldung ab, statt mitten in `docker compose` → Task 1, `scripts/env-pruefen.sh`.

**Offene Prüfungen aus Spezifikation 6.5.** Jede entscheidet ein Test. Was gilt, wenn sie fehlschlägt, steht in der Spezifikation; das Ergebnis kommt in den Commit der Aufgabe und später ins Abnahmeprotokoll (Spezifikation 6.6).

| Nr | Kurz | Test | Task |
|---|---|---|---|
| P1 | Rahmen über 16'384 Zeichen → `1009`, 12'014 Zeichen gehen durch | `ChatSocketIntegrationTest` | 11 |
| P2 | `desktop-client`: jeder Port bei `http://127.0.0.1/callback` | `RealmImportIntegrationTest.desktopClientAcceptsAnyLoopbackPort` | 3 |
| P3 | `${PUBLIC_URL}` auch in der Post-Logout-URI ersetzt | `RealmImportIntegrationTest.postLogoutRedirectUsesPublicUrl` | 3 |
| P4 | feste `id` aus der Realm-Datei = `sub` | `RealmImportIntegrationTest.aliceGetsFixedIdNameEmailAndRoles` | 3 |
| P5 | Abmelden mit abgelaufenem `id_token_hint` | `LoginIntegrationTest.logoutWorksWithExpiredIdToken` | 6 |
| P6 | WebSocket-Rahmen verlängern die Session nicht | `ChatSocketIntegrationTest` | 11 |
| P7 | Password-Grant über `admin-cli` | `RealmImportIntegrationTest.adminCliPasswordGrantGivesTokenForAdminCli`; mit dem Gateway: `LoginIntegrationTest.adminCliTokenIsRejected` | 3, 7 |
| P8 | `accepted`/`error` in der Reihenfolge der Rahmen | `ChatSocketIntegrationTest` | 11 |
| P9 | kein `prefetch` bei `NONE` | `DeliveryIntegrationTest` | 12 |
| P10 | `X-Forwarded-*` aus dem Browser | `KeycloakProxyIntegrationTest.forgedForwardedHeadersNeverReachKeycloak` | 5 |
| P11 | Keycloak nicht erreichbar → `5xx` innert 5 s | `KeycloakProxyDownIntegrationTest` | 5 |
| P12 | Client, der nicht liest, wird getrennt | `DeliveryIntegrationTest` | 12 |
| P13 | `1001` beim geordneten Stopp | `ChatSocketIntegrationTest` | 11 |
| P14 | Ziel der Umleitung nach dem Login: gespeicherte Anfrage, mit oder ohne `?continue` | `LoginIntegrationTest.loginReturnsToRequestedPage` | 6 |

**P11 in einer eigenen Klasse** (Spezifikation 6.4): Für `KeycloakProxyDownIntegrationTest` braucht das Gateway eine Keycloak-Adresse, an der niemand zuhört, und damit einen eigenen Spring-Kontext. In `KeycloakProxyIntegrationTest` müsste ein Test den Mini-Server anhalten, und die anderen Tests hingen von der Reihenfolge ab. Ein Keycloak, der die Verbindung annimmt, aber nie antwortet, gehört nicht zu P11.

## Abgrenzung

| Bewusst **nicht** in diesem Plan | Warum |
|---|---|
| Räume anlegen, Mitgliedschaften, Verlauf | Baustein 2 (Spezifikation 1.3). Baustein 1 kennt nur die Lobby `00000000-0000-0000-0000-000000000001` |
| Rechte nach Rolle, Queue-Tiefe, `load-generator` | Baustein 3. `admin` steht schon in `/api/me`, verlangt wird die Rolle noch nirgends |
| Messreihe, `--scale` | Baustein 4 |
| Desktop-Fenster (JavaFX) | Baustein 5. Der Keycloak-Client `desktop-client` und die Prüfung von Bearer-JWTs entstehen aber jetzt (Task 3 und 7), sonst wären sie nicht testbar |
| Gateway skalieren, Sessions in einer Datenbank | Offener Punkt 1, F12: genau eine Instanz, Sessions im Speicher |
| Token im Browser | Spezifikation 5: Der Browser bekommt nur `JSESSIONID` |
| Admin-Konsole, Bootstrap-Admin | Offener Punkt 5. Einen Bootstrap-Admin gibt es nur im Test-Container von `LoginIntegrationTest`, für P5 |
| Änderungen am `chat-service` und am `batch-writer` | Sie bleiben im Stand `bewertung-1`. Einzige Änderung: je eine `COPY`-Zeile im Dockerfile (Task 2), ohne die ihr Image nicht mehr baut |
| Auf RabbitMQ veröffentlichen | PLANUNG.md 3.4: Das Gateway ist Consumer, kein Producer |

## Dateistruktur

In Klammern die Aufgabe, die eine Datei anlegt oder ändert.

```
pom.xml                                  # + Modul web-gateway, + Stückliste Spring Cloud (2)
chat-service/Dockerfile                  # + COPY web-gateway/pom.xml (2)
batch-writer/Dockerfile                  # + COPY web-gateway/pom.xml (2)
docker-compose.yml                       # + keycloak (4); Healthcheck rabbitmq, + web-gateway mit dem EINZIGEN ports: (8)
.env.example                             # + KEYCLOAK_CLIENT_SECRET, DEMO_PASSWORD_* (4)
.github/workflows/build.yml              # + Job web-ui (9), + Job abnahme-system (10)
keycloak/realm-chat.json                 # Realm chat: Rollen, zwei Clients, drei Benutzer, Platzhalter (3)
scripts/
├── abnahme.sh                           # startet nur die vier Dienste von Bewertung 1 (1)
├── env-pruefen.sh                       # .env hat jeden Schlüssel aus .env.example (1)
├── abnahme-system.sh                    # W1, W2, W3, W7, W8, W10 (10); + W4, W5, W6, W9, W11 (14)
├── WebSocketProbe.java                  # Werkzeug für die WebSocket-Kriterien (14)
└── kommentare.sh                        # + TypeScript (15)
docs/spec-batch-writer.md                # Nachtrag in Abschnitt 6 (1)
docs/belege/2026-10-02-baustein-1/       # task-NN-rot.txt, task-NN-gruen.txt je Aufgabe, notizen.md (ab 1)
README.md                                # Zeile zu abnahme.sh (1); Login, Gateway, Web-UI (17)
web-ui/                                  # React, TypeScript, Vite (9, 13, 16)
├── package.json, package-lock.json      # Versionen fest, npm ci (9)
├── index.html, vite.config.ts, tsconfig.json (9)
├── src/main.tsx, src/App.tsx            # «Angemeldet als …», Abmelden (9)
├── src/currentUser.ts                   # reine Funktionen zu /api/me (9)
├── src/currentUser.test.ts              # Vitest (9)
├── src/chatReducer.ts, src/chatReducer.test.ts, src/Chat.tsx (13)
└── e2e/chat.spec.ts, playwright.config.ts (16)
web-gateway/
├── pom.xml                              (2)
├── Dockerfile                           # Maven- und JRE-Stufe (2), + Node-Stufe für web-ui/ (9)
└── src/
    ├── main/java/ch/benedict/m321/webgateway/
    │   ├── WebGatewayApplication.java   (2, 5)
    │   ├── config/
    │   │   ├── GatewayProperties.java        # Adressen und Secret aus der Umgebung (5, 6, 11)
    │   │   ├── KeycloakProxyConfig.java      # Route für zwei Präfixe unter /auth (5)
    │   │   ├── SecurityConfig.java           # Regeln, Login, Abmelden, Einstiegspunkte, Bearer (5, 6, 7)
    │   │   ├── KeycloakClientConfig.java     # Clientregistrierung von Hand (6)
    │   │   ├── JwtConfig.java                # Decoder: JWKS intern, Prüfungen zusammengesetzt (7)
    │   │   ├── AuthorizedPartyValidator.java # azp = desktop-client (7)
    │   │   └── ApiBearerTokenResolver.java   # Bearer nur für /api/** und /ws/** (7)
    │   ├── controller/CurrentUserController.java  # GET /api/me (6)
    │   ├── dto/CurrentUser.java              # Antwort von /api/me (6)
    │   ├── service/LoggedInUser.java         # wer angemeldet ist, aus den Claims (6)
    │   └── …                                 # Sendeweg (11), Zustellweg (12)
    ├── main/resources/application.yml   (2, 5, 6, 11)
    └── test/
        ├── resources/gateway-test.properties # Umgebung für Tests ohne andere Dienste (2)
        └── java/ch/benedict/m321/webgateway/
            ├── WebGatewayApplicationTest.java    (2)
            ├── TestKeycloak.java                 # Keycloak-Container mit der echten Realm-Datei (3)
            ├── TestBrowser.java                  # HTTP-Client mit Cookies, Umleitungen einzeln (3)
            ├── KeycloakFlow.java                 # Schritte des Code-Flows mit PKCE (3)
            ├── RealmImportIntegrationTest.java   (3)
            ├── TestHttpServer.java               # Mini-Server aus dem JDK (5)
            ├── FixedValue.java                   # fester Wert für @DynamicPropertySource (5)
            ├── TestUsers.java                    # angemeldete Benutzer für MockMvc (6)
            ├── config/
            │   ├── KeycloakProxyIntegrationTest.java      (5)
            │   ├── KeycloakProxyDownIntegrationTest.java  (5)
            │   ├── SecurityConfigTest.java                (6)
            │   ├── LoginIntegrationTest.java              (6, 7)
            │   └── BearerTokenTest.java                   (7)
            ├── controller/CurrentUserControllerTest.java  (6)
            └── …                                 # ChatServiceClientTest, ChatSocketIntegrationTest (11), DeliveryIntegrationTest (12)
```

**Wer wen kennt** — und zwar nur in dieser Richtung (Stand Task 10):

```
Browser ──► web-gateway :8080
              ├─ /auth/realms/chat/**, /auth/resources/** ──► KeycloakProxyConfig ──► keycloak:8080/auth/… (unverändert)
              ├─ /oauth2/…, /login/oauth2/…, GET /logout ──► Spring Security
              │        (SecurityConfig, KeycloakClientConfig) ──► keycloak:8080 (Token, JWKS, Userinfo; intern)
              ├─ /api/me ──► CurrentUserController ──► LoggedInUser (Claims aus Session oder JWT) ──► CurrentUser
              └─ /, /assets/** ──► Dateien der Web-UI (static/ im Jar)

Desktop-Client, Abnahme ──Authorization: Bearer──► /api/**, /ws/**
              ──► ApiBearerTokenResolver ──► JwtConfig (Issuer, Ablauf, AuthorizedPartyValidator)
              ──► JWKS einmal von keycloak:8080, danach im Speicher
```

Ab Task 11 kommen dazu: `/ws/chat` → WebSocket-Handler → Client für `POST http://chat-service:8080/messages`; `chat.delivery` → eigene Queue → Verbraucher → Verbindungen im Raum.

Testklassen heissen `...Test` oder `...IntegrationTest`, damit Surefire sie ohne weiteres Plugin findet (wie in den anderen Modulen). Testhilfen (`Test…`, `KeycloakFlow`, `FixedValue`) liegen in der Paketwurzel der Tests, wie `TestDatabase` im `batch-writer`.

## Arbeitsweise

- **Form dieses Plans.** Jeder **Test steht vollständig** im Plan, mit seinen Kommentaren: Er legt das Verhalten fest und wird zuerst geschrieben. **Produktionscode steht nicht vollständig** im Plan, sondern als Datei mit Zweck, öffentlichen Signaturen und dem Verhalten Punkt für Punkt mit Verweis auf die Spezifikation, oft «nach dem Vorbild von `<Pfad>` im Archiv-Branch `origin/archiv/vorarbeit-2026-09-23`, geändert: …». Kurze Konfiguration (POM-Ausschnitte, `application.yml`, Compose-Ausschnitte, `.env.example`, Realm-Datei, Dockerfile, Workflow, Skripte der Abnahme) steht wörtlich da. *Warum:* Der Code steht so nur einmal, im Repository, und der Plan kann ihm nicht widersprechen. Genau das bemängelte die zweite Probe-Bewertung des `batch-writer` (Befund Z6: Codeblöcke im Plan zeigten ohne Hinweis einen anderen Stand als der Commit). Ändert eine spätere Aufgabe einen Test, der hier vollständig steht, nennt sein Block den Stand («Stand: Task 6. Task 7 ergänzt …»).
- **Der Plan entsteht in Etappen.** Jede Etappe wird committet, bevor die Aufgaben beginnen, die sie beschreibt. Die erste Etappe heisst «Task 1 und 2» (`75c0905`). Weil der Plan parallel zur Umsetzung weitergeschrieben wird, kann der Commit einer Aufgabe die Plandatei nicht mitnehmen.
- **Test zuerst, Rot lokal.** Jede Aufgabe beginnt mit dem Test aus dem Plan. Rot zeigen wir lokal, auch bei Tests mit Containern: Dieselben Testcontainers laufen auf dem eigenen Rechner (Docker Desktop). Die Grundlinie vom 02.10.2026: `mvn clean test` mit 14 + 29 Tests grün, `bash scripts/abnahme.sh` S2 bis S8 bestanden. *Warum nicht in GitHub Actions:* Jeder Probelauf dort kostet 3 bis 5 min Wartezeit, lokal läuft derselbe Test ohne sie. Es gibt deshalb keine Probe-Branches.
- **Ablauf je Aufgabe N** (`NN` zweistellig, Belege im Ordner `docs/belege/2026-10-02-baustein-1/`):
  1. Den Test aus dem Plan schreiben.
  2. Rot lokal zeigen. Befehl, Branch, Stand und der wichtige Ausschnitt der Ausgabe kommen in `task-NN-rot.txt`.
  3. Den Code schreiben und grün lokal zeigen, ebenso festgehalten in `task-NN-gruen.txt`.
  4. Fallen, Abweichungen vom Plan und Überraschungen in `notizen.md` eintragen.
  5. Committen: Test, Code, beide Belege und `notizen.md`, mit der Message aus dem Plan. Gestagt werden nur ausdrücklich genannte Pfade.
  6. `baustein/web-gateway` pushen. Grün muss es auch in GitHub Actions sein. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`. Vor dem nächsten Push wird geprüft, dass der vorige Lauf grün war.
- **Was nur dem roten Nachweis dient** (der Dummy-Dienst in Task 1, ein absichtlicher Fehler in Task 10), steht nur lokal und wird nie committet. Der Beleg zeigt, dass es wieder weg ist (`git diff --quiet -- <Datei>`).
- **Belege nicht nur unter `target/`.** `mvn clean` im Wurzelordner löscht `./target/` mit allen Zwischendateien, denn das Eltern-POM ist selbst ein Projekt. Was als Nachweis bleiben soll, steht im Beleg der Aufgabe.
- **Ein Commit pro Aufgabe** auf `baustein/web-gateway`. **Ausnahme Task 8** mit zwei Commits und eigenen Belegen je Commit (`task-08a-…`, `task-08b-…`): Der Healthcheck von RabbitMQ ist ein eigenes Thema (ein `fix:` für alle drei Dienste, die darauf warten), das erst mit dem Gateway nötig wird.
- **Häkchen und Links je Meilenstein.** Sie kommen in einem Sammel-Commit in diesen Plan, zusammen mit den Läufen und Fallen aus `notizen.md`: `docs: Plan, Häkchen, Läufe und Fallen bis Task N`.
- **Erst committen, dann pushen.** Ein CI-Lauf braucht einen Commit. Ist ein Lauf rot, folgt ein Korrektur-Commit mit eigener Zeile in `notizen.md`. Nichts wird umgeschrieben: kein `--amend`, kein `push --force`.
- **`main` nur per Fast-Forward** von `baustein/web-gateway` und nur mit grünem Lauf (docs/fahrplan.md 3): `git switch main`, `git merge --ff-only baustein/web-gateway`, `git push`. Tag `schritt-2` nach Task 10, Tag `schritt-3` nach Task 17 und der Abschlussprüfung.
- **Plan und `git log` bleiben deckungsgleich.** Jede Commit-Message steht im Plan. Die Commit-Übersicht am Ende nennt zu jedem Commit seinen Eintrag, seine Belege und seinen Lauf.

## Reihenfolge und warum

PLANUNG.md 6: «Auth zuerst, sonst wird es später nachträglich eingebaut und ist dann falsch.» Innerhalb des Logins von innen nach aussen: erst der Schutz der bestehenden Abnahme, dann Modul und Realm, dann Proxy, Anmeldung und Bearer, zuletzt Betrieb, Oberfläche und Abnahme. Danach derselbe Weg für den Chat.

| # | Aufgabe (Commit-Message) | Warum an dieser Stelle |
|---|---|---|
| 1 | `test: Abnahme von Bewertung 1 startet nur ihre vier Dienste` | Ab Task 4 verlangt `docker-compose.yml` ein Secret mit `:?`, ab Task 8 gibt es einen Port. Vorher muss `scripts/abnahme.sh` auf die vier Dienste von Bewertung 1 begrenzt sein, sonst wird der Job `abnahme` rot, ohne dass am `batch-writer` etwas falsch ist |
| 2 | `chore: Modul web-gateway anlegen` | Alles Weitere braucht ein übersetzbares Modul, auch der Test der Realm-Datei läuft darin. Die `COPY`-Zeilen gehören dazu, sonst bricht der Image-Bau der anderen Dienste im selben Moment. Das Dockerfile des Gateways entsteht gleich mit |
| 3 | `feat: Realm chat als JSON-Import für Keycloak` | Der innerste Vertrag des Logins: Clients, Redirect-URIs, PKCE, Benutzer, Claims. Mit Keycloak allein testbar, ganz ohne Gateway. P2, P3, P4 und P7 entscheiden sich hier und können die Realm-Datei noch ändern |
| 4 | `chore: Keycloak in docker-compose` | Healthcheck, Import und Issuer im Stack prüfen, solange noch kein Gateway mitspielt. Ein Fehler dort soll nicht erst zusammen mit dem Gateway auffallen |
| 5 | `feat: Gateway reicht den Realm chat an Keycloak durch` | Ohne Proxy erreicht der Browser das Login-Formular nicht, es gibt nur einen Port. Mit einem Mini-Server testbar, ohne Login. Die Sperre für Admin-Konsole und Realm `master` (W8) entsteht hier |
| 6 | `feat: Anmeldung über Keycloak, /api/me nennt den Benutzer` | Braucht den Realm (3) und den Proxy (5): Das Login-Formular kommt durch das Gateway |
| 7 | `feat: Gateway prüft Bearer-Tokens` | Der zweite Weg hinein, für Desktop-Client und Abnahme. Setzt die Einstiegspunkte aus 6 voraus (401 statt Umleitung) |
| 8 | `fix: RabbitMQ-Healthcheck prüft die Ports statt nur den Prozess`, dann `chore: web-gateway mit dem einzigen Port in docker-compose` | Erst wenn das Gateway allein richtig arbeitet, lohnt der Betrieb im Stack. Mit ihm warten drei Dienste darauf, dass RabbitMQ «gesund» ist |
| 9 | `feat: Web-UI zeigt den angemeldeten Benutzer` | Braucht `/api/me` (6) und das Gateway im Stack (8). Schliesst Schritt 2 aus PLANUNG.md 6 ab: «React zeigt den Benutzernamen» |
| 10 | `test: Systemabnahme Login (W1, W2, W3, W7, W8, W10)` | Prüft den Login wie die Abnahme, auf dem ganzen Stack. Danach Tag `schritt-2` |
| 11 | `feat: Sendeweg vom WebSocket zum chat-service` | Schritt 3 beginnt beim Senden; der Absender kommt aus der Anmeldung (6, 7) |
| 12 | `feat: Zustellweg von chat.delivery zum WebSocket` | Braucht die offenen Verbindungen aus 11. Erst jetzt kommt eine Nachricht beim zweiten Browser an |
| 13 | `feat: Web-UI chattet in der Lobby` | Braucht beide Wege (11, 12): Duplikate, Sortierung, Fehleranzeige (Spezifikation 1.2) |
| 14 | `test: Systemabnahme Chat (W4, W5, W6, W9, W11)` | Prüft den ganzen Weg mit `scripts/WebSocketProbe.java` auf dem Stack |
| 15 | `test: Kommentarprüfung auch für TypeScript` | Die Regel gilt für die Web-UI seit Task 9. Das Prüfskript prüft dann deren ganzen Code aus 9 und 13 auf einmal |
| 16 | `test: Zwei Browser chatten (Playwright, W12)` | Was ein Mensch sieht: zwei echte Browser. Braucht alles davor |
| 17 | `docs: README für Login, Gateway und Web-UI` | Beschreibt, was es jetzt wirklich gibt, deshalb zuletzt. Danach Abschlussprüfung und Tag `schritt-3` |

Task 1 bis 10 bilden den Meilenstein `schritt-2` (Login), Task 11 bis 17 den Meilenstein `schritt-3` (ein Weg durch).

---

## Task 1: Abnahme von Bewertung 1 startet nur ihre vier Dienste

**Warum an dieser Stelle:** Ab Task 4 enthält `docker-compose.yml` Keycloak mit `${KEYCLOAK_CLIENT_SECRET:?…}`, ab Task 8 das Gateway mit dem einzigen Port. S2 in `scripts/abnahme.sh` verlangt aber «4 Dienste laufen, 0 veröffentlichte Ports» und startet mit `docker compose up -d --build` alles. Deshalb startet das Skript ab jetzt nur die vier Dienste von Bewertung 1 (Spezifikation 6.3). Bewertet bleibt der Tag `bewertung-1`; die Änderung sorgt nur dafür, dass S2 bis S8 auch auf dem wachsenden Stand grün bleiben.

**Dateien:**
- Anlegen: `scripts/env-pruefen.sh`
- Ändern: `scripts/abnahme.sh` (Kopfkommentar, `SERVICES`, Prüfung der `.env`, S2, S6)
- Ändern: `docs/spec-batch-writer.md` (datierter Nachtrag am Ende von Abschnitt 6)
- Ändern: `README.md` (Zeile zu `abnahme.sh`)
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-01-rot.txt`, `task-01-gruen.txt`, `notizen.md`
- Test: lokale Prüfung von `env-pruefen.sh` mit Kopien von `.env.example` unter `target/env-test/`; roter Nachweis der Abnahme lokal, mit einem Dummy-Dienst in `docker-compose.yml`, der nie committet wird

**Schnittstellen:**
- Verbraucht: `scripts/abnahme.sh` im Stand `bewertung-1`, `.env.example`
- Stellt bereit: `bash scripts/env-pruefen.sh [ENV-DATEI] [BEISPIEL-DATEI]` (Vorgabe `.env` und `.env.example`) → Exit 0; sonst Exit 1 und die fehlenden Schlüssel auf stderr, nur Namen, nie Werte. `SERVICES` in `abnahme.sh`. Task 4 (neue Schlüssel) und Task 10 (`abnahme-system.sh`) verlassen sich darauf.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

Die Prüfung arbeitet mit Kopien von `.env.example` unter `target/` (in `.gitignore`), nie mit der echten `.env`: Die enthält Geheimnisse und wird nicht ausgegeben.

```bash
export MSYS_NO_PATHCONV=1
mkdir -p target/env-test
grep -v '^POSTGRES_DB=' .env.example > target/env-test/ohne-db
cp .env.example target/env-test/voll
bash scripts/env-pruefen.sh target/env-test/ohne-db; echo "Exit: $?"
bash scripts/env-pruefen.sh target/env-test/voll; echo "Exit: $?"
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: die Befehle aus Schritt 1
Erwartet: zweimal `bash: scripts/env-pruefen.sh: No such file or directory` und `Exit: 127`.
Beleg: `task-01-rot.txt`, Teil A.

- [ ] **Schritt 3: `scripts/env-pruefen.sh` anlegen**

```bash
#!/usr/bin/env bash
# Prüft, ob die lokale .env jeden Schlüssel aus .env.example enthält.
#
# Warum: docker-compose.yml verlangt manche Werte mit ${NAME:?…}. Fehlt einer
# in einer älteren .env, scheitert JEDER docker-compose-Befehl, auch "down"
# (spec-web-gateway.md 4.1). Dieses Skript sagt vorher, was fehlt. Es gibt
# nur Namen aus, nie Werte: Die .env enthält Geheimnisse.
#
# Aufruf im Wurzelverzeichnis:   bash scripts/env-pruefen.sh [ENV-DATEI] [BEISPIEL-DATEI]
#   Ohne Angaben: .env und .env.example.
# Exit-Code: 0, wenn nichts fehlt; 1, wenn mindestens ein Schlüssel fehlt.

env_file=${1:-.env}
example_file=${2:-.env.example}

missing=""
while IFS= read -r line; do
  # Ein Windows-Zeilenende (CR) gehört nicht zum Namen.
  line=${line%$'\r'}
  case "$line" in
    '' | '#'*) continue ;;
  esac
  key=${line%%=*}
  if ! grep -q "^$key=" "$env_file"; then
    missing="$missing $key"
  fi
done < "$example_file"

if [ -n "$missing" ]; then
  echo "In $env_file fehlen Schlüssel aus $example_file:$missing" >&2
  echo "Die Zeilen aus $example_file übernehmen und einen Wert setzen." >&2
  exit 1
fi
exit 0
```

- [ ] **Schritt 4: Test laufen lassen und grün bestätigen**

Ausführen: die Befehle aus Schritt 1
Erwartet:
```
In target/env-test/ohne-db fehlen Schlüssel aus .env.example: POSTGRES_DB
Die Zeilen aus .env.example übernehmen und einen Wert setzen.
Exit: 1
Exit: 0
```

Dazu drei Prüfungen, die nur der Beleg festhält:
- dieselben Aufrufe mit Kopien im Windows-Format (`sed 's/$/\r/' .env.example > target/env-test/beispiel-crlf`, ebenso für die `.env`): gleiche Exit-Codes, und im ausgegebenen Namen steht kein CR (`cat -A` zeigt kein `^M`);
- die echte `.env`: `bash scripts/env-pruefen.sh >/dev/null 2>&1; echo "Exit: $?"` → `Exit: 0`. Ausgegeben wird nur der Exit-Code, denn die Datei enthält Geheimnisse;
- `bash scripts/kommentare.sh scripts; echo "Exit: $?"` → `Exit: 0`.

Beleg: `task-01-gruen.txt`, Teil A.

- [ ] **Schritt 5: Rot für die Abnahme, lokal**

`scripts/abnahme.sh` ist noch im alten Stand. In `docker-compose.yml` steht **nur lokal**, nie committet, vor `networks:` ein fünfter Dienst mit Port, so wie später das Gateway:

```yaml
  # Nur lokal für den roten Nachweis: ein fünfter Dienst mit Port, wie später das Gateway.
  dummy:
    image: busybox:1.36
    command: ["sleep", "3600"]
    ports:
      - "127.0.0.1:18080:80"
    networks:
      - chat-net
```

Der Dummy betrifft nur S2. Ein voller Lauf spielte danach noch S3 bis S8 durch, ohne mehr zu zeigen. Deshalb läuft nur S2, in einer Kopie des alten Skripts, in der nur die sechs Aufrufe `scenario_s3` bis `scenario_s8` fehlen:

```bash
sed -e '/^scenario_s[3-8]$/d' scripts/abnahme.sh > target/abnahme-nur-s2.sh
diff scripts/abnahme.sh target/abnahme-nur-s2.sh      # nur diese sechs Zeilen
bash target/abnahme-nur-s2.sh; echo "Exit: $?"
```

Erwartet: S2 wartet 180 s auf genau vier Dienste und meldet `S2  FAIL gemessen: laufend: batch-writer chat-service dummy postgres rabbitmq | veröffentlichte Ports: 1 | erwartet: 4 Dienste laufen, 0 veröffentlichte Ports`, danach `Exit: 1`.
Beleg: `task-01-rot.txt`, Teil B, mit dem `diff`.

- [ ] **Schritt 6: `scripts/abnahme.sh` ändern**

Im Kopfkommentar nach dem ersten Absatz:

```bash
# Seit Baustein 1 startet das Skript nur die vier Dienste von Bewertung 1
# (SERVICES). Keycloak und web-gateway prüft scripts/abnahme-system.sh
# (spec-web-gateway.md 6.3).
```

Nach `cd "$(dirname "$0")/.." || exit 1`, an Stelle des bisherigen `if [ ! -f .env ] … fi`:

```bash
# Die vier Dienste von Bewertung 1; Keycloak und Gateway prüft abnahme-system.sh.
SERVICES="rabbitmq chat-service postgres batch-writer"

if [ ! -f .env ]; then
  cp .env.example .env
fi
# Fehlt einer älteren .env ein neuer Schlüssel, scheitert sonst jeder
# docker-compose-Befehl mit einer schwer lesbaren Meldung (spec-web-gateway.md 4.1).
bash scripts/env-pruefen.sh || exit 1
```

S2, die ersten Zeilen von `scenario_s2`:

```bash
scenario_s2() {
  echo "== S2: frischer Start, .env aus .env.example, docker compose up -d --build $SERVICES"
  docker compose down -v --remove-orphans >/dev/null 2>&1
  # $SERVICES ohne Anführungszeichen: Jeder Dienst wird ein eigenes Argument.
  docker compose up -d --build $SERVICES
```

S6, der Anfang von `scenario_s6` bis vor `local consumers`:

```bash
scenario_s6() {
  echo "== S6: zwei Instanzen, 1000 Nachrichten"
  # Nur den batch-writer nennen, sonst startete "up" auch Keycloak und Gateway.
  docker compose up -d --scale batch-writer=2 batch-writer
  wait_until 90 consumers_are 2
  # Bis Bewertung 1 erstellte dieses "up" auch den chat-service neu. Das Warten,
  # bis er antwortet, schadet nicht und bleibt als Sicherheit.
  wait_until 120 chat_service_answers
```

> **Falle 1 – Anführungszeichen:** `"$SERVICES"` wäre EIN Argument mit Leerzeichen, und Compose
> meldet `no such service`. Deshalb ohne Anführungszeichen, mit Kommentar.
>
> **Falle 2 – `up` mit Dienstnamen:** `up -d batch-writer` startet auch, wovon der `batch-writer`
> abhängt (`rabbitmq`, `postgres`), aber nie Keycloak oder Gateway. Genau das will S6.

- [ ] **Schritt 7: Grün lokal, zweimal**

Ausführen: `bash scripts/abnahme.sh; echo "Exit: $?"`, zuerst **mit** dem Dummy-Dienst, dann ohne ihn.
Erwartet:
- *mit Dummy:* S2 bis S8 `PASS`, `Exit: 0`. S2 meldet `laufend: batch-writer chat-service postgres rabbitmq | veröffentlichte Ports: 0`; der Dummy-Dienst startet nie, auch nicht in S6.
- *Endstand ohne Dummy:* Den Dienst wieder entfernen, `git diff --quiet -- docker-compose.yml; echo "Exit: $?"` → `Exit: 0`. Der zweite Lauf prüft genau das, was committet wird: wieder S2 bis S8 `PASS`.

Beleg: `task-01-gruen.txt`, Teil B (mit Dummy) und Teil C (Endstand).

- [ ] **Schritt 8: Nachtrag und README**

`docs/spec-batch-writer.md`, am Ende von Abschnitt 6 (nach der Tabelle des Abnahmeprotokolls, vor `---`), mit dem Datum des Commits:

```markdown
**Nachtrag vom TT.MM.2026:** Seit Baustein 1 startet `scripts/abnahme.sh` nur die vier Dienste
von Bewertung 1 (`SERVICES`), siehe [spec-web-gateway.md 6.3](spec-web-gateway.md#63-auswirkung-auf-bewertung-1).
Die Kriterien S1 bis S8 bleiben gleich.
```

`README.md`, im Block «Bauen, testen, starten» die Zeile zu `abnahme.sh`:

```bash
bash scripts/abnahme.sh          # S2 bis S8 von Bewertung 1, nur deren vier Dienste (beginnt mit "down -v"!)
```

- [ ] **Schritt 9: Committen**

```bash
git add scripts/abnahme.sh scripts/env-pruefen.sh docs/spec-batch-writer.md README.md \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-01-rot.txt docs/belege/2026-10-02-baustein-1/task-01-gruen.txt
git commit -m "test: Abnahme von Bewertung 1 startet nur ihre vier Dienste" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Erwartet: `git show --stat HEAD` nennt `docker-compose.yml` und den Plan nicht.

- [ ] **Schritt 10: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `images` und `abnahme` grün; `abnahme` zeigt `== S2: frischer Start, .env aus .env.example, docker compose up -d --build rabbitmq chat-service postgres batch-writer`. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 2: Modul web-gateway anlegen

**Warum an dieser Stelle:** Alles Weitere braucht ein übersetzbares Modul, auch der Test der Realm-Datei (Task 3) läuft darin. Die `COPY`-Zeilen in den Dockerfiles von `chat-service` und `batch-writer` gehören in denselben Commit: Sobald das Eltern-POM das neue Modul nennt, bricht der Image-Bau der beiden anderen Dienste. Das Dockerfile des Gateways entsteht gleich mit (Maven- und JRE-Stufe). Der Job `images` baut es erst, wenn der Dienst in `docker-compose.yml` steht (Task 8); bis dahin zeigt ein lokaler Bau, dass es nicht nur behauptet ist.

**Dateien:**
- Ändern: `pom.xml` (Modulliste, Stückliste Spring Cloud)
- Ändern: `chat-service/Dockerfile`, `batch-writer/Dockerfile` (je eine `COPY`-Zeile)
- Anlegen: `web-gateway/pom.xml`
- Anlegen: `web-gateway/Dockerfile` (Maven- und JRE-Stufe; die Stufe für die Web-UI kommt in Task 9)
- Anlegen: `web-gateway/src/main/java/ch/benedict/m321/webgateway/WebGatewayApplication.java`
- Anlegen: `web-gateway/src/main/resources/application.yml`
- Anlegen: `web-gateway/src/test/resources/gateway-test.properties`
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-02-rot.txt`, `task-02-gruen.txt`; Ändern: `notizen.md`
- Test: `web-gateway/src/test/java/ch/benedict/m321/webgateway/WebGatewayApplicationTest.java`; roter Nachweis lokal für die `COPY`-Zeilen (`docker compose build`); lokaler Bau des Gateway-Images

**Schnittstellen:**
- Verbraucht: Eltern-POM
- Stellt bereit: Paketwurzel `ch.benedict.m321.webgateway`, Artefakt `ch.benedict.m321:web-gateway:0.1.0-SNAPSHOT`; `web-gateway/Dockerfile`, das Task 8 in `docker-compose.yml` einbindet und Task 9 um die Web-UI ergänzt; ein Spring-Kontext, der ohne Keycloak, RabbitMQ und `chat-service` startet; `gateway-test.properties` mit den vier Variablen aus Spezifikation 4.1, alle auf Adressen ohne Dienst. Jede Spring-Testklasse bindet sie mit `@TestPropertySource(locations = "classpath:gateway-test.properties")` ein und überschreibt nur, was sie selbst braucht.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/WebGatewayApplicationTest.java`

```java
package ch.benedict.m321.webgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Prüft, dass der Spring-Kontext des Gateways hochfährt, OHNE dass Keycloak,
 * RabbitMQ oder der chat-service erreichbar sind.
 *
 * Darauf verlässt sich der Betrieb (Spezifikation F1 und 3.5): Das Gateway
 * braucht beim Start keinen dieser Dienste. Die Adressen kommen aus
 * gateway-test.properties und zeigen auf einen Port, an dem niemand zuhört.
 * Verlangt eine spätere Aufgabe beim Start einen Dienst, wird dieser Test rot.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:gateway-test.properties")
class WebGatewayApplicationTest {

    /**
     * Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring eine
     * Exception und der Test wird rot.
     */
    @Test
    void contextLoadsWithoutKeycloakBrokerAndChatService() {
    }
}
```

`web-gateway/src/test/resources/gateway-test.properties`

```properties
# Umgebung für Spring-Tests, die keinen anderen Dienst brauchen. Es sind die
# vier Variablen aus spec-web-gateway.md 4.1, die im Betrieb docker-compose.yml
# und .env setzen. Auf localhost:9 hört niemand zu: Ein Aufruf scheitert sofort,
# statt auf eine Antwort zu warten. Braucht ein Test einen Dienst, setzt er
# dessen Adresse selbst (@DynamicPropertySource).
PUBLIC_URL=http://localhost:8080
KEYCLOAK_INTERNAL_URL=http://localhost:9/auth
KEYCLOAK_CLIENT_SECRET=test-secret
CHAT_SERVICE_URL=http://localhost:9
```

Die Datei nennt schon jetzt alle vier Variablen, damit keine spätere Aufgabe alle Testklassen anfassen muss, wenn das Gateway eine weitere davon liest.

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl web-gateway test`
Erwartet: Fehlschlag — `Could not find the selected project in the reactor: web-gateway`, das Modul gibt es noch nicht.
Beleg: `task-02-rot.txt`, Teil A.

- [ ] **Schritt 3: Modul und Stückliste im Eltern-POM eintragen**

`pom.xml`, an Stelle der bisherigen Modulliste:

```xml
    <!-- Weitere Dienste kommen hier dazu: load-generator. -->
    <modules>
        <module>chat-service</module>
        <module>batch-writer</module>
        <module>web-gateway</module>
    </modules>

    <!-- Spring Cloud gehört nicht zum Eltern-POM von Spring Boot. Diese
         Stückliste (BOM) legt die Versionen aller Spring-Cloud-Bausteine fest,
         passend zu Spring Boot 3.5. Nur das web-gateway benutzt Spring Cloud,
         für den Proxy unter /auth. -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>2025.0.3</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

- [ ] **Schritt 4: Modul-POM anlegen**

`web-gateway/pom.xml` — nach dem Vorbild von `web-gateway/pom.xml` im Archiv, geändert: ohne `testcontainers-keycloak` (Keycloak läuft im Test als `GenericContainer`, Spezifikation 4.4), Kommentare an jeder Abhängigkeit.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ch.benedict.m321</groupId>
        <artifactId>it3c-m321</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>web-gateway</artifactId>
    <name>web-gateway</name>
    <description>Der einzige offene Port: Login über Keycloak, Proxy auf /auth, Web-UI, WebSocket</description>

    <dependencies>
        <!-- Web-UI ausliefern, /api/me -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Login: das Gateway ist OIDC-Client von Keycloak, die Anmeldung liegt in der Session -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-client</artifactId>
        </dependency>
        <!-- Bearer-JWTs für Desktop-Client und Abnahme, lokal im Speicher geprüft -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>

        <!-- Proxy: reicht zwei Pfade unter /auth an Keycloak im Docker-Netz durch -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webmvc</artifactId>
        </dependency>

        <!-- Sendeweg: der Browser schickt Nachrichten über WebSocket -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>

        <!-- Zustellweg: das Gateway liest chat.delivery (Consumer, kein Producer) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <!-- Lombok erzeugt Logger und Konstruktoren beim Uebersetzen.
             "optional" heisst: nur wir brauchen es, niemand der uns benutzt. -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- oidcLogin() und jwt(): angemeldete Benutzer in MockMvc-Tests -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- Startet im Test echte Container: Keycloak (als GenericContainer) und RabbitMQ -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <!-- Lombok wird nur zum Uebersetzen gebraucht und
                             gehoert nicht ins ausgelieferte Jar. -->
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Schritt 5: Hauptklasse anlegen**

`web-gateway/src/main/java/ch/benedict/m321/webgateway/WebGatewayApplication.java` — nach dem Vorbild von `web-gateway/src/main/java/ch/benedict/m321/webgateway/WebGatewayApplication.java` im Archiv, geändert:
- `@SpringBootApplication public class WebGatewayApplication` mit `public static void main(String[] args)`, die nur `SpringApplication.run(WebGatewayApplication.class, args)` aufruft;
- der Javadoc der Klasse sagt, warum es das Gateway gibt: einziger Port, Login über Keycloak, Proxy unter `/auth`, Web-UI, später Senden und Zustellen (Spezifikation 1.2). Räume und Queue-Tiefe der Vorarbeit fallen weg (Spezifikation 1.3);
- **Javadoc auch über `main`** (fehlte in der Vorarbeit): Start von Spring, den Rest finden Konfiguration und Controller selbst;
- noch ohne `@ConfigurationPropertiesScan`, die erste Eigenschaft kommt in Task 5.

- [ ] **Schritt 6: Konfiguration anlegen**

`web-gateway/src/main/resources/application.yml` — die Adressen von Keycloak und `chat-service` kommen mit den Aufgaben, die sie brauchen (5, 6, 11).

```yaml
spring:
  application:
    name: web-gateway
  rabbitmq:
    # Im Docker-Netz heisst der Broker "rabbitmq". Beim Start ausserhalb
    # von Docker greift der Vorgabewert "localhost".
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}

server:
  port: 8080

logging:
  level:
    # Im Unterricht wollen wir jeden Schritt sehen.
    ch.benedict.m321: DEBUG
```

- [ ] **Schritt 7: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl web-gateway test`
Erwartet: `WebGatewayApplicationTest` grün.
Beleg: `task-02-gruen.txt`, Teil A.

> **Falle – keine Zeile `Using generated security password`:** Ohne eigene Regeln greift zwar die
> Standard-Filterkette von Spring Security (`SpringBootWebSecurityConfiguration`): Jede Anfrage
> verlangt eine Anmeldung. Einen Benutzer dafür legt Spring Boot aber nicht an. Der
> Bedingungsbericht (`mvn -q -pl web-gateway test -Ddebug=true`) sagt warum:
> `UserDetailsServiceAutoConfiguration` greift nicht, weil `ClientRegistrationRepository` und
> `OpaqueTokenIntrospector` auf dem Klassenpfad liegen (OAuth2-Client und Resource Server). Bis
> Task 5 kommt also niemand hinein; am Test ändert das nichts.

- [ ] **Schritt 8: Dockerfile des Gateways, lokal gebaut und gestartet**

`web-gateway/Dockerfile` — nach dem Vorbild von `web-gateway/Dockerfile` im Archiv, vorerst ohne die Stufe für die Web-UI (Task 9) und nur mit den POMs der Module, die es gibt:

```dockerfile
# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Maven liest ALLE Module aus dem Eltern-POM, auch die, die hier nicht
# gebaut werden. Deshalb müssen auch die POMs von chat-service und
# batch-writer da sein.
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY web-gateway/pom.xml web-gateway/pom.xml
COPY web-gateway/src web-gateway/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl web-gateway -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/web-gateway/target/web-gateway-0.1.0-SNAPSHOT.jar app.jar
# EXPOSE dokumentiert den Port nur. Veröffentlicht wird er in
# docker-compose.yml, als einziger Port des Gesamtsystems (127.0.0.1:8080).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Der Dienst steht noch nicht in `docker-compose.yml` (Task 8), deshalb direkt mit `docker build`. Der kurze Start zeigt, dass das Jar wirklich hochfährt; ohne `-p` gibt es keinen Port nach aussen:

```bash
docker build -f web-gateway/Dockerfile -t it3c-m321-web-gateway:task-02 .; echo "Exit: $?"
docker run -d --name t2-web-gateway-probe it3c-m321-web-gateway:task-02
docker logs t2-web-gateway-probe | grep -E 'Tomcat started|Started WebGatewayApplication'
docker rm -f t2-web-gateway-probe
```

Erwartet: `Exit: 0`; nach wenigen Sekunden `Tomcat started on port 8080` und `Started WebGatewayApplication`, ohne Zeile mit ` WARN `, ` ERROR ` oder `Exception`.
Beleg: `task-02-gruen.txt`, Teil D.

- [ ] **Schritt 9: Rot für die `COPY`-Zeilen, lokal**

Stand: Eltern-POM, `web-gateway/pom.xml`, `web-gateway/src` und `web-gateway/Dockerfile` sind da, die Dockerfiles von `chat-service` und `batch-writer` noch unverändert. Lokal entfällt `cp .env.example .env`, die lokale `.env` hat alle Schlüssel (Task 1).

Ausführen: `docker compose build; echo "Exit: $?"`
Erwartet: Der Bau von `chat-service` und `batch-writer` bricht ab mit `Child module /build/web-gateway of /build/pom.xml does not exist`, der Exit-Code ist nicht 0.
Beleg: `task-02-rot.txt`, Teil B.

- [ ] **Schritt 10: `COPY`-Zeilen ergänzen**

`chat-service/Dockerfile`, nach `COPY batch-writer/pom.xml batch-writer/pom.xml`, und der Kommentar darüber:

```dockerfile
# Maven liest ALLE Module aus dem Eltern-POM, auch die, die hier nicht
# gebaut werden. Deshalb müssen auch die POMs von batch-writer und
# web-gateway da sein.
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY web-gateway/pom.xml web-gateway/pom.xml
```

`batch-writer/Dockerfile`, an Stelle der zwei Zeilen vor `COPY chat-service/pom.xml chat-service/pom.xml` und dieser Zeile:

```dockerfile
# Maven liest ALLE Module aus dem Eltern-POM, auch die, die hier nicht
# gebaut werden. Deshalb müssen auch die POMs von chat-service und
# web-gateway da sein.
COPY chat-service/pom.xml chat-service/pom.xml
COPY web-gateway/pom.xml web-gateway/pom.xml
```

Ausführen: `docker compose build; echo "Exit: $?"`
Erwartet: `Exit: 0`, die Images von `chat-service` und `batch-writer` bauen wieder.
Beleg: `task-02-gruen.txt`, Teil C.

> **Falle:** Am `chat-service` und am `batch-writer` ändert sich nichts, und trotzdem baut ihr Image
> nicht mehr: `mvn -pl … -am` liest das Eltern-POM, und das nennt jetzt drei Module. Genau diesen
> Fehler zeigt der rote Nachweis.

- [ ] **Schritt 11: Alle Module und die Code-Regeln, lokal**

```bash
mvn -B clean test; echo "Exit: $?"
bash scripts/kommentare.sh web-gateway/src; echo "Exit: $?"
grep -rin --exclude-dir=target --exclude-dir=node_modules --exclude-dir=dist stream web-gateway/
grep -rln -- '->' web-gateway/src
grep -rn '::' web-gateway/src
```

Erwartet: `BUILD SUCCESS` mit 14 (chat-service) + 29 (batch-writer) + 1 (web-gateway) = 44 Tests, 0 Failures, 0 Errors; `kommentare.sh` mit `Exit: 0`; die drei Suchen ohne Treffer. Gegenprobe zur Kommentarprüfung: In einer Kopie von `web-gateway/src` ohne den Javadoc über `main` meldet `kommentare.sh` genau diese Zeile und `Exit: 1`.
Beleg: `task-02-gruen.txt`, Teil B und E.

- [ ] **Schritt 12: Committen**

```bash
git add pom.xml chat-service/Dockerfile batch-writer/Dockerfile web-gateway \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-02-rot.txt docs/belege/2026-10-02-baustein-1/task-02-gruen.txt
git commit -m "chore: Modul web-gateway anlegen" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Erwartet: `git show --stat HEAD` nennt den Plan nicht und nichts unter `web-gateway/target/` (steht in `.gitignore`).

- [ ] **Schritt 13: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven` (jetzt mit `WebGatewayApplicationTest`), `images` und `abnahme` grün. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 3: Realm chat als JSON-Import für Keycloak

**Warum an dieser Stelle:** Der Realm ist der innerste Vertrag des Logins: welche Clients es gibt, wohin Keycloak zurückleiten darf, dass PKCE Pflicht ist, welche Benutzer mit welchen Rollen und welche Claims im Token stehen (Spezifikation 2.6, 4.2). Er ist mit Keycloak allein testbar, ganz ohne Gateway. Vier offene Prüfungen (P2, P3, P4, P7) entscheiden sich hier und können die Realm-Datei noch ändern, bevor etwas auf ihr aufbaut.

**Dateien:**
- Anlegen: `keycloak/realm-chat.json`
- Anlegen (Testhilfen): `web-gateway/src/test/java/ch/benedict/m321/webgateway/TestKeycloak.java`, `TestBrowser.java`, `KeycloakFlow.java`
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-03-rot.txt`, `task-03-gruen.txt`; Ändern: `notizen.md`
- Test: `web-gateway/src/test/java/ch/benedict/m321/webgateway/RealmImportIntegrationTest.java`, lokal mit Testcontainers

**Schnittstellen:**
- Verbraucht: Modul aus Task 2, Image `quay.io/keycloak/keycloak:26.7.3`
- Stellt bereit: Realm `chat` mit den Clients `web-gateway` (vertraulich, Secret `${KEYCLOAK_CLIENT_SECRET}`) und `desktop-client` (öffentlich), Mapper `roles`, Benutzer `alice`, `bob`, `admin` mit festen IDs (Spezifikation 4.2). Für spätere Tests: `TestKeycloak.createContainer(String publicUrl)` → `GenericContainer<?>` (noch nicht gestartet), `TestKeycloak.url(container)` → `http://localhost:<Port>/auth`, `TestKeycloak.CLIENT_SECRET`; `TestBrowser` (`get`, `postForm`, `follow`, `location`, `formAction`, `cookieNamesForRootPath`, `cookieValue`); `KeycloakFlow` (`newVerifier`, `challengeFor`, `authorizationUrl`, `codeFrom`, `redeemCode`, `tokenOf`, `claimsOf`).

- [ ] **Schritt 1: Testhilfen schreiben (Container, Browser, Code-Flow)**

`TestKeycloak` nach dem Muster von `batch-writer/src/test/java/ch/benedict/m321/batchwriter/TestDatabase.java`.

`web-gateway/src/test/java/ch/benedict/m321/webgateway/TestKeycloak.java`

```java
package ch.benedict.m321.webgateway;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Ein Keycloak für die Tests, eingerichtet mit der ECHTEN Realm-Datei.
 *
 * keycloak/realm-chat.json wird an dieselbe Stelle kopiert, an der
 * docker-compose.yml sie einbindet, und Keycloak startet mit denselben
 * Einstellungen wie im Betrieb (Spezifikation 4.3). So prüft jeder Test die
 * Realm-Datei mit, und es gibt keine zweite Kopie davon.
 */
public final class TestKeycloak {

    /** Secret des Clients web-gateway in allen Tests. Im Betrieb kommt es aus .env. */
    public static final String CLIENT_SECRET = "test-secret";

    /** Dasselbe Image wie in docker-compose.yml. */
    private static final String IMAGE = "quay.io/keycloak/keycloak:26.7.3";

    /** Die Tests laufen im Ordner web-gateway, die Realm-Datei liegt eine Ebene höher. */
    private static final String REALM_FILE = "../keycloak/realm-chat.json";

    /** Hier sucht "--import-realm" nach Realm-Dateien (wie in docker-compose.yml). */
    private static final String IMPORT_TARGET = "/opt/keycloak/data/import/realm-chat.json";

    /** Port für alle Anfragen an Keycloak. */
    private static final int HTTP_PORT = 8080;

    /** Port des Health-Endpunkts; nur dort gibt es /auth/health/ready (E2). */
    private static final int MANAGEMENT_PORT = 9000;

    /** Diese Klasse sammelt nur Hilfsmethoden und wird nie erzeugt. */
    private TestKeycloak() {
    }

    /**
     * Baut den Container, noch nicht gestartet. publicUrl ist die Adresse, unter
     * der Browser das Gateway sehen: Daraus bildet Keycloak den Issuer und alle
     * Adressen in Seiten und Umleitungen (KC_HOSTNAME, E3), und die Realm-Datei
     * setzt sie für ${PUBLIC_URL} ein. Die Demo-Passwörter bleiben bei den
     * Beispielwerten der Realm-Datei, solange ein Test sie nicht setzt.
     *
     * Bereit ist Keycloak erst, wenn /auth/health/ready mit 200 antwortet, wie
     * beim Healthcheck in docker-compose.yml. Drei Minuten Geduld, weil ein
     * Runner von GitHub langsamer ist als der Rechner aus E1 (32 s).
     */
    public static GenericContainer<?> createContainer(String publicUrl) {
        DockerImageName image = DockerImageName.parse(IMAGE);
        MountableFile realm = MountableFile.forHostPath(REALM_FILE);
        HttpWaitStrategy ready = Wait.forHttp("/auth/health/ready")
                .forPort(MANAGEMENT_PORT)
                .forStatusCode(200);
        Duration patience = Duration.ofMinutes(3);
        ready.withStartupTimeout(patience);

        GenericContainer<?> container = new GenericContainer<>(image);
        container.withCommand("start-dev", "--import-realm");
        container.withCopyFileToContainer(realm, IMPORT_TARGET);
        container.withEnv("KC_HTTP_RELATIVE_PATH", "/auth");
        container.withEnv("KC_HOSTNAME", publicUrl + "/auth");
        container.withEnv("KC_HEALTH_ENABLED", "true");
        container.withEnv("PUBLIC_URL", publicUrl);
        container.withEnv("KEYCLOAK_CLIENT_SECRET", CLIENT_SECRET);
        container.withExposedPorts(HTTP_PORT, MANAGEMENT_PORT);
        container.waitingFor(ready);
        return container;
    }

    /** Adresse, unter der der Test Keycloak direkt erreicht, mit /auth am Ende. */
    public static String url(GenericContainer<?> container) {
        String host = container.getHost();
        Integer port = container.getMappedPort(HTTP_PORT);
        return "http://" + host + ":" + port + "/auth";
    }
}
```

`web-gateway/src/test/java/ch/benedict/m321/webgateway/TestBrowser.java`

```java
package ch.benedict.m321.webgateway;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein Browser für Tests, gebaut aus dem HTTP-Client des JDK.
 *
 * Er merkt sich Cookies wie ein Browser, folgt Umleitungen aber nur, wenn der
 * Test follow() aufruft. So kann ein Test jede einzelne Antwort prüfen, auch
 * die Umleitungen dazwischen (Spezifikation 3.1). Jeder neue TestBrowser
 * beginnt ohne Cookies, wie ein frisches privates Fenster.
 */
public final class TestBrowser {

    /** Ziel des ersten Formulars einer Seite, z. B. des Login-Formulars von Keycloak. */
    private static final Pattern FORM_ACTION = Pattern.compile("action=\"([^\"]*)\"");

    /** Länger wartet kein Test auf eine einzelne Antwort. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Mehr Umleitungen hintereinander deuten auf eine Schleife. */
    private static final int MAX_REDIRECTS = 10;

    private final CookieManager cookieManager;
    private final HttpClient client;

    /** Ein Browser ohne Cookies, der Umleitungen nicht von selbst folgt. */
    public TestBrowser() {
        cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /** GET, wie beim Eintippen einer Adresse. */
    public HttpResponse<String> get(String url) throws IOException, InterruptedException {
        Map<String, String> noHeaders = Map.of();
        return get(url, noHeaders);
    }

    /** GET mit zusätzlichen Headern, z. B. Authorization, Cookie oder X-Forwarded-Host. */
    public HttpResponse<String> get(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        URI address = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(address);
        builder.timeout(TIMEOUT);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        builder.GET();
        HttpRequest request = builder.build();
        return send(request);
    }

    /** POST eines Formulars, wie nach einem Klick auf «Anmelden». */
    public HttpResponse<String> postForm(String url, Map<String, String> fields)
            throws IOException, InterruptedException {
        URI address = URI.create(url);
        String body = formBody(fields);
        HttpRequest.BodyPublisher content = HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(address)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(content)
                .build();
        return send(request);
    }

    /** Folgt Umleitungen, bis eine Antwort keine Umleitung mehr ist. */
    public HttpResponse<String> follow(HttpResponse<String> response) throws IOException, InterruptedException {
        HttpResponse<String> current = response;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            String target = location(current);
            if (target.isEmpty()) {
                return current;
            }
            current = get(target);
        }
        URI start = response.uri();
        throw new IllegalStateException("Mehr als " + MAX_REDIRECTS + " Umleitungen ab " + start);
    }

    /** Namen der Cookies mit dem Pfad "/" (Spezifikation W3: dort steht nur JSESSIONID). */
    public List<String> cookieNamesForRootPath() {
        CookieStore store = cookieManager.getCookieStore();
        List<HttpCookie> cookies = store.getCookies();
        List<String> names = new ArrayList<>();
        for (HttpCookie cookie : cookies) {
            String path = cookie.getPath();
            if ("/".equals(path)) {
                names.add(cookie.getName());
            }
        }
        return names;
    }

    /** Wert eines gespeicherten Cookies, oder null, wenn es keines mit diesem Namen gibt. */
    public String cookieValue(String name) {
        CookieStore store = cookieManager.getCookieStore();
        List<HttpCookie> cookies = store.getCookies();
        for (HttpCookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Ziel einer Umleitung als vollständige Adresse; leer, wenn die Antwort keine
     * Umleitung ist. Tomcat schreibt Ziele wie "/" ohne Host, deshalb wird gegen
     * die Adresse der Anfrage aufgelöst.
     */
    public static String location(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 300 || status > 399) {
            return "";
        }
        HttpHeaders headers = response.headers();
        Optional<String> location = headers.firstValue("Location");
        if (location.isEmpty()) {
            return "";
        }
        URI base = response.uri();
        URI target = base.resolve(location.get());
        return target.toString();
    }

    /** Ziel des ersten Formulars einer Seite; im HTML steht "&" als "&amp;". */
    public static String formAction(String html) {
        Matcher matcher = FORM_ACTION.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("Kein Formular auf der Seite");
        }
        String action = matcher.group(1);
        return action.replace("&amp;", "&");
    }

    /** Schickt die Anfrage und liest die Antwort als Text. */
    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse.BodyHandler<String> asText = HttpResponse.BodyHandlers.ofString();
        return client.send(request, asText);
    }

    /** Baut "name=wert&name=wert", jeder Teil URL-kodiert wie in einem Browser. */
    private static String formBody(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            String name = URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8);
            String value = URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8);
            body.append(name);
            body.append('=');
            body.append(value);
        }
        return body.toString();
    }
}
```

`web-gateway/src/test/java/ch/benedict/m321/webgateway/KeycloakFlow.java`

```java
package ch.benedict.m321.webgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Schritte des Authorization Code Flow mit PKCE, so wie ein Client sie
 * macht (Spezifikation 3.1, Punkt 5). Die Tests holen damit echte Tokens von
 * Keycloak: direkt beim Container (Task 3) und über das Gateway (Task 7).
 *
 * PKCE in zwei Sätzen: Der Client denkt sich ein Geheimnis aus (verifier) und
 * schickt beim Login nur dessen Hash (challenge). Beim Einlösen des Codes zeigt
 * er das Geheimnis selbst; wer nur den Code abfängt, kann ihn nicht einlösen.
 */
public final class KeycloakFlow {

    /** Liest die JSON-Antworten von Keycloak. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Der Code steht in der Umleitung als Parameter "code". */
    private static final Pattern CODE = Pattern.compile("[?&]code=([^&]+)");

    /** Diese Klasse sammelt nur Hilfsmethoden und wird nie erzeugt. */
    private KeycloakFlow() {
    }

    /** Ein neues Geheimnis für PKCE: 32 Zufallsbytes, Base64url ohne "=". */
    public static String newVerifier() {
        byte[] random = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(random);
        Base64.Encoder urlEncoder = Base64.getUrlEncoder();
        Base64.Encoder encoder = urlEncoder.withoutPadding();
        return encoder.encodeToString(random);
    }

    /** Der Hash des Geheimnisses, den Keycloak beim Login bekommt (Methode S256). */
    public static String challengeFor(String verifier) throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] verifierBytes = verifier.getBytes(StandardCharsets.US_ASCII);
        byte[] hash = sha256.digest(verifierBytes);
        Base64.Encoder urlEncoder = Base64.getUrlEncoder();
        Base64.Encoder encoder = urlEncoder.withoutPadding();
        return encoder.encodeToString(hash);
    }

    /**
     * Adresse, mit der ein Client den Login startet. keycloakUrl endet mit /auth.
     * Ist challenge null, fehlen die PKCE-Parameter: So prüfen Tests, dass
     * Keycloak eine Anfrage ohne PKCE ablehnt.
     */
    public static String authorizationUrl(String keycloakUrl, String clientId, String redirectUri, String challenge) {
        String encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        String url = keycloakUrl + "/realms/chat/protocol/openid-connect/auth"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&scope=openid%20profile%20email"
                + "&state=test-state"
                + "&redirect_uri=" + encodedRedirect;
        if (challenge == null) {
            return url;
        }
        return url + "&code_challenge=" + challenge + "&code_challenge_method=S256";
    }

    /** Liest den Code aus der Umleitung zurück zum Client. */
    public static String codeFrom(String location) {
        Matcher matcher = CODE.matcher(location);
        if (!matcher.find()) {
            throw new IllegalStateException("Kein code in der Umleitung: " + location);
        }
        return matcher.group(1);
    }

    /**
     * Löst den Code beim Token-Endpunkt ein, wie es der Client nach der
     * Rückleitung tut. clientSecret ist null beim öffentlichen desktop-client.
     */
    public static HttpResponse<String> redeemCode(TestBrowser browser, String keycloakUrl, String clientId,
                                                  String clientSecret, String redirectUri, String code,
                                                  String verifier) throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "authorization_code");
        fields.put("client_id", clientId);
        if (clientSecret != null) {
            fields.put("client_secret", clientSecret);
        }
        fields.put("redirect_uri", redirectUri);
        fields.put("code", code);
        fields.put("code_verifier", verifier);
        String tokenUrl = keycloakUrl + "/realms/chat/protocol/openid-connect/token";
        return browser.postForm(tokenUrl, fields);
    }

    /** Ein Token aus der Antwort des Token-Endpunkts, z. B. "access_token" oder "id_token". */
    public static String tokenOf(HttpResponse<String> tokenResponse, String tokenName) throws IOException {
        JsonNode tokens = JSON.readTree(tokenResponse.body());
        JsonNode token = tokens.get(tokenName);
        if (token == null) {
            throw new IllegalStateException("Kein " + tokenName + " in: " + tokenResponse.body());
        }
        return token.asText();
    }

    /**
     * Die Claims eines Tokens. Ein JWT hat drei Teile, durch Punkte getrennt;
     * der mittlere ist JSON in Base64url. Die Signatur prüft hier niemand: Der
     * Test hat das Token gerade selbst von Keycloak geholt.
     */
    public static JsonNode claimsOf(HttpResponse<String> tokenResponse, String tokenName) throws IOException {
        String compact = tokenOf(tokenResponse, tokenName);
        String[] parts = compact.split("\\.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        byte[] payload = decoder.decode(parts[1]);
        return JSON.readTree(payload);
    }
}
```

- [ ] **Schritt 2: Den fehlschlagenden Test schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/RealmImportIntegrationTest.java`

```java
package ch.benedict.m321.webgateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die ECHTE Realm-Datei keycloak/realm-chat.json in einem echten Keycloak
 * (Spezifikation 2.6 und 4.2, offene Prüfungen P2, P3, P4 und P7).
 *
 * Ohne Gateway: Der Test spricht Keycloak direkt über den Port an, den
 * Testcontainers nach aussen legt. Keycloak bekommt aber dieselbe öffentliche
 * Adresse wie im Betrieb (http://localhost:8080). Seiten und Umleitungen zeigen
 * deshalb dorthin; wo der Test einer solchen Adresse folgen muss (Ziel des
 * Login-Formulars), setzt er die Adresse des Containers ein.
 */
@Testcontainers
class RealmImportIntegrationTest {

    /** Öffentliche Adresse wie in docker-compose.yml. */
    private static final String PUBLIC_URL = "http://localhost:8080";

    /** So steht der Issuer in jedem Token (E3). */
    private static final String ISSUER = PUBLIC_URL + "/auth/realms/chat";

    /** Die einzige Redirect-URI des Clients web-gateway. */
    private static final String GATEWAY_REDIRECT = PUBLIC_URL + "/login/oauth2/code/keycloak";

    /** Rückleitung des Desktop-Clients, mit dem Port aus der Abnahme (Spezifikation 6.1). */
    private static final String DESKTOP_REDIRECT = "http://127.0.0.1:53682/callback";

    /** Dieses Passwort bekommt bob über die Umgebung statt des Beispielwerts. */
    private static final String BOB_PASSWORD = "bob-aus-dem-test";

    /** Feste IDs aus der Realm-Datei (Spezifikation 4.2). */
    private static final String ALICE_ID = "a11ce000-0000-4000-8000-000000000001";
    private static final String BOB_ID = "b0b00000-0000-4000-8000-000000000002";
    private static final String ADMIN_ID = "ad000000-0000-4000-8000-000000000003";

    /** Keycloak mit der echten Realm-Datei. */
    @Container
    static GenericContainer<?> keycloak = createKeycloak();

    /**
     * Baut den Container. Nur DEMO_PASSWORD_BOB ist gesetzt: bob zeigt, dass
     * Keycloak die Variable einsetzt, alice und admin zeigen, dass ohne Variable
     * der Beispielwert nach dem Doppelpunkt gilt (Spezifikation 2.6, E1).
     */
    private static GenericContainer<?> createKeycloak() {
        GenericContainer<?> container = TestKeycloak.createContainer(PUBLIC_URL);
        container.withEnv("DEMO_PASSWORD_BOB", BOB_PASSWORD);
        return container;
    }

    /** Die Discovery nennt die öffentliche Adresse als Issuer, nicht die des Containers (E3). */
    @Test
    void discoveryNamesPublicIssuer() throws Exception {
        TestBrowser browser = new TestBrowser();
        String keycloakUrl = TestKeycloak.url(keycloak);

        HttpResponse<String> discovery = browser.get(keycloakUrl + "/realms/chat/.well-known/openid-configuration");

        String body = discovery.body();
        assertEquals(200, discovery.statusCode());
        assertTrue(body.contains("\"issuer\":\"" + ISSUER + "\""), body);
    }

    /**
     * alice meldet sich mit dem Beispielwert ihres Passworts an. Beide Tokens
     * tragen ihre feste id als sub (P4), Name, E-Mail und genau die Rolle user
     * aus dem Mapper roles; das Access-Token ist für den Client web-gateway.
     */
    @Test
    void aliceGetsFixedIdNameEmailAndRoles() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("alice", "alice-demo");

        JsonNode idToken = KeycloakFlow.claimsOf(tokens, "id_token");
        JsonNode accessToken = KeycloakFlow.claimsOf(tokens, "access_token");
        String fromIdToken = identityOf(idToken);
        String fromAccessToken = identityOf(accessToken);
        String issuer = text(accessToken, "iss");
        String authorizedParty = text(accessToken, "azp");
        String expected = ALICE_ID + "|alice|Alice Muster|alice@example.org|[user]";
        assertEquals(expected, fromIdToken, "ID-Token");
        assertEquals(expected, fromAccessToken, "Access-Token");
        assertEquals(ISSUER, issuer);
        assertEquals("web-gateway", authorizedParty);
    }

    /** bob hat sein Passwort aus der Umgebung; der Beispielwert gilt dann nicht mehr. */
    @Test
    void bobUsesPasswordFromEnvironment() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("bob", BOB_PASSWORD);
        TestBrowser browser = new TestBrowser();
        String challenge = newChallenge();

        HttpResponse<String> withExample = submitLoginForm(browser, "web-gateway", GATEWAY_REDIRECT, challenge,
                "bob", "bob-demo");

        JsonNode idToken = KeycloakFlow.claimsOf(tokens, "id_token");
        String identity = identityOf(idToken);
        String page = withExample.body();
        assertEquals(BOB_ID + "|bob|Bob Muster|bob@example.org|[user]", identity);
        assertEquals(200, withExample.statusCode(), "falsches Passwort: das Formular kommt wieder");
        assertTrue(page.contains("kc-form-login"));
    }

    /** admin hat zusätzlich die Rolle admin und ist sonst ein Chat-Benutzer wie die anderen. */
    @Test
    void adminHasRolesAdminAndUser() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("admin", "admin-demo");

        JsonNode accessToken = KeycloakFlow.claimsOf(tokens, "access_token");
        String identity = identityOf(accessToken);
        assertEquals(ADMIN_ID + "|admin|Ada Admin|admin@example.org|[admin, user]", identity);
    }

    /**
     * Das Secret kommt aus der Umgebung und steht nicht wörtlich in der Datei:
     * Der Platzhalter selbst als Secret wird abgelehnt (E1).
     */
    @Test
    void placeholderIsNotTheSecret() throws Exception {
        TestBrowser browser = new TestBrowser();
        String verifier = KeycloakFlow.newVerifier();
        String challenge = KeycloakFlow.challengeFor(verifier);
        String code = logInAndGetCode(browser, "web-gateway", GATEWAY_REDIRECT, challenge, "alice", "alice-demo");
        String keycloakUrl = TestKeycloak.url(keycloak);

        HttpResponse<String> answer = KeycloakFlow.redeemCode(browser, keycloakUrl, "web-gateway",
                "${KEYCLOAK_CLIENT_SECRET}", GATEWAY_REDIRECT, code, verifier);

        String body = answer.body();
        assertEquals(401, answer.statusCode(), body);
        assertTrue(body.contains("unauthorized_client"), body);
    }

    /** Ohne code_challenge gibt es für keinen der beiden Clients ein Login-Formular (2.6, W7). */
    @Test
    void loginWithoutPkceIsRejected() throws Exception {
        assertNoLoginForm("web-gateway", GATEWAY_REDIRECT, null);
        assertNoLoginForm("desktop-client", DESKTOP_REDIRECT, null);
    }

    /** Nur die registrierte Redirect-URI gilt; ein anderer Port oder Host bekommt kein Formular (E1). */
    @Test
    void onlyRegisteredRedirectUrisAreAccepted() throws Exception {
        String challenge = newChallenge();

        assertNoLoginForm("web-gateway", "http://localhost:9999/login/oauth2/code/keycloak", challenge);
        assertNoLoginForm("desktop-client", "http://evil.example/callback", challenge);
    }

    /** P2: Beim desktop-client gilt jeder Port auf 127.0.0.1 (RFC 8252, Abschnitt 7.3). */
    @Test
    void desktopClientAcceptsAnyLoopbackPort() throws Exception {
        assertLoginForm("desktop-client", "http://127.0.0.1:53682/callback");
        assertLoginForm("desktop-client", "http://127.0.0.1:61234/callback");
    }

    /** Ein Token für den desktop-client trägt dessen Namen in azp; genau das prüft das Gateway (Task 7). */
    @Test
    void desktopClientGetsTokenForItself() throws Exception {
        TestBrowser browser = new TestBrowser();
        String verifier = KeycloakFlow.newVerifier();
        String challenge = KeycloakFlow.challengeFor(verifier);
        String code = logInAndGetCode(browser, "desktop-client", DESKTOP_REDIRECT, challenge, "alice", "alice-demo");
        String keycloakUrl = TestKeycloak.url(keycloak);

        HttpResponse<String> tokens = KeycloakFlow.redeemCode(browser, keycloakUrl, "desktop-client", null,
                DESKTOP_REDIRECT, code, verifier);

        assertEquals(200, tokens.statusCode(), tokens.body());
        JsonNode accessToken = KeycloakFlow.claimsOf(tokens, "access_token");
        String identity = identityOf(accessToken);
        String issuer = text(accessToken, "iss");
        String authorizedParty = text(accessToken, "azp");
        assertEquals(ALICE_ID + "|alice|Alice Muster|alice@example.org|[user]", identity);
        assertEquals(ISSUER, issuer);
        assertEquals("desktop-client", authorizedParty);
    }

    /**
     * P3: Auch in der Post-Logout-URI ersetzt Keycloak ${PUBLIC_URL}. Mit dem
     * ID-Token als Hinweis meldet es ab und leitet genau dorthin zurück; eine
     * andere Adresse lehnt es ab, ohne abzumelden.
     */
    @Test
    void postLogoutRedirectUsesPublicUrl() throws Exception {
        HttpResponse<String> tokens = logInAsWebGateway("alice", "alice-demo");
        String idToken = KeycloakFlow.tokenOf(tokens, "id_token");
        String wrongTarget = logoutUrl(idToken, "http://localhost:9999/");
        String rightTarget = logoutUrl(idToken, PUBLIC_URL + "/");
        TestBrowser browser = new TestBrowser();

        HttpResponse<String> wrong = browser.get(wrongTarget);
        HttpResponse<String> right = browser.get(rightTarget);

        String location = TestBrowser.location(right);
        assertEquals(400, wrong.statusCode(), "fremde Post-Logout-URI");
        assertEquals(302, right.statusCode(), right.body());
        assertEquals(PUBLIC_URL + "/", location, "P3");
    }

    /** Unsere Clients kennen keinen Password-Grant (PLANUNG.md 3.2), Keycloak lehnt ab. */
    @Test
    void ourClientsHaveNoPasswordGrant() throws Exception {
        HttpResponse<String> gateway = passwordGrant("web-gateway", TestKeycloak.CLIENT_SECRET);
        HttpResponse<String> desktop = passwordGrant("desktop-client", null);

        assertEquals(400, gateway.statusCode(), gateway.body());
        assertEquals(400, desktop.statusCode(), desktop.body());
    }

    /**
     * P7: admin-cli legt Keycloak in jedem Realm selbst an, mit Password-Grant.
     * Das Token trägt dann azp=admin-cli; genau daran lehnt das Gateway es ab
     * (Task 7, W7). Lehnt Keycloak schon den Grant ab, ist P7 anders entschieden.
     */
    @Test
    void adminCliPasswordGrantGivesTokenForAdminCli() throws Exception {
        HttpResponse<String> answer = passwordGrant("admin-cli", null);

        assertEquals(200, answer.statusCode(), "P7: " + answer.body());
        JsonNode accessToken = KeycloakFlow.claimsOf(answer, "access_token");
        String authorizedParty = text(accessToken, "azp");
        assertEquals("admin-cli", authorizedParty);
    }

    /** Ganzer Login über den Client web-gateway, wie ihn das Gateway macht; gibt die Antwort mit den Tokens zurück. */
    private HttpResponse<String> logInAsWebGateway(String username, String password) throws Exception {
        TestBrowser browser = new TestBrowser();
        String verifier = KeycloakFlow.newVerifier();
        String challenge = KeycloakFlow.challengeFor(verifier);
        String code = logInAndGetCode(browser, "web-gateway", GATEWAY_REDIRECT, challenge, username, password);
        String keycloakUrl = TestKeycloak.url(keycloak);
        HttpResponse<String> tokens = KeycloakFlow.redeemCode(browser, keycloakUrl, "web-gateway",
                TestKeycloak.CLIENT_SECRET, GATEWAY_REDIRECT, code, verifier);
        assertEquals(200, tokens.statusCode(), tokens.body());
        return tokens;
    }

    /** Meldet sich an und liest den Code aus der Umleitung zum Client. */
    private String logInAndGetCode(TestBrowser browser, String clientId, String redirectUri, String challenge,
                                   String username, String password) throws Exception {
        HttpResponse<String> answer = submitLoginForm(browser, clientId, redirectUri, challenge, username, password);
        assertEquals(302, answer.statusCode(), "Anmeldung von " + username + " abgelehnt");
        String location = TestBrowser.location(answer);
        return KeycloakFlow.codeFrom(location);
    }

    /**
     * Holt das Login-Formular und schickt Benutzer und Passwort ab. Das Ziel des
     * Formulars nennt die öffentliche Adresse (E4); der Test setzt die des
     * Containers ein. Die Cookies von Keycloak gehen dabei mit.
     */
    private HttpResponse<String> submitLoginForm(TestBrowser browser, String clientId, String redirectUri,
                                                 String challenge, String username, String password)
            throws Exception {
        String keycloakUrl = TestKeycloak.url(keycloak);
        String url = KeycloakFlow.authorizationUrl(keycloakUrl, clientId, redirectUri, challenge);
        HttpResponse<String> form = browser.get(url);
        assertEquals(200, form.statusCode(), "Login-Formular erwartet: " + url);
        String publicAction = TestBrowser.formAction(form.body());
        String action = publicAction.replace(PUBLIC_URL + "/auth", keycloakUrl);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", username);
        fields.put("password", password);
        return browser.postForm(action, fields);
    }

    /** Keycloak zeigt für diese Anfrage mit PKCE das Login-Formular. */
    private void assertLoginForm(String clientId, String redirectUri) throws Exception {
        TestBrowser browser = new TestBrowser();
        String challenge = newChallenge();
        String keycloakUrl = TestKeycloak.url(keycloak);
        String url = KeycloakFlow.authorizationUrl(keycloakUrl, clientId, redirectUri, challenge);

        HttpResponse<String> answer = browser.get(url);

        String page = answer.body();
        assertEquals(200, answer.statusCode(), redirectUri);
        assertTrue(page.contains("kc-form-login"), redirectUri);
    }

    /**
     * Keycloak lehnt die Login-Anfrage ab: Status 400 oder eine Umleitung mit
     * error=invalid_request, und auf keinen Fall das Login-Formular (W7 d).
     */
    private void assertNoLoginForm(String clientId, String redirectUri, String challenge) throws Exception {
        TestBrowser browser = new TestBrowser();
        String keycloakUrl = TestKeycloak.url(keycloak);
        String url = KeycloakFlow.authorizationUrl(keycloakUrl, clientId, redirectUri, challenge);

        HttpResponse<String> answer = browser.get(url);

        int status = answer.statusCode();
        String location = TestBrowser.location(answer);
        String page = answer.body();
        boolean refused = status == 400 || location.contains("error=invalid_request");
        assertTrue(refused, clientId + ": Status " + status + ", Ziel " + location);
        assertFalse(page.contains("kc-form-login"), clientId + ": Login-Formular trotz Ablehnung");
    }

    /** Password-Grant für alice, so wie ihn PLANUNG.md 3.2 für unsere Anwendung verwirft. */
    private HttpResponse<String> passwordGrant(String clientId, String clientSecret) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "password");
        fields.put("client_id", clientId);
        if (clientSecret != null) {
            fields.put("client_secret", clientSecret);
        }
        fields.put("username", "alice");
        fields.put("password", "alice-demo");
        String keycloakUrl = TestKeycloak.url(keycloak);
        TestBrowser browser = new TestBrowser();
        return browser.postForm(keycloakUrl + "/realms/chat/protocol/openid-connect/token", fields);
    }

    /** Abmelde-Adresse von Keycloak mit dem ID-Token als Hinweis und dem Ziel danach. */
    private static String logoutUrl(String idToken, String postLogoutRedirectUri) {
        String keycloakUrl = TestKeycloak.url(keycloak);
        String encodedTarget = URLEncoder.encode(postLogoutRedirectUri, StandardCharsets.UTF_8);
        return keycloakUrl + "/realms/chat/protocol/openid-connect/logout"
                + "?id_token_hint=" + idToken
                + "&post_logout_redirect_uri=" + encodedTarget;
    }

    /** Eine gültige code_challenge, wenn der Test den Code danach nicht einlöst. */
    private static String newChallenge() throws Exception {
        String verifier = KeycloakFlow.newVerifier();
        return KeycloakFlow.challengeFor(verifier);
    }

    /** sub, Benutzername, Name, E-Mail und Rollen eines Tokens in einer Zeile. */
    private static String identityOf(JsonNode claims) {
        String subject = text(claims, "sub");
        String username = text(claims, "preferred_username");
        String name = text(claims, "name");
        String email = text(claims, "email");
        List<String> roles = rolesOf(claims);
        return subject + "|" + username + "|" + name + "|" + email + "|" + roles;
    }

    /** Die Werte des Claims roles, sortiert; die Reihenfolge legt Keycloak nicht fest. */
    private static List<String> rolesOf(JsonNode claims) {
        List<String> roles = new ArrayList<>();
        JsonNode values = claims.path("roles");
        for (JsonNode value : values) {
            roles.add(value.asText());
        }
        Collections.sort(roles);
        return roles;
    }

    /** Ein Claim als Text; fehlt er, ein leerer Text. */
    private static String text(JsonNode claims, String name) {
        JsonNode value = claims.path(name);
        return value.asText();
    }
}
```

- [ ] **Schritt 3: Übersetzen und Rot lokal**

Ausführen: `mvn -q -pl web-gateway test-compile`
Erwartet: übersetzt ohne Fehler; die Testhilfen und der Test sind vollständig.

Ausführen (Docker läuft): `mvn -q -pl web-gateway test -Dtest=RealmImportIntegrationTest; echo "Exit: $?"`
Erwartet: alle Tests von `RealmImportIntegrationTest` mit einem Fehler beim Start des Containers, weil `../keycloak/realm-chat.json` fehlt; der Exit-Code ist nicht 0. Die genaue Meldung hält der Beleg fest.
Beleg: `task-03-rot.txt`.

- [ ] **Schritt 4: Realm-Datei anlegen**

`keycloak/realm-chat.json` — genau der Inhalt aus Spezifikation 2.6 und 4.2. Nach dem Vorbild von `keycloak/realm-chat.json` im Archiv, geändert: Secret, Passwörter und Adressen als Platzhalter, genau eine Redirect-URI je Client, kein `webOrigins`, Mapper `roles` statt `realm-roles`, keine `defaultRoles`, feste `id`, E-Mail unter `example.org`, Nachname von bob `Muster`.

```json
{
  "realm": "chat",
  "enabled": true,
  "roles": {
    "realm": [
      { "name": "user", "description": "Darf chatten" },
      { "name": "admin", "description": "Darf zusätzlich verwalten (ab Baustein 3)" }
    ]
  },
  "clients": [
    {
      "clientId": "web-gateway",
      "name": "web-gateway: Login im Browser, Authorization Code mit PKCE",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      "clientAuthenticatorType": "client-secret",
      "secret": "${KEYCLOAK_CLIENT_SECRET}",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "implicitFlowEnabled": false,
      "serviceAccountsEnabled": false,
      "redirectUris": ["${PUBLIC_URL}/login/oauth2/code/keycloak"],
      "attributes": {
        "pkce.code.challenge.method": "S256",
        "post.logout.redirect.uris": "${PUBLIC_URL}/"
      },
      "protocolMappers": [
        {
          "name": "roles",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-realm-role-mapper",
          "config": {
            "claim.name": "roles",
            "multivalued": "true",
            "jsonType.label": "String",
            "id.token.claim": "true",
            "access.token.claim": "true",
            "userinfo.token.claim": "true"
          }
        }
      ]
    },
    {
      "clientId": "desktop-client",
      "name": "desktop-client: JavaFX und Abnahme, Authorization Code mit PKCE, ohne Secret",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": true,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "implicitFlowEnabled": false,
      "serviceAccountsEnabled": false,
      "redirectUris": ["http://127.0.0.1/callback"],
      "attributes": {
        "pkce.code.challenge.method": "S256"
      },
      "protocolMappers": [
        {
          "name": "roles",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-realm-role-mapper",
          "config": {
            "claim.name": "roles",
            "multivalued": "true",
            "jsonType.label": "String",
            "id.token.claim": "true",
            "access.token.claim": "true",
            "userinfo.token.claim": "true"
          }
        }
      ]
    }
  ],
  "users": [
    {
      "id": "a11ce000-0000-4000-8000-000000000001",
      "username": "alice",
      "enabled": true,
      "email": "alice@example.org",
      "emailVerified": true,
      "firstName": "Alice",
      "lastName": "Muster",
      "credentials": [{ "type": "password", "value": "${DEMO_PASSWORD_ALICE:alice-demo}", "temporary": false }],
      "realmRoles": ["user"]
    },
    {
      "id": "b0b00000-0000-4000-8000-000000000002",
      "username": "bob",
      "enabled": true,
      "email": "bob@example.org",
      "emailVerified": true,
      "firstName": "Bob",
      "lastName": "Muster",
      "credentials": [{ "type": "password", "value": "${DEMO_PASSWORD_BOB:bob-demo}", "temporary": false }],
      "realmRoles": ["user"]
    },
    {
      "id": "ad000000-0000-4000-8000-000000000003",
      "username": "admin",
      "enabled": true,
      "email": "admin@example.org",
      "emailVerified": true,
      "firstName": "Ada",
      "lastName": "Admin",
      "credentials": [{ "type": "password", "value": "${DEMO_PASSWORD_ADMIN:admin-demo}", "temporary": false }],
      "realmRoles": ["user", "admin"]
    }
  ]
}
```

JSON kennt keine Kommentare. Die Gründe stehen in Spezifikation 2.6 und 4.2, auf die die Beschreibung der Clients verweist.

> **Fallen in dieser Datei:**
> 1. **Ohne `email` scheitert die Anmeldung** mit «Account is not fully set up» (E1). Jeder Benutzer
>    braucht E-Mail, Vor- und Nachname; den Claim `name` setzt Keycloak aus beiden zusammen.
> 2. **Ohne `realmRoles` hat ein importierter Benutzer keine Rolle**, nicht einmal
>    `default-roles-chat` (E3b). `roles` im Token wäre dann leer.
> 3. **`${…}` bleibt in der Datei.** Keycloak ersetzt die Platzhalter beim Import (E1). Wer sie
>    durch echte Werte ersetzt, schreibt ein Geheimnis ins Repository (W11 prüft das).

- [ ] **Schritt 5: Grün lokal**

Ausführen: `mvn -q -pl web-gateway test -Dtest=RealmImportIntegrationTest; echo "Exit: $?"`, danach alle Tests des Moduls mit `mvn -q -pl web-gateway test`
Erwartet: `RealmImportIntegrationTest` mit 12 Tests grün, `Exit: 0`; das Modul mit `WebGatewayApplicationTest` ebenso. Der erste Lauf dauert länger, weil das Image von Keycloak geladen wird.
Beleg: `task-03-gruen.txt`.

Ergebnis der offenen Prüfungen, im Beleg und in `notizen.md` festhalten (erwartet ist jeweils «bestanden»):
- **P2** (`desktopClientAcceptsAnyLoopbackPort`): Fällt sie durch, registriert die Realm-Datei genau `http://127.0.0.1:53682/callback` (Spezifikation 6.5), und der Test prüft nur noch diesen Port.
- **P3** (`postLogoutRedirectUsesPublicUrl`): Fällt sie durch, steht in der Realm-Datei `http://localhost:8080/` fest. Dann muss `LoginIntegrationTest` (Task 6) das Gateway auf Port 8080 starten, sonst lehnt Keycloak dort das Abmelden ab.
- **P4** (`aliceGetsFixedIdNameEmailAndRoles`): Fällt sie durch, gilt die Grenze aus Spezifikation 6.5, und W6 prüft nur `sender_name`.
- **P7** (`adminCliPasswordGrantGivesTokenForAdminCli`): Gibt Keycloak kein Token heraus, prüft der Test die Ablehnung, und `LoginIntegrationTest.adminCliTokenIsRejected` (Task 7) entfällt mit Vermerk.

- [ ] **Schritt 6: Committen**

```bash
git add keycloak web-gateway/src/test \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-03-rot.txt docs/belege/2026-10-02-baustein-1/task-03-gruen.txt
git commit -m "feat: Realm chat als JSON-Import für Keycloak" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Schritt 7: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `images` und `abnahme` grün. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 4: Keycloak in docker-compose

**Warum an dieser Stelle:** Der Realm ist geprüft (Task 3). Jetzt läuft Keycloak im Stack, so wie es später das Gateway braucht: Healthcheck ohne `curl` (E2), Import der Realm-Datei, Issuer aus `KC_HOSTNAME` (E3), kein Port nach aussen. Solange noch kein Gateway mitspielt, fällt ein Fehler hier allein auf.

**Dateien:**
- Ändern: `docker-compose.yml` (Dienst `keycloak`, Kommentar am Anfang)
- Ändern: `.env.example` (`KEYCLOAK_CLIENT_SECRET`, `DEMO_PASSWORD_*`)
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-04-rot.txt`, `task-04-gruen.txt`; Ändern: `notizen.md`
- Test: Prüfbefehle für Keycloak im Stack, lokal; die Abnahme von Bewertung 1 (`scripts/abnahme.sh`) bleibt grün

**Schnittstellen:**
- Verbraucht: `keycloak/realm-chat.json` (Task 3), `scripts/env-pruefen.sh` (Task 1)
- Stellt bereit: Compose-Dienst `keycloak` im Netz `chat-net`, intern `http://keycloak:8080/auth`, «healthy» erst bei `/auth/health/ready` = 200; Issuer `http://localhost:8080/auth/realms/chat`. Neue Schlüssel in `.env.example`, ohne die jeder `docker compose`-Befehl abbricht.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

Keycloak allein im Stack starten und von innen prüfen, lokal (Docker läuft, `.env` vollständig):

```bash
export MSYS_NO_PATHCONV=1
docker compose up -d --wait --wait-timeout 180 keycloak; echo "Exit: $?"
docker compose ps --format '{{.Service}} {{.Health}}'
docker compose ps --format '{{.Service}} {{.Ports}}' | grep -c -- '->'
docker run --rm --network chat-net curlimages/curl -s \
  http://keycloak:8080/auth/realms/chat/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
```

`curl` läuft hier im Container und schreibt auf die Standardausgabe; ein Pfad wie `/dev/null` kommt nicht vor (Globale Vorgaben).

- [ ] **Schritt 2: Rot lokal**

Ausführen: die Befehle aus Schritt 1, noch ohne den Dienst `keycloak`
Erwartet: `no such service: keycloak` und ein Exit-Code ungleich 0; in der Liste der Dienste fehlt `keycloak`, und `curl` findet den Namen `keycloak` nicht (keine Ausgabe).
Beleg: `task-04-rot.txt`.

- [ ] **Schritt 3: Dienst `keycloak` in `docker-compose.yml`**

Der Kommentar am Anfang der Datei:

```yaml
# Baustein 1: Broker, chat-service, Datenbank, batch-writer und Keycloak.
# Das web-gateway mit dem einzigen Port kommt in einem späteren Schritt dazu.
```

Nach dem Dienst `batch-writer`, vor dem Kommentar «KEIN ports:-Eintrag …» (Spezifikation 4.3):

```yaml
  # Login-Dienst (spec-web-gateway.md 1.2). Importiert den Realm chat beim
  # ersten Start aus keycloak/realm-chat.json, Secret und Passwörter kommen aus
  # .env. Kein Port nach aussen: Der Browser erreicht Keycloak nur über das
  # Gateway. Kein depends_on auf postgres: Keycloak hat seine eigene Datenhaltung.
  keycloak:
    image: quay.io/keycloak/keycloak:26.7.3
    command: ["start-dev", "--import-realm"]
    environment:
      KC_HTTP_RELATIVE_PATH: /auth
      KC_HOSTNAME: http://localhost:8080/auth
      KC_HEALTH_ENABLED: "true"
      PUBLIC_URL: http://localhost:8080
      KEYCLOAK_CLIENT_SECRET: ${KEYCLOAK_CLIENT_SECRET:?KEYCLOAK_CLIENT_SECRET fehlt in .env}
      DEMO_PASSWORD_ALICE: ${DEMO_PASSWORD_ALICE:-alice-demo}
      DEMO_PASSWORD_BOB: ${DEMO_PASSWORD_BOB:-bob-demo}
      DEMO_PASSWORD_ADMIN: ${DEMO_PASSWORD_ADMIN:-admin-demo}
    volumes:
      - ./keycloak/realm-chat.json:/opt/keycloak/data/import/realm-chat.json:ro
    networks:
      - chat-net
    healthcheck:
      # Im Image gibt es weder curl noch wget, aber bash: bash öffnet selbst eine
      # Verbindung zum Management-Port 9000 und prüft die Statuszeile (E2).
      test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/9000 && printf 'GET /auth/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3 && read -r status <&3 && [[ \"$$status\" == *' 200 '* ]]"]
      interval: 5s
      timeout: 5s
      retries: 12
      # Erste Antwort in E1 nach 32 s, auf einem Runner von GitHub langsamer.
      start_period: 90s
```

> **Fallen in diesem Dienst:**
> 1. **`$$status`:** Compose ersetzt `${…}` und `$…` selbst. `$$` kommt als ein `$` in der bash des
>    Containers an.
> 2. **`\r\n` in doppelten Anführungszeichen** ist eine Escape-Folge von YAML. Im Befehl stehen dann
>    echte Zeilenenden; `printf` gibt sie unverändert aus, das ist dasselbe.

- [ ] **Schritt 4: `.env.example` ergänzen**

`.env.example`, ganze Datei:

```bash
# Beispielwerte für den Unterricht. Die echte .env steht in .gitignore.
RABBITMQ_USER=chat
RABBITMQ_PASSWORD=bitte-lokal-aendern

# PostgreSQL: Das Image legt beim ersten Start die Datenbank POSTGRES_DB und
# den Benutzer POSTGRES_USER an. Der batch-writer meldet sich genau so an.
POSTGRES_DB=chat
POSTGRES_USER=chat
POSTGRES_PASSWORD=bitte-lokal-aendern

# Keycloak: Secret des Clients web-gateway. Keycloak setzt es beim Import des
# Realms ein, das Gateway meldet sich damit an. Ohne diesen Wert bricht jeder
# docker-compose-Befehl ab (spec-web-gateway.md 4.1).
KEYCLOAK_CLIENT_SECRET=bitte-lokal-aendern

# Passwörter der Demo-Benutzer im Realm chat. Eine Änderung wirkt erst in
# einem neuen Container: docker compose up -d --force-recreate keycloak.
DEMO_PASSWORD_ALICE=alice-demo
DEMO_PASSWORD_BOB=bob-demo
DEMO_PASSWORD_ADMIN=admin-demo
```

Eine ältere lokale `.env` bekommt die neuen Zeilen angehängt, ohne dass sie ausgegeben wird, und wird dann geprüft:

```bash
bash scripts/env-pruefen.sh            # nennt KEYCLOAK_CLIENT_SECRET und die drei DEMO_PASSWORD_*
grep -E '^(KEYCLOAK_CLIENT_SECRET|DEMO_PASSWORD_)' .env.example >> .env
bash scripts/env-pruefen.sh; echo "Exit: $?"
docker compose config --quiet; echo "Exit: $?"
```

Erwartet: zuerst die Meldung mit den vier fehlenden Schlüsseln, danach zweimal `Exit: 0`. Ohne die neue Zeile meldet `docker compose config` `required variable KEYCLOAK_CLIENT_SECRET is missing a value: KEYCLOAK_CLIENT_SECRET fehlt in .env`.

- [ ] **Schritt 5: Grün lokal**

Ausführen: die Befehle aus Schritt 1, danach `bash scripts/abnahme.sh; echo "Exit: $?"`
Erwartet: `Exit: 0`; in der Liste der Dienste die Zeile `keycloak healthy`; `0` Zeilen mit `->`; `"issuer":"http://localhost:8080/auth/realms/chat"`. Die Abnahme von Bewertung 1 bleibt grün: S2 startet nur die vier Dienste, S2 bis S8 `PASS`, `Exit: 0`.
Beleg: `task-04-gruen.txt`.

- [ ] **Schritt 6: Committen**

```bash
git add docker-compose.yml .env.example \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-04-rot.txt docs/belege/2026-10-02-baustein-1/task-04-gruen.txt
git commit -m "chore: Keycloak in docker-compose" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Erwartet: `git show --stat HEAD` nennt den Plan und `.env` nicht.

- [ ] **Schritt 7: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `images` und `abnahme` grün; `images` liest dabei `.env` aus `.env.example` mit dem neuen Secret. Dauerhaft im CI geprüft werden Keycloak im Stack und der Issuer ab Task 10 (W1, W2). Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 5: Gateway reicht den Realm chat an Keycloak durch

**Warum an dieser Stelle:** Es gibt nur einen Port. Ohne Proxy erreicht der Browser weder das Login-Formular noch dessen CSS und JavaScript (E4). Der Proxy lässt sich mit einem Mini-Server testen, ganz ohne Login. Hier entsteht auch die Sperre für Admin-Konsole, Realm `master` und Willkommensseite (W8, F15) und die erste Fassung der Sicherheitsregeln: `/auth/**` und `/error` frei, keine CSRF-Prüfung unter `/auth/**` (Spezifikation 2.1).

**Dateien:**
- Anlegen: `web-gateway/src/main/java/ch/benedict/m321/webgateway/config/GatewayProperties.java`
- Anlegen: `web-gateway/src/main/java/ch/benedict/m321/webgateway/config/KeycloakProxyConfig.java`
- Anlegen: `web-gateway/src/main/java/ch/benedict/m321/webgateway/config/SecurityConfig.java`
- Ändern: `WebGatewayApplication.java` (`@ConfigurationPropertiesScan`), `application.yml` (Abschnitt `gateway`)
- Anlegen (Testhilfen): `web-gateway/src/test/java/ch/benedict/m321/webgateway/TestHttpServer.java`, `FixedValue.java`
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-05-rot.txt`, `task-05-gruen.txt`; Ändern: `notizen.md`
- Test: `web-gateway/src/test/java/ch/benedict/m321/webgateway/config/KeycloakProxyIntegrationTest.java`, `KeycloakProxyDownIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: Modul (Task 2), `TestBrowser` (Task 3), `gateway-test.properties` (Task 2)
- Stellt bereit: `GatewayProperties` (Präfix `gateway`, vorerst nur `keycloakInternalUrl()`; Task 6 und 11 ergänzen), die Route für `/auth/realms/chat/**` und `/auth/resources/**`, `SecurityConfig.securityFilterChain(HttpSecurity)` in erster Fassung. Für spätere Tests: `TestHttpServer` (`start()`, `url(path)`, `answer(path, Answer)`, `requests()`, `clear()`, `stop()`, Records `Answer` und `Request`) und `FixedValue`.

- [ ] **Schritt 1: Testhilfen schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/FixedValue.java`

```java
package ch.benedict.m321.webgateway;

import java.util.function.Supplier;

/**
 * Ein fester Wert für @DynamicPropertySource.
 *
 * Spring verlangt dort einen Supplier, also etwas, das den Wert erst auf
 * Nachfrage liefert. Unsere Werte stehen schon fest, wenn Spring startet (Port
 * eines Containers oder Mini-Servers). Diese kleine benannte Klasse steht da,
 * wo man sonst ein Lambda schriebe; Lambdas gibt es in diesem Projekt nur in
 * der Konfiguration von Spring Security.
 *
 * @param value der Wert, den Spring für die Eigenschaft einsetzt
 */
public record FixedValue(Object value) implements Supplier<Object> {

    /** Gibt den Wert zurück, der beim Erzeugen mitgegeben wurde. */
    @Override
    public Object get() {
        return value;
    }
}
```

`web-gateway/src/test/java/ch/benedict/m321/webgateway/TestHttpServer.java`

```java
package ch.benedict.m321.webgateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein kleiner HTTP-Server aus dem JDK, der im Test einen anderen Dienst spielt,
 * zum Beispiel Keycloak (Spezifikation 4.4: Mini-Server aus dem JDK, wie in E6).
 *
 * Er antwortet auf festgelegte Pfade mit festen Antworten, auf alle anderen mit
 * 404, und merkt sich jede Anfrage. So prüft ein Test nicht nur, was das Gateway
 * antwortet, sondern auch, was beim Dienst dahinter ankommt und was nicht.
 */
public final class TestHttpServer {

    /** Eine feste Antwort: Status, Header und Text. */
    public record Answer(int status, Map<String, String> headers, String body) {
    }

    /** Eine Anfrage, wie sie beim Server ankam; die Header ohne Gross- und Kleinschreibung. */
    public record Request(String method, String path, String query, Headers headers, String body) {
    }

    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /** Bindet einen freien Port auf localhost; gestartet wird in start(). */
    private TestHttpServer() throws IOException {
        InetSocketAddress anyFreePort = new InetSocketAddress("localhost", 0);
        server = HttpServer.create(anyFreePort, 0);
        RecordingHandler handler = new RecordingHandler(this);
        server.createContext("/", handler);
    }

    /** Startet einen Server auf einem freien Port von localhost. */
    public static TestHttpServer start() {
        try {
            TestHttpServer testServer = new TestHttpServer();
            testServer.server.start();
            return testServer;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Vollständige Adresse eines Pfads auf diesem Server. */
    public String url(String path) {
        InetSocketAddress address = server.getAddress();
        int port = address.getPort();
        return "http://localhost:" + port + path;
    }

    /** Legt fest, was der Server auf einen Pfad antwortet (Pfad ohne Query, wie er ankommt). */
    public void answer(String path, Answer answer) {
        answers.put(path, answer);
    }

    /** Alle Anfragen seit dem letzten clear(), in der Reihenfolge ihres Eingangs. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Vergisst die bisherigen Anfragen; die festen Antworten bleiben. */
    public void clear() {
        requests.clear();
    }

    /** Hält den Server sofort an und gibt den Port frei. */
    public void stop() {
        server.stop(0);
    }

    /** Nimmt jede Anfrage an: merken, Antwort suchen, senden. */
    private static final class RecordingHandler implements HttpHandler {

        private final TestHttpServer owner;

        /** Der Handler legt die Anfragen beim Server ab, zu dem er gehört. */
        private RecordingHandler(TestHttpServer owner) {
            this.owner = owner;
        }

        /** Wird vom JDK-Server für jede Anfrage aufgerufen. */
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Request request = read(exchange);
            owner.requests.add(request);
            Answer answer = owner.answers.get(request.path());
            if (answer == null) {
                Map<String, String> noHeaders = Map.of();
                answer = new Answer(404, noHeaders, "");
            }
            send(exchange, answer);
        }

        /** Liest Methode, Pfad, Query, Header und Body der Anfrage. */
        private static Request read(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            Headers received = exchange.getRequestHeaders();
            Headers headers = new Headers();
            headers.putAll(received);
            // var statt des Typnamens: Er enthält das Wort, nach dem W11 sucht.
            var requestBody = exchange.getRequestBody();
            byte[] bytes = requestBody.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            return new Request(method, path, query, headers, body);
        }

        /** Schickt Status, Header und Body der festen Antwort. */
        private static void send(HttpExchange exchange, Answer answer) throws IOException {
            Headers responseHeaders = exchange.getResponseHeaders();
            Map<String, String> headers = answer.headers();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                responseHeaders.add(header.getKey(), header.getValue());
            }
            String text = answer.body();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            long length = bytes.length;
            if (bytes.length == 0) {
                // -1 heisst beim JDK-Server: Diese Antwort hat keinen Body.
                length = -1;
            }
            exchange.sendResponseHeaders(answer.status(), length);
            if (bytes.length > 0) {
                // var statt des Typnamens, wie oben.
                var responseBody = exchange.getResponseBody();
                responseBody.write(bytes);
            }
            exchange.close();
        }
    }
}
```

> **Falle – das Wort im Typnamen:** `getRequestBody()` und `getResponseBody()` liefern Typen, deren
> Namen das Wort enthalten, nach dem W11 sucht. Der Test schreibt deshalb `var`. Der Kommentar
> daneben sagt warum, ohne das Wort selbst zu nennen.

- [ ] **Schritt 2: Den fehlschlagenden Test für den Proxy schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/config/KeycloakProxyIntegrationTest.java`

```java
package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.FixedValue;
import ch.benedict.m321.webgateway.TestBrowser;
import ch.benedict.m321.webgateway.TestHttpServer;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Proxy unter /auth (Spezifikation 2.1, F15, P10) mit einem
 * Mini-Server, der Keycloak spielt. Der Mini-Server merkt sich jede Anfrage;
 * so zeigt der Test auch, was NICHT bei Keycloak ankommt.
 *
 * Das Gateway läuft auf einem zufälligen Port, wie ein echter Server: Nur so
 * gehen die Anfragen wirklich durch den Proxy und die Firewall von Spring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:gateway-test.properties")
class KeycloakProxyIntegrationTest {

    /** Discovery des Realms chat, gekürzt auf das, was der Test prüft. */
    private static final String DISCOVERY = "/auth/realms/chat/.well-known/openid-configuration";
    private static final String DISCOVERY_JSON = "{\"issuer\":\"http://localhost:8080/auth/realms/chat\"}";

    /** CSS der Login-Seite, Pfad wie in E4. */
    private static final String STYLES = "/auth/resources/j6b6d/login/keycloak.v2/css/styles.css";

    /** Ziel des Login-Formulars (E4); Keycloak antwortet mit Umleitung und Cookie. */
    private static final String AUTHENTICATE = "/auth/realms/chat/login-actions/authenticate";
    private static final String CALLBACK = "http://localhost:8080/login/oauth2/code/keycloak?state=s1&code=c1";
    private static final String KEYCLOAK_COOKIE =
            "AUTH_SESSION_ID=abc.123; Version=1; Path=/auth/realms/chat/; HttpOnly; SameSite=Lax";

    /** Spielt Keycloak. Läuft schon, bevor Spring das Gateway startet. */
    private static final TestHttpServer fakeKeycloak = startFakeKeycloak();

    @LocalServerPort
    private int port;

    /** Startet den Mini-Server mit Antworten wie in E4, gekürzt. */
    private static TestHttpServer startFakeKeycloak() {
        TestHttpServer server = TestHttpServer.start();
        Map<String, String> json = Map.of("Content-Type", "application/json");
        Map<String, String> css = Map.of("Content-Type", "text/css");
        Map<String, String> redirect = Map.of("Location", CALLBACK, "Set-Cookie", KEYCLOAK_COOKIE);
        TestHttpServer.Answer discovery = new TestHttpServer.Answer(200, json, DISCOVERY_JSON);
        TestHttpServer.Answer styles = new TestHttpServer.Answer(200, css, "body { margin: 0; }");
        TestHttpServer.Answer loggedIn = new TestHttpServer.Answer(302, redirect, "");
        server.answer(DISCOVERY, discovery);
        server.answer(STYLES, styles);
        server.answer(AUTHENTICATE, loggedIn);
        return server;
    }

    /** Das Gateway reicht an den Mini-Server weiter statt an keycloak:8080. */
    @DynamicPropertySource
    static void keycloakAddress(DynamicPropertyRegistry registry) {
        String internalUrl = fakeKeycloak.url("/auth");
        FixedValue value = new FixedValue(internalUrl);
        registry.add("KEYCLOAK_INTERNAL_URL", value);
    }

    /** Jeder Test zählt nur die Anfragen, die er selbst ausgelöst hat. */
    @BeforeEach
    void forgetEarlierRequests() {
        fakeKeycloak.clear();
    }

    /** Am Schluss den Port des Mini-Servers wieder freigeben. */
    @AfterAll
    static void stopFakeKeycloak() {
        fakeKeycloak.stop();
    }

    /** Die Discovery von Realm chat kommt unverändert durch; Keycloak sieht genau diese eine Anfrage. */
    @Test
    void passesDiscoveryOfRealmChatThrough() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(DISCOVERY);

        HttpResponse<String> response = browser.get(url);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        assertEquals(200, response.statusCode());
        assertEquals(DISCOVERY_JSON, response.body());
        assertEquals(DISCOVERY, received.path());
    }

    /** CSS, JavaScript und Bilder der Login-Seite liegen unter /auth/resources/ (E4). */
    @Test
    void passesLoginPageResourcesThrough() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(STYLES);

        HttpResponse<String> response = browser.get(url);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        assertEquals(200, response.statusCode());
        assertEquals(STYLES, received.path());
    }

    /**
     * Das Login-Formular schickt ein POST an Keycloak, ohne CSRF-Token des
     * Gateways (Spezifikation 2.1). Es kommt mit Query und Feldern an, und die
     * Umleitung zurück zum Gateway geht unverändert an den Browser.
     */
    @Test
    void passesLoginFormPostWithoutCsrfToken() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(AUTHENTICATE + "?session_code=sc1&client_id=web-gateway&tab_id=t1");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", "alice");
        fields.put("password", "alice-demo");

        HttpResponse<String> response = browser.postForm(url, fields);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        String location = TestBrowser.location(response);
        String body = received.body();
        assertEquals(302, response.statusCode());
        assertEquals(CALLBACK, location);
        assertEquals("POST", received.method());
        assertEquals("session_code=sc1&client_id=web-gateway&tab_id=t1", received.query());
        assertTrue(body.contains("username=alice"), body);
        assertTrue(body.contains("password=alice-demo"), body);
    }

    /** Cookies gehen in beide Richtungen unverändert durch (Spezifikation 3.1, Punkt 4). */
    @Test
    void passesCookiesBothWaysUnchanged() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(AUTHENTICATE);
        Map<String, String> cookie = Map.of("Cookie", "AUTH_SESSION_ID=abc.123; KC_RESTART=xyz");

        HttpResponse<String> response = browser.get(url, cookie);

        HttpHeaders headers = response.headers();
        List<String> setCookies = headers.allValues("Set-Cookie");
        TestHttpServer.Request received = onlyRequestAtKeycloak();
        Headers receivedHeaders = received.headers();
        assertEquals(List.of(KEYCLOAK_COOKIE), setCookies);
        assertEquals("AUTH_SESSION_ID=abc.123; KC_RESTART=xyz", receivedHeaders.getFirst("Cookie"));
    }

    /**
     * Admin-Konsole, Realm master und Willkommensseite beantwortet das Gateway
     * selbst mit 404; bei Keycloak kommt nichts an (W8, F15).
     */
    @Test
    void answersNotFoundForEverythingElseUnderAuth() throws Exception {
        TestBrowser browser = new TestBrowser();
        List<String> paths = List.of("/auth/admin/", "/auth/admin/master/console/",
                "/auth/realms/master/.well-known/openid-configuration", "/auth/", "/auth");

        for (String path : paths) {
            String url = gateway(path);
            HttpResponse<String> response = browser.get(url);
            assertEquals(404, response.statusCode(), path);
        }

        List<TestHttpServer.Request> received = fakeKeycloak.requests();
        assertTrue(received.isEmpty(), "bei Keycloak angekommen: " + received);
    }

    /**
     * Ein Pfad mit kodiertem ".." darf den Realm chat nicht verlassen. Der Proxy
     * gäbe ihn unverändert weiter, und Keycloak löste ihn zu /auth/admin/ auf.
     * Die Firewall von Spring Security weist ihn vorher ab.
     */
    @Test
    void dotSegmentsNeverReachKeycloak() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway("/auth/realms/chat/%2e%2e/%2e%2e/admin/");

        HttpResponse<String> response = browser.get(url);

        List<TestHttpServer.Request> received = fakeKeycloak.requests();
        assertEquals(400, response.statusCode());
        assertTrue(received.isEmpty(), "bei Keycloak angekommen: " + received);
    }

    /**
     * P10: X-Forwarded-*-Header und Forwarded aus dem Browser kommen nicht mit
     * ihrem gefälschten Wert bei Keycloak an. Keycloak wertet sie heute nicht
     * aus (kein KC_PROXY_HEADERS), aber das soll nicht davon abhängen.
     */
    @Test
    void forgedForwardedHeadersNeverReachKeycloak() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = gateway(DISCOVERY);
        Map<String, String> forged = new LinkedHashMap<>();
        forged.put("X-Forwarded-Host", "evil.example");
        forged.put("X-Forwarded-Proto", "https");
        forged.put("X-Forwarded-Port", "65000");
        forged.put("X-Forwarded-For", "203.0.113.7");
        forged.put("X-Forwarded-Prefix", "/evil");
        forged.put("Forwarded", "for=203.0.113.7;host=evil.example;proto=https");

        HttpResponse<String> response = browser.get(url, forged);

        TestHttpServer.Request received = onlyRequestAtKeycloak();
        Headers receivedHeaders = received.headers();
        assertEquals(200, response.statusCode());
        for (Map.Entry<String, String> header : forged.entrySet()) {
            String arrived = receivedHeaders.getFirst(header.getKey());
            boolean forgedValueArrived = arrived != null && arrived.contains(header.getValue());
            assertFalse(forgedValueArrived, "P10: " + header.getKey() + " kam an als " + arrived);
        }
    }

    /** Adresse eines Pfads auf dem Gateway. */
    private String gateway(String path) {
        return "http://localhost:" + port + path;
    }

    /** Genau eine Anfrage kam bei Keycloak an; der Test bekommt sie zum Prüfen. */
    private TestHttpServer.Request onlyRequestAtKeycloak() {
        List<TestHttpServer.Request> received = fakeKeycloak.requests();
        assertEquals(1, received.size(), "Anfragen bei Keycloak: " + received);
        return received.get(0);
    }
}
```

- [ ] **Schritt 3: Den fehlschlagenden Test für P11 schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/config/KeycloakProxyDownIntegrationTest.java`

```java
package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.TestBrowser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P11: Was der Browser sieht, wenn Keycloak nicht erreichbar ist (F1), zum
 * Beispiel weil der Container gerade neu startet. gateway-test.properties
 * zeigt mit KEYCLOAK_INTERNAL_URL auf localhost:9, wo niemand zuhört.
 *
 * Eigene Klasse neben KeycloakProxyIntegrationTest: Hier darf das Gateway
 * keinen Mini-Server als Keycloak haben, und Spring baut dafür einen eigenen
 * Kontext. So hängt kein Test von der Reihenfolge der anderen ab.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:gateway-test.properties")
class KeycloakProxyDownIntegrationTest {

    @LocalServerPort
    private int port;

    /** Innert 5 s eine Antwort mit einem Status 5xx, keine hängende Seite. */
    @Test
    void answersServerErrorWithinFiveSeconds() throws Exception {
        TestBrowser browser = new TestBrowser();
        String url = "http://localhost:" + port + "/auth/realms/chat/.well-known/openid-configuration";
        long start = System.nanoTime();

        HttpResponse<String> response = browser.get(url);

        long end = System.nanoTime();
        long millis = (end - start) / 1_000_000;
        int status = response.statusCode();
        assertTrue(status >= 500 && status <= 599, "P11: Status " + status);
        assertTrue(millis < 5000, "P11: Antwort nach " + millis + " ms");
    }
}
```

- [ ] **Schritt 4: Tests laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl web-gateway test -Dtest='KeycloakProxy*'`
Erwartet: Fehlschläge. Ohne Route und ohne eigene Regeln verlangt die Standard-Filterkette von Spring Security für jede Anfrage eine Anmeldung, und einen Benutzer dafür gibt es nicht (Beobachtung aus Task 2). Wo der Test `200`, `302` zum Callback, `404` oder `5xx` erwartet, kommt deren Antwort, eine Umleitung auf `/login` oder `401`. Nur `dotSegmentsNeverReachKeycloak` kann schon grün sein, denn die Firewall von Spring Security gibt es auch ohne eigene Regeln; er bleibt als Schutz gegen spätere Änderungen.
Beleg: `task-05-rot.txt`.

- [ ] **Schritt 5: Eigenschaften, Route und erste Sicherheitsregeln**

`application.yml`, am Ende:

```yaml
# Adressen kommen aus docker-compose.yml (spec-web-gateway.md 4.1). Bewusst
# ohne Vorgabewert: Fehlt eine Variable, startet das Gateway nicht, statt mit
# einer falschen Adresse zu laufen.
gateway:
  # Keycloak im Docker-Netz, mit /auth am Ende: Ziel des Proxys.
  keycloak-internal-url: ${KEYCLOAK_INTERNAL_URL}
```

`config/GatewayProperties.java` (neu):
- `@ConfigurationProperties(prefix = "gateway") public record GatewayProperties(String keycloakInternalUrl)`;
- Javadoc: die Adressen aus der Umgebung an genau einer Stelle (Spezifikation 4.1), mit `@param` je Feld. Task 6 ergänzt `publicUrl`, `clientSecret` und zwei Methoden für die Realm-Adressen, Task 11 `chatServiceUrl`.

`WebGatewayApplication.java`: zusätzlich `@ConfigurationPropertiesScan`, damit Spring `GatewayProperties` findet; der Javadoc der Klasse sagt das in einem Satz.

`config/KeycloakProxyConfig.java` (neu) — nach dem Vorbild von `web-gateway/src/main/java/ch/benedict/m321/webgateway/config/KeycloakProxyConfig.java` im Archiv, geändert:
- `@Bean public RouterFunction<ServerResponse> keycloakRoute(GatewayProperties gatewayProperties)` mit einem Javadoc, der die zwei Präfixe begründet (die Vorarbeit sprach von «alles unter /auth»);
- **nur zwei Präfixe** statt `/auth/**`: `RequestPredicates.path("/auth/realms/chat/**")` oder `RequestPredicates.path("/auth/resources/**")`, Handler `HandlerFunctions.http()` (Spezifikation 2.1, 5, E4);
- **Ziel nur Schema, Host und Port** aus `KEYCLOAK_INTERNAL_URL` (`http://keycloak:8080`), ausdrücklich gebaut mit `URI.create(…)` und `new URI(scheme, null, host, port, null, null, null)`, gesetzt mit `BeforeFilterFunctions.uri(…)`. Der Pfad bleibt der des Browsers, denn Keycloak läuft selbst unter `/auth`;
- **keine Route für den Rest unter `/auth`:** Spring MVC findet dort weder Route noch Datei und antwortet `404` (W8, F15). Es gibt keine Sperrliste, die man nachführen müsste; gesperrt ist alles, was nicht ausdrücklich durchgeht;
- der Klassen-Javadoc sagt nicht mehr, das Gateway setze `X-Forwarded-*` (Spezifikation 5, P10).

`config/SecurityConfig.java` (neu), erste Fassung — nach dem Vorbild von `config/SecurityConfig.java` im Archiv, vorerst nur die Regeln:
- `@Bean public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception`, Javadoc über Klasse und Methode;
- `http.authorizeHttpRequests(requests -> requests.requestMatchers("/auth", "/auth/**", "/error").permitAll().anyRequest().authenticated())`: `/auth` steht ausdrücklich da, damit auch die Wurzel ohne Schrägstrich ihr `404` bekommt; `/error`, damit eine Fehlerseite nicht selbst zur Anmeldung führt (Spezifikation 2.1);
- `http.csrf(csrf -> csrf.ignoringRequestMatchers("/auth/**"))`: Keycloak schützt sein Formular selbst (`session_code`, `tab_id`, eigene Cookies, E4);
- noch kein Login: Wer nicht angemeldet ist, bekommt vorerst `403`. Task 6 ergänzt Login, Einstiegspunkte und Abmelden.

- [ ] **Schritt 6: Tests laufen lassen, P10 entscheiden**

Ausführen: `mvn -q -pl web-gateway test -Dtest='KeycloakProxy*'`
Erwartet: alle Tests grün, ausser vielleicht `forgedForwardedHeadersNeverReachKeycloak`. Sein Ergebnis entscheidet P10 und kommt in den Beleg und in `notizen.md`:
- **grün:** Der Proxy reicht die gefälschten Werte nicht weiter. P10 bestanden, die Route bleibt, wie sie ist.
- **rot:** Der Proxy reicht sie durch. Dann entfernt die Route die sechs Header, bevor sie weiterreicht (Spezifikation 6.5): eine Konstante `FORWARDED_HEADERS` mit `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, `X-Forwarded-For`, `X-Forwarded-Prefix` und `Forwarded`, und in einer `for`-Schleife je ein `BeforeFilterFunctions.removeRequestHeader(name)` am Builder der Route. Danach ist der Test grün. Den Satz «`KC_PROXY_HEADERS` nie ohne diesen Filter setzen» bekommt Spezifikation 5 mit dem Abnahmeprotokoll (6.6), nicht in diesem Commit.

P11 (`answersServerErrorWithinFiveSeconds`) mit dem gemessenen Status ebenso festhalten. Ist er rot, bekommt der Proxy ausdrückliche Zeitgrenzen (Spezifikation 6.5). Ein Keycloak, der die Verbindung annimmt und nie antwortet, gehört nicht zu P11 (Spezifikation 6.4).

> **Fallen:**
> 1. **Formular-POST durch den Proxy:** Tomcat liest ein Formular beim ersten `getParameter()` aus;
>    danach wäre der Body leer. Spring Cloud Gateway baut ihn deshalb aus den Parametern neu auf
>    (`FormFilter`). Der Test prüft die Felder einzeln, nicht den Body Zeichen für Zeichen.
> 2. **`/error` gehört zu den freien Pfaden:** Sonst führte jedes `404` unter `/auth` über die
>    Fehlerseite wieder zur Anmeldung, und W8 sähe `403` oder `302` statt `404`.

- [ ] **Schritt 7: Alle Tests des Moduls**

Ausführen: `mvn -q -pl web-gateway test; echo "Exit: $?"`
Erwartet: alle grün, auch `RealmImportIntegrationTest` mit Keycloak im Container. `WebGatewayApplicationTest` braucht keine Änderung, denn `gateway-test.properties` nennt `KEYCLOAK_INTERNAL_URL` schon seit Task 2.
Beleg: `task-05-gruen.txt`, zusammen mit dem Ergebnis von Schritt 6.

- [ ] **Schritt 8: Committen**

```bash
git add web-gateway/src \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-05-rot.txt docs/belege/2026-10-02-baustein-1/task-05-gruen.txt
git commit -m "feat: Gateway reicht den Realm chat an Keycloak durch" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Schritt 9: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `images` und `abnahme` grün; `maven` zeigt `KeycloakProxyIntegrationTest` mit 7 und `KeycloakProxyDownIntegrationTest` mit 1 Test. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 6: Anmeldung über Keycloak, /api/me nennt den Benutzer

**Warum an dieser Stelle:** Realm (Task 3) und Proxy (Task 5) stehen; das Login-Formular kommt also durch das Gateway. Jetzt meldet das Gateway Browser als OIDC-Client an, hält die Anmeldung in der Session und sagt der Web-UI unter `/api/me`, wer angemeldet ist (Spezifikation 2.2, 3.1, 4.6). PLANUNG.md 6: «Auth zuerst».

**Dateien:**
- Anlegen: `config/KeycloakClientConfig.java`, `service/LoggedInUser.java`, `dto/CurrentUser.java`, `controller/CurrentUserController.java` (alle unter `web-gateway/src/main/java/ch/benedict/m321/webgateway/`)
- Ändern: `config/GatewayProperties.java`, `config/SecurityConfig.java`, `application.yml`
- Anlegen (Testhilfe): `web-gateway/src/test/java/ch/benedict/m321/webgateway/TestUsers.java`
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-06-rot.txt`, `task-06-gruen.txt`; Ändern: `notizen.md`
- Test: `config/SecurityConfigTest.java`, `controller/CurrentUserControllerTest.java` (beide MockMvc), `config/LoginIntegrationTest.java` (Keycloak-Container, echter Port)

**Schnittstellen:**
- Verbraucht: Realm (Task 3), `TestKeycloak`, `TestBrowser`, `KeycloakFlow` (Task 3), Route und `SecurityConfig` (Task 5), `FixedValue` (Task 5)
- Stellt bereit: `GET /api/me` → `CurrentUser(String username, String displayName, boolean admin)`; `LoggedInUser.fromClaims(ClaimAccessor claims)` → `LoggedInUser(String subject, String username, String displayName, boolean admin)` (Task 11 nimmt `subject` und `displayName` als Absender); `KeycloakClientConfig.REGISTRATION_ID` = `"keycloak"`; `GatewayProperties.publicRealmUrl()` und `internalRealmUrl()`. Ohne Anmeldung: `/api/**` und `/ws/**` → `401` mit `WWW-Authenticate: Bearer`, alles andere ausser `/auth`, `/error` und dem Login selbst → `302`. `GET /logout` meldet beim Gateway und bei Keycloak ab.

- [ ] **Schritt 1: Testhilfe für angemeldete Benutzer schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/TestUsers.java`

```java
package ch.benedict.m321.webgateway;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Angemeldete Benutzer für MockMvc-Tests, so wie Spring sie nach dem Login in
 * der Session ablegt: ein OidcUser mit den Claims aus ID-Token und Userinfo.
 * Mit oidcLogin().oidcUser(…) aus spring-security-test kommt er in die
 * Anfrage, ohne dass ein Keycloak läuft. Gebaut aus fertigen Objekten, nicht
 * mit einem Lambda in oidcLogin().idToken(…).
 */
public final class TestUsers {

    /** Feste id von alice aus der Realm-Datei (Spezifikation 4.2). */
    public static final String ALICE_ID = "a11ce000-0000-4000-8000-000000000001";

    /** Diese Klasse sammelt nur Hilfsmethoden und wird nie erzeugt. */
    private TestUsers() {
    }

    /** alice mit den Claims aus Spezifikation 2.2. */
    public static OidcUser alice() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", ALICE_ID);
        claims.put("preferred_username", "alice");
        claims.put("name", "Alice Muster");
        claims.put("roles", List.of("user"));
        return oidcUser(claims);
    }

    /** Ein Benutzer mit genau diesen Claims; preferred_username muss dabei sein (Spezifikation 4.6). */
    public static OidcUser oidcUser(Map<String, Object> claims) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(300);
        OidcIdToken idToken = new OidcIdToken("id-token-aus-dem-test", issuedAt, expiresAt, claims);
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("OIDC_USER");
        return new DefaultOidcUser(authorities, idToken, "preferred_username");
    }
}
```

- [ ] **Schritt 2: Den fehlschlagenden Test der Regeln schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/config/SecurityConfigTest.java` — die Attribute des Session-Cookies prüft er **nicht**, denn MockMvc schreibt keinen echten Header `Set-Cookie: JSESSIONID` (Spezifikation 6.4). Das macht `LoginIntegrationTest`.

```java
package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Prüft die Zugriffsregeln des Gateways (Spezifikation 2.1) mit MockMvc, ohne
 * Keycloak: Seiten leiten zum Login, /api und /ws antworten 401, die
 * Umleitung zu Keycloak verlangt PKCE, und Abmelden geht auch bei Keycloak.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:gateway-test.properties")
class SecurityConfigTest {

    /** Login-Adresse von Keycloak, wie der Browser sie sieht (PUBLIC_URL aus gateway-test.properties). */
    private static final String KEYCLOAK_LOGIN = "http://localhost:8080/auth/realms/chat/protocol/openid-connect/auth?";

    /**
     * Name der Registrierung, wie er in /oauth2/authorization/keycloak steht
     * (Spezifikation 2.1). Bewusst als Text und nicht als Konstante des Codes:
     * So übersetzt der Test schon, bevor es KeycloakClientConfig gibt, und wird
     * zur Laufzeit rot statt beim Übersetzen.
     */
    private static final String REGISTRATION_ID = "keycloak";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    /** Eine Seite ohne Anmeldung führt zum Login-Start des Gateways (W2). */
    @Test
    void pageWithoutLoginRedirectsToLoginStart() throws Exception {
        MockHttpServletRequestBuilder request = get("/");

        MockHttpServletResponse response = perform(request);

        String location = response.getRedirectedUrl();
        assertEquals(302, response.getStatus());
        assertTrue(location.endsWith("/oauth2/authorization/keycloak"), location);
    }

    /** Der Login-Start leitet zu Keycloak, mit PKCE (S256) und genau der registrierten Redirect-URI (W2, 4.6). */
    @Test
    void loginStartRedirectsToKeycloakWithPkce() throws Exception {
        MockHttpServletRequestBuilder request = get("/oauth2/authorization/keycloak");

        MockHttpServletResponse response = perform(request);

        String location = response.getRedirectedUrl();
        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        assertEquals(302, response.getStatus());
        assertTrue(location.startsWith(KEYCLOAK_LOGIN), location);
        assertTrue(decoded.contains("client_id=web-gateway"), decoded);
        assertTrue(decoded.contains("code_challenge_method=S256"), decoded);
        assertTrue(decoded.contains("code_challenge="), decoded);
        assertTrue(decoded.contains("redirect_uri=http://localhost:8080/login/oauth2/code/keycloak"), decoded);
        assertTrue(decoded.contains("scope=openid profile email"), decoded);
    }

    /** /api ohne Anmeldung: 401 mit WWW-Authenticate: Bearer, keine Umleitung (2.1, F7). */
    @Test
    void apiWithoutLoginIsUnauthorized() throws Exception {
        MockHttpServletRequestBuilder request = get("/api/me");

        MockHttpServletResponse response = perform(request);

        String challenge = response.getHeader("WWW-Authenticate");
        assertEquals(401, response.getStatus());
        assertTrue(challenge.startsWith("Bearer"), challenge);
        assertNull(response.getRedirectedUrl());
    }

    /** /ws ohne Anmeldung: ebenso 401; den WebSocket selbst gibt es erst ab Task 11. */
    @Test
    void webSocketWithoutLoginIsUnauthorized() throws Exception {
        MockHttpServletRequestBuilder request = get("/ws/chat?roomId=00000000-0000-0000-0000-000000000001");

        MockHttpServletResponse response = perform(request);

        String challenge = response.getHeader("WWW-Authenticate");
        assertEquals(401, response.getStatus());
        assertTrue(challenge.startsWith("Bearer"), challenge);
    }

    /**
     * /error braucht keine Anmeldung. Ohne Fehler davor meldet die Fehlerseite
     * von Spring Boot 500; wichtig ist, dass weder 302 noch 401 kommt (2.1).
     */
    @Test
    void errorPageNeedsNoLogin() throws Exception {
        MockHttpServletRequestBuilder request = get("/error");

        MockHttpServletResponse response = perform(request);

        assertEquals(500, response.getStatus());
    }

    /** Ausserhalb von /auth gilt CSRF weiter: ein POST ohne Token wird abgewiesen, auch angemeldet. */
    @Test
    void postOutsideAuthNeedsCsrfToken() throws Exception {
        OidcUser alice = TestUsers.alice();
        OidcLoginRequestPostProcessor login = oidcLogin().oidcUser(alice);
        MockHttpServletRequestBuilder request = post("/api/me").with(login);

        MockHttpServletResponse response = perform(request);

        assertEquals(403, response.getStatus());
    }

    /**
     * Unter /auth gilt CSRF nicht (2.1): Das Formular von Keycloak kennt kein
     * Token des Gateways. Ein POST ohne Token kommt deshalb an der Prüfung
     * vorbei; hier antwortet danach Spring MVC (404 oder 405), aber nie 403.
     */
    @Test
    void authPathsNeedNoCsrfToken() throws Exception {
        MockHttpServletRequestBuilder request = post("/auth/admin/");

        MockHttpServletResponse response = perform(request);

        assertNotEquals(403, response.getStatus(), "CSRF hätte mit 403 abgewiesen");
    }

    /**
     * GET /logout ohne CSRF-Token beendet die Session und schickt den Browser zum
     * Abmelden zu Keycloak, mit id_token_hint und Rückkehr auf PUBLIC_URL/ (3.1, W10).
     */
    @Test
    void logoutRedirectsToKeycloakEndSession() throws Exception {
        OidcUser alice = TestUsers.alice();
        ClientRegistration keycloak = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        OidcLoginRequestPostProcessor login = oidcLogin().clientRegistration(keycloak).oidcUser(alice);
        MockHttpServletRequestBuilder request = get("/logout").with(login);

        MockHttpServletResponse response = perform(request);

        String location = response.getRedirectedUrl();
        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        assertEquals(302, response.getStatus());
        assertTrue(location.startsWith("http://localhost:8080/auth/realms/chat/protocol/openid-connect/logout?"), location);
        assertTrue(decoded.contains("id_token_hint=id-token-aus-dem-test"), decoded);
        assertTrue(decoded.contains("post_logout_redirect_uri=http://localhost:8080/"), decoded);
    }

    /** Die Admin-Konsole bleibt auch angemeldet unerreichbar (W8 mit Anmeldung). */
    @Test
    void adminConsoleIsNotFoundEvenWhenLoggedIn() throws Exception {
        OidcUser alice = TestUsers.alice();
        OidcLoginRequestPostProcessor login = oidcLogin().oidcUser(alice);
        MockHttpServletRequestBuilder request = get("/auth/admin/").with(login);

        MockHttpServletResponse response = perform(request);

        assertEquals(404, response.getStatus());
    }

    /** Schickt die Anfrage durch MockMvc und gibt die Antwort zum Prüfen zurück. */
    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        return result.getResponse();
    }
}
```

- [ ] **Schritt 3: Den fehlschlagenden Test für /api/me schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/controller/CurrentUserControllerTest.java`

```java
package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Prüft GET /api/me mit einem angemeldeten Benutzer aus der Session
 * (Spezifikation 2.2). Die Antwort hat genau drei Felder, in dieser
 * Reihenfolge: kein Token, keine E-Mail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:gateway-test.properties")
class CurrentUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** alice: Name aus dem Claim name, keine Rolle admin. */
    @Test
    void answersWithNameFromToken() throws Exception {
        OidcUser alice = TestUsers.alice();

        MockHttpServletResponse response = me(alice);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals("{\"username\":\"alice\",\"displayName\":\"Alice Muster\",\"admin\":false}", body);
    }

    /** admin: Rollen user und admin im Claim roles ergeben admin=true. */
    @Test
    void reportsAdminRole() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "ad000000-0000-4000-8000-000000000003");
        claims.put("preferred_username", "admin");
        claims.put("name", "Ada Admin");
        claims.put("roles", List.of("user", "admin"));
        OidcUser admin = TestUsers.oidcUser(claims);

        MockHttpServletResponse response = me(admin);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals("{\"username\":\"admin\",\"displayName\":\"Ada Admin\",\"admin\":true}", body);
    }

    /** Fehlt der Claim name, ist der Anzeigename der Benutzername (2.2). */
    @Test
    void fallsBackToUsernameWithoutName() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "b0b00000-0000-4000-8000-000000000002");
        claims.put("preferred_username", "bob");
        claims.put("roles", List.of("user"));
        OidcUser bob = TestUsers.oidcUser(claims);

        MockHttpServletResponse response = me(bob);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals("{\"username\":\"bob\",\"displayName\":\"bob\",\"admin\":false}", body);
    }

    /** Fehlt der Claim roles ganz, ist niemand admin. */
    @Test
    void isNoAdminWithoutRoles() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "ad000000-0000-4000-8000-000000000003");
        claims.put("preferred_username", "admin");
        claims.put("name", "Ada Admin");
        OidcUser withoutRoles = TestUsers.oidcUser(claims);

        MockHttpServletResponse response = me(withoutRoles);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals("{\"username\":\"admin\",\"displayName\":\"Ada Admin\",\"admin\":false}", body);
    }

    /** GET /api/me als dieser Benutzer. */
    private MockHttpServletResponse me(OidcUser user) throws Exception {
        OidcLoginRequestPostProcessor login = oidcLogin().oidcUser(user);
        MockHttpServletRequestBuilder request = get("/api/me").with(login);
        MvcResult result = mockMvc.perform(request).andReturn();
        return result.getResponse();
    }
}
```

- [ ] **Schritt 4: Den fehlschlagenden Test des ganzen Logins schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/config/LoginIntegrationTest.java` — Stand: Task 6. Task 7 ergänzt zwei Tests mit Bearer-Tokens.

```java
package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.FixedValue;
import ch.benedict.m321.webgateway.KeycloakFlow;
import ch.benedict.m321.webgateway.TestBrowser;
import ch.benedict.m321.webgateway.TestKeycloak;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der ganze Login wie im Browser (Spezifikation 3.1): Gateway auf einem echten
 * Port, Keycloak im Container mit der echten Realm-Datei, das Login-Formular
 * durch den Proxy des Gateways. Belegt W3, W10, P5 und P14.
 *
 * Das Gateway läuft auf einem festen, vorher gesuchten Port. Keycloak muss ihn
 * schon beim Start kennen: Daraus baut es Issuer, Formularziel und
 * Umleitungen (KC_HOSTNAME), so wie im Betrieb aus http://localhost:8080.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(locations = "classpath:gateway-test.properties")
@Testcontainers
class LoginIntegrationTest {

    /** Port des Gateways in diesem Test. */
    private static final int GATEWAY_PORT = findFreePort();

    /** Öffentliche Adresse im Test, wie http://localhost:8080 im Betrieb. */
    private static final String PUBLIC_URL = "http://localhost:" + GATEWAY_PORT;

    /** Test-Admin für die Admin-API (P5). Nur in diesem Container, nie in docker-compose.yml. */
    private static final String TEST_ADMIN = "test-admin";
    private static final String TEST_ADMIN_PASSWORD = "test-admin-passwort";

    /** Keycloak mit der echten Realm-Datei und dem Test-Admin. */
    @Container
    static GenericContainer<?> keycloak = createKeycloak();

    /** Baut Keycloak für PUBLIC_URL; nur hier gibt es einen Bootstrap-Admin (Spezifikation 6.4). */
    private static GenericContainer<?> createKeycloak() {
        GenericContainer<?> container = TestKeycloak.createContainer(PUBLIC_URL);
        container.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", TEST_ADMIN);
        container.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", TEST_ADMIN_PASSWORD);
        return container;
    }

    /** Das Gateway bekommt Port, öffentliche Adresse, Keycloak im Container und das Test-Secret. */
    @DynamicPropertySource
    static void gatewaySettings(DynamicPropertyRegistry registry) {
        String keycloakUrl = TestKeycloak.url(keycloak);
        FixedValue port = new FixedValue(GATEWAY_PORT);
        FixedValue publicUrl = new FixedValue(PUBLIC_URL);
        FixedValue internalUrl = new FixedValue(keycloakUrl);
        FixedValue secret = new FixedValue(TestKeycloak.CLIENT_SECRET);
        registry.add("server.port", port);
        registry.add("PUBLIC_URL", publicUrl);
        registry.add("KEYCLOAK_INTERNAL_URL", internalUrl);
        registry.add("KEYCLOAK_CLIENT_SECRET", secret);
    }

    /**
     * W3: alice meldet sich über das Formular an und sieht unter /api/me ihren
     * Namen. Nach dem Login gilt eine neue Session-ID (Session Fixation, 3.1),
     * für den Pfad / gibt es nur JSESSIONID, und keine Antwort enthält ein JWT.
     */
    @Test
    void aliceLogsInAndSeesHerName() throws Exception {
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> start = browser.get(PUBLIC_URL + "/oauth2/authorization/keycloak");
        String sessionBeforeLogin = browser.cookieValue("JSESSIONID");

        HttpResponse<String> afterLogin = logIn(browser, start, "alice", "alice-demo");
        HttpResponse<String> me = browser.get(PUBLIC_URL + "/api/me");
        HttpResponse<String> page = browser.get(PUBLIC_URL + "/");

        String target = TestBrowser.location(afterLogin);
        String sessionAfterLogin = browser.cookieValue("JSESSIONID");
        List<String> rootCookies = browser.cookieNamesForRootPath();
        String meBody = me.body();
        String pageBody = page.body();
        assertEquals(PUBLIC_URL + "/", target, "ohne gespeicherte Anfrage geht es auf /");
        assertEquals(200, me.statusCode());
        assertEquals("{\"username\":\"alice\",\"displayName\":\"Alice Muster\",\"admin\":false}", meBody);
        assertEquals(List.of("JSESSIONID"), rootCookies, "für / nur das Session-Cookie");
        assertNotEquals(sessionBeforeLogin, sessionAfterLogin, "neue Session-ID nach dem Login");
        assertFalse(meBody.contains("eyJ"), "kein JWT in /api/me");
        assertFalse(pageBody.contains("eyJ"), "kein JWT auf /");
    }

    /** W3 für admin: dieselbe Antwort, mit admin=true. */
    @Test
    void adminSeesAdminFlag() throws Exception {
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> start = browser.get(PUBLIC_URL + "/oauth2/authorization/keycloak");

        logIn(browser, start, "admin", "admin-demo");
        HttpResponse<String> me = browser.get(PUBLIC_URL + "/api/me");

        assertEquals("{\"username\":\"admin\",\"displayName\":\"Ada Admin\",\"admin\":true}", me.body());
    }

    /**
     * P14: Wer auf / kam, landet nach dem Login wieder dort. Spring Security 6
     * hängt "?continue" an: Daran erkennt es, dass es die gespeicherte Anfrage
     * suchen soll. Die Web-UI ignoriert den Parameter (Spezifikation 6.5).
     */
    @Test
    void loginReturnsToRequestedPage() throws Exception {
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> start = browser.get(PUBLIC_URL + "/");

        HttpResponse<String> afterLogin = logIn(browser, start, "alice", "alice-demo");

        String target = TestBrowser.location(afterLogin);
        assertEquals(PUBLIC_URL + "/?continue", target, "P14");
    }

    /** Ein falsches Passwort: Keycloak zeigt das Formular wieder, das Gateway bleibt ohne Anmeldung. */
    @Test
    void wrongPasswordShowsFormAgain() throws Exception {
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> start = browser.get(PUBLIC_URL + "/oauth2/authorization/keycloak");
        HttpResponse<String> form = browser.follow(start);
        String action = TestBrowser.formAction(form.body());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", "alice");
        fields.put("password", "falsch");

        HttpResponse<String> answer = browser.postForm(action, fields);
        HttpResponse<String> me = browser.get(PUBLIC_URL + "/api/me");

        String page = answer.body();
        assertEquals(200, answer.statusCode());
        assertTrue(page.contains("kc-form-login"), "das Formular kommt wieder");
        assertEquals(401, me.statusCode());
    }

    /** Das Session-Cookie: HttpOnly, SameSite=Lax, Pfad /, ohne Secure (2.1, 4.5, W3). */
    @Test
    void sessionCookieIsHttpOnlyAndLax() throws Exception {
        TestBrowser browser = new TestBrowser();

        HttpResponse<String> start = browser.get(PUBLIC_URL + "/oauth2/authorization/keycloak");

        HttpHeaders headers = start.headers();
        List<String> setCookies = headers.allValues("Set-Cookie");
        String sessionCookie = "";
        for (String setCookie : setCookies) {
            if (setCookie.startsWith("JSESSIONID=")) {
                sessionCookie = setCookie;
            }
        }
        assertTrue(sessionCookie.contains("HttpOnly"), sessionCookie);
        assertTrue(sessionCookie.contains("SameSite=Lax"), sessionCookie);
        assertTrue(sessionCookie.contains("Path=/"), sessionCookie);
        assertFalse(sessionCookie.contains("Secure"), sessionCookie);
    }

    /**
     * W10: Ein einziger Aufruf von GET /logout, dem der Browser folgt. Er meldet
     * beim Gateway und bei Keycloak ab und endet beim Login-Formular, ohne
     * stilles Wiederanmelden. Die alte Session-ID gilt danach nicht mehr.
     */
    @Test
    void logoutEndsGatewayAndKeycloakSession() throws Exception {
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> start = browser.get(PUBLIC_URL + "/oauth2/authorization/keycloak");
        logIn(browser, start, "alice", "alice-demo");
        String oldSession = browser.cookieValue("JSESSIONID");

        HttpResponse<String> logout = browser.get(PUBLIC_URL + "/logout");
        HttpResponse<String> landing = browser.follow(logout);
        TestBrowser withOldCookie = new TestBrowser();
        Map<String, String> oldCookie = Map.of("Cookie", "JSESSIONID=" + oldSession);
        HttpResponse<String> me = withOldCookie.get(PUBLIC_URL + "/api/me", oldCookie);

        String target = TestBrowser.location(logout);
        String decoded = URLDecoder.decode(target, StandardCharsets.UTF_8);
        String page = landing.body();
        assertTrue(target.startsWith(PUBLIC_URL + "/auth/realms/chat/protocol/openid-connect/logout?"), target);
        assertTrue(decoded.contains("id_token_hint="), decoded);
        assertTrue(decoded.contains("post_logout_redirect_uri=" + PUBLIC_URL + "/"), decoded);
        assertTrue(page.contains("kc-form-login"), "Login-Formular statt stillem Wiederanmelden");
        assertEquals(401, me.statusCode(), "alte Session-ID");
    }

    /**
     * P5: Auch mit einem abgelaufenen ID-Token als Hinweis meldet Keycloak ab
     * und leitet zurück. Die Lebensdauer der Tokens wird dafür über die
     * Admin-API auf 10 s gesetzt und danach auf die Vorgabe von 300 s zurück.
     */
    @Test
    void logoutWorksWithExpiredIdToken() throws Exception {
        String adminToken = adminToken();
        setTokenLifespan(adminToken, 10);
        try {
            TestBrowser browser = new TestBrowser();
            HttpResponse<String> start = browser.get(PUBLIC_URL + "/oauth2/authorization/keycloak");
            logIn(browser, start, "alice", "alice-demo");
            // Das ID-Token in der Session lebt 10 s; danach ist es abgelaufen.
            Thread.sleep(12_000);

            HttpResponse<String> logout = browser.get(PUBLIC_URL + "/logout");
            HttpResponse<String> landing = browser.follow(logout);

            String page = landing.body();
            assertTrue(page.contains("kc-form-login"), "P5: Keycloak hat nicht zurückgeleitet: " + page);
        } finally {
            setTokenLifespan(adminToken, 300);
        }
    }

    /**
     * Folgt vom Start bis zum Formular von Keycloak (durch den Proxy), schickt
     * Benutzer und Passwort ab und ruft die Rückleitung beim Gateway auf. Gibt
     * die Antwort darauf zurück: die Umleitung nach dem Login.
     */
    private HttpResponse<String> logIn(TestBrowser browser, HttpResponse<String> start, String username,
                                       String password) throws Exception {
        HttpResponse<String> form = browser.follow(start);
        String page = form.body();
        assertTrue(page.contains("kc-form-login"), "Login-Formular erwartet");
        String action = TestBrowser.formAction(page);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", username);
        fields.put("password", password);
        HttpResponse<String> answer = browser.postForm(action, fields);
        String callback = TestBrowser.location(answer);
        assertTrue(callback.startsWith(PUBLIC_URL + "/login/oauth2/code/keycloak?"), "Rückleitung: " + callback);
        return browser.get(callback);
    }

    /** Token des Test-Admins im Realm master; nur dieser Container hat ihn. */
    private String adminToken() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "password");
        fields.put("client_id", "admin-cli");
        fields.put("username", TEST_ADMIN);
        fields.put("password", TEST_ADMIN_PASSWORD);
        String keycloakUrl = TestKeycloak.url(keycloak);
        TestBrowser browser = new TestBrowser();
        HttpResponse<String> answer = browser.postForm(keycloakUrl + "/realms/master/protocol/openid-connect/token", fields);
        assertEquals(200, answer.statusCode(), answer.body());
        return KeycloakFlow.tokenOf(answer, "access_token");
    }

    /** Setzt im Realm chat die Lebensdauer von Access- und ID-Token, direkt beim Container. */
    private void setTokenLifespan(String adminToken, int seconds) throws Exception {
        String keycloakUrl = TestKeycloak.url(keycloak);
        URI realm = URI.create(keycloakUrl + "/admin/realms/chat");
        String json = "{\"accessTokenLifespan\":" + seconds + "}";
        HttpRequest.BodyPublisher content = HttpRequest.BodyPublishers.ofString(json);
        HttpRequest request = HttpRequest.newBuilder(realm)
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .PUT(content)
                .build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse.BodyHandler<String> asText = HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> answer = client.send(request, asText);
        assertEquals(204, answer.statusCode(), answer.body());
    }

    /** Ein Port, auf dem gerade niemand zuhört; das Gateway startet gleich danach darauf. */
    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
```

- [ ] **Schritt 5: Tests laufen lassen und Fehlschlag bestätigen**

Ausführen (Docker läuft): `mvn -q -pl web-gateway test -Dtest='SecurityConfigTest,CurrentUserControllerTest,LoginIntegrationTest'; echo "Exit: $?"`
Erwartet: Alle drei übersetzen, denn sie brauchen nur die Testhilfen und Spring; sie scheitern erst zur Laufzeit, Exit-Code ungleich 0:
- `SecurityConfigTest`: Der Kontext startet nicht, es gibt noch kein `ClientRegistrationRepository`;
- `CurrentUserControllerTest`: `404` statt `200`, `/api/me` gibt es noch nicht;
- `LoginIntegrationTest`: Ohne Login antwortet das Gateway auf `/oauth2/authorization/keycloak` mit `403` statt mit der Umleitung zu Keycloak («Login-Formular erwartet»).

Beleg: `task-06-rot.txt`.

- [ ] **Schritt 6: Anmeldung, Einstiegspunkte, Abmelden und /api/me**

`application.yml`: der Block `server` und der Abschnitt `gateway` ganz.

```yaml
server:
  port: 8080
  servlet:
    session:
      cookie:
        # JSESSIONID ist für JavaScript nicht lesbar und geht bei Anfragen
        # fremder Seiten im Hintergrund nicht mit (spec-web-gateway.md 4.5).
        http-only: true
        same-site: lax

# Adressen und Secret kommen aus docker-compose.yml und .env (spec-web-gateway.md
# 4.1). Bewusst ohne Vorgabewert: Fehlt eine Variable, startet das Gateway
# nicht, statt mit einer falschen Adresse zu laufen.
gateway:
  # So sehen Browser das Gateway: Issuer, Login- und Abmelde-Adresse (4.6).
  public-url: ${PUBLIC_URL}
  # Keycloak im Docker-Netz, mit /auth am Ende: Proxy, Token, JWKS, Userinfo.
  keycloak-internal-url: ${KEYCLOAK_INTERNAL_URL}
  # Secret des Clients web-gateway (spec-web-gateway.md 2.6).
  client-secret: ${KEYCLOAK_CLIENT_SECRET}
```

`config/GatewayProperties.java` — nach dem Vorbild von `config/KeycloakProperties.java` im Archiv, geändert: `record GatewayProperties(String publicUrl, String keycloakInternalUrl, String clientSecret)`; Realm `chat` und Client `web-gateway` sind keine Variablen, sie stehen fest im Code (Spezifikation 4.6). Zwei Methoden, damit keine Adresse an zwei Stellen zusammengesetzt wird:
- `publicRealmUrl()` → `publicUrl + "/auth/realms/chat"`: Issuer jedes Tokens (E3), Basis der Adressen für den Browser;
- `internalRealmUrl()` → `keycloakInternalUrl + "/realms/chat"`: Basis für Token, JWKS und Userinfo.

`config/KeycloakClientConfig.java` (neu) — nach dem Vorbild von `config/KeycloakClientConfig.java` im Archiv, geändert: Scopes `openid`, `profile`, `email`; `redirectUri` fest `publicUrl + "/login/oauth2/code/keycloak"` statt `{baseUrl}/…` (Spezifikation 4.6); Javadoc über der `@Bean`-Methode. Inhalt genau die Tabelle aus Spezifikation 4.6:
- `public static final String REGISTRATION_ID = "keycloak"`, `@Bean public ClientRegistrationRepository clientRegistrationRepository(GatewayProperties gatewayProperties)` → `InMemoryClientRegistrationRepository` mit genau einer `ClientRegistration`, gebaut mit `ClientRegistration.withRegistrationId(REGISTRATION_ID)…build()`;
- `clientId` `web-gateway`, `clientSecret` aus `GatewayProperties`, `CLIENT_SECRET_BASIC`, `AUTHORIZATION_CODE`;
- öffentlich: `authorizationUri` (`publicRealmUrl()` + `/protocol/openid-connect/auth`), `issuerUri` (`publicRealmUrl()`), in den Metadaten `end_session_endpoint` (`publicRealmUrl()` + `/protocol/openid-connect/logout`);
- intern: `tokenUri`, `jwkSetUri`, `userInfoUri` (`internalRealmUrl()` + `/protocol/openid-connect/token`, `…/certs`, `…/userinfo`);
- `userNameAttributeName("preferred_username")`, PKCE mit `ClientRegistration.ClientSettings.builder().requireProofKey(true).build()`.

`config/SecurityConfig.java`, zweite Fassung (Spezifikation 2.1, 3.1):
- `http.oauth2Login(Customizer.withDefaults())`: Login über die Registrierung `keycloak`;
- **Einstiegspunkte nach Pfad**, als `DelegatingAuthenticationEntryPoint`, gesetzt mit `http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))`: für `/api/**` und `/ws/**` ein `BearerTokenAuthenticationEntryPoint` (401, `WWW-Authenticate: Bearer`, leerer Body), sonst `LoginUrlAuthenticationEntryPoint("/oauth2/authorization/keycloak")` (302). Den Matcher für `/api/**` und `/ws/**` baut eine private Methode `apiAndWebSocket()` aus zwei `PathPatternRequestMatcher` und einem `OrRequestMatcher`; Task 7 braucht ihn noch einmal. Die Vorarbeit leitete alle Pfade um; ein `fetch` oder WebSocket kann einer Umleitung aber nicht folgen (Spezifikation 7);
- **Abmelden** mit `GET /logout`: `logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/logout"))` und ein `OidcClientInitiatedLogoutSuccessHandler` mit `setPostLogoutRedirectUri(gatewayProperties.publicUrl() + "/")`. Er schickt den Browser an `end_session_endpoint`, mit `id_token_hint` (3.1, Punkt 6);
- die Regeln aus Task 5 bleiben. Die Methode bekommt `GatewayProperties` und `ClientRegistrationRepository` als Parameter.

`service/LoggedInUser.java`, `dto/CurrentUser.java`, `controller/CurrentUserController.java` (neu) — nach dem Vorbild der gleichnamigen Dateien im Archiv, geändert: die Kommentare nennen den Mapper `roles` (2.6) und nicht mehr die Queue-Tiefe (Baustein 3):
- `public record LoggedInUser(String subject, String username, String displayName, boolean admin)` mit `public static LoggedInUser fromClaims(ClaimAccessor claims)`: `subject` aus `sub`, `username` aus `preferred_username`, `displayName` aus `name`, fehlt er, `username`; `admin`, wenn der Claim `roles` den Wert `admin` enthält, sonst `false`, auch ohne Claim `roles` (2.2). `ClaimAccessor` haben der `OidcUser` der Session und der `Jwt` eines Bearer-Tokens gemeinsam (Task 7);
- `public record CurrentUser(String username, String displayName, boolean admin)`: genau die drei Felder aus 2.2, in dieser Reihenfolge;
- `@GetMapping("/api/me") public CurrentUser currentUser(@AuthenticationPrincipal ClaimAccessor user)`: liest `LoggedInUser` und gibt `CurrentUser` zurück. Ohne Anmeldung kommt keine Anfrage hierher, `SecurityConfig` antwortet vorher mit `401`.

> **Fallen:**
> 1. **PKCE beim vertraulichen Client:** Spring schickt `code_challenge` von sich aus nur bei
>    öffentlichen Clients. Ohne `requireProofKey(true)` lehnte Keycloak jeden Login ab, denn der
>    Realm verlangt PKCE (Task 3). `loginStartRedirectsToKeycloakWithPkce` zeigt es.
> 2. **Ohne `issuerUri` prüfte Spring `iss` im ID-Token nicht** (Spezifikation 4.6), und ohne
>    `end_session_endpoint` in den Metadaten endete `GET /logout` nur beim Gateway.
> 3. **`oidcLogin()` ohne `clientRegistration(…)`** benutzt eine Test-Registrierung namens `test`.
>    Der Logout-Handler findet dazu keine Abmelde-Adresse und leitet nur auf `/`. Deshalb holt
>    `logoutRedirectsToKeycloakEndSession` die echte Registrierung aus dem Kontext.
> 4. **Session-Cookie und MockMvc:** MockMvc schreibt keinen Header `Set-Cookie: JSESSIONID`. Die
>    Attribute prüft deshalb `LoginIntegrationTest` am echten Port (Spezifikation 6.4).
> 5. **`/?continue` nach dem Login** (P14): Spring Security 6 hängt den Parameter an die gespeicherte
>    Anfrage. Wer direkt bei `/oauth2/authorization/keycloak` beginnt (wie `login` in der Abnahme),
>    landet ohne gespeicherte Anfrage auf `/`.

- [ ] **Schritt 7: Grün lokal**

Ausführen: `mvn -q -pl web-gateway test -Dtest='SecurityConfigTest,CurrentUserControllerTest,LoginIntegrationTest'; echo "Exit: $?"`
Erwartet: alle grün, `LoginIntegrationTest` mit 7 Tests, `Exit: 0`.

- [ ] **Schritt 8: Alle Tests des Moduls, offene Prüfungen festhalten**

Ausführen: `mvn -q -pl web-gateway test; echo "Exit: $?"`
Erwartet: alle grün. `WebGatewayApplicationTest` zeigt weiter, dass das Gateway ohne Keycloak startet: Die Registrierung steht im Code und holt beim Start keine Discovery.
Beleg: `task-06-gruen.txt`, mit Schritt 7.

Ergebnis der offenen Prüfungen, im Beleg und in `notizen.md` festhalten:
- **P5** (`logoutWorksWithExpiredIdToken`), erwartet «bestanden». Sonst schickt das Gateway nach Ablauf des ID-Tokens `client_id` statt `id_token_hint` (Spezifikation 6.5), und der Test prüft die Seite, auf der Keycloak nachfragt.
- **P14** (`loginReturnsToRequestedPage`), erwartet `/?continue`. Kommt `/` ohne Zusatz, wird der Test darauf geändert.

- [ ] **Schritt 9: Committen**

```bash
git add web-gateway/src \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-06-rot.txt docs/belege/2026-10-02-baustein-1/task-06-gruen.txt
git commit -m "feat: Anmeldung über Keycloak, /api/me nennt den Benutzer" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Schritt 10: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `images` und `abnahme` grün. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 7: Gateway prüft Bearer-Tokens

**Warum an dieser Stelle:** Der zweite Weg hinein, für den Desktop-Client (Baustein 5) und die Abnahme (W7, W9). Er setzt die Einstiegspunkte aus Task 6 voraus: `/api/**` und `/ws/**` antworten ohne Anmeldung `401`, und genau dort darf ein Bearer-JWT stehen. Geprüft werden Signatur, Issuer, Ablauf und `azp = desktop-client`, lokal im Speicher (Spezifikation 2.6, 4.6, F14).

**Dateien:**
- Anlegen: `config/JwtConfig.java`, `config/AuthorizedPartyValidator.java`, `config/ApiBearerTokenResolver.java` (unter `web-gateway/src/main/java/ch/benedict/m321/webgateway/`)
- Ändern: `config/SecurityConfig.java` (Resource Server)
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-07-rot.txt`, `task-07-gruen.txt`; Ändern: `notizen.md`
- Test: `web-gateway/src/test/java/ch/benedict/m321/webgateway/config/BearerTokenTest.java`; `config/LoginIntegrationTest.java` (zwei Tests dazu)

**Schnittstellen:**
- Verbraucht: Einstiegspunkte und `apiAndWebSocket()` aus `SecurityConfig` (Task 6), `GatewayProperties.publicRealmUrl()` und `internalRealmUrl()` (Task 6), Client `desktop-client` im Realm (Task 3), `TestUsers.ALICE_ID` (Task 6)
- Stellt bereit: `JwtConfig.DESKTOP_CLIENT` = `"desktop-client"`; `public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer)` mit den Prüfungen Issuer, Ablauf und `azp` (Produktion und `BearerTokenTest` benutzen dieselbe Methode); `@Bean JwtDecoder jwtDecoder(GatewayProperties)`; `AuthorizedPartyValidator(String expectedClientId)`; `ApiBearerTokenResolver(RequestMatcher)`. Ein gültiges Bearer-JWT öffnet `/api/**` und `/ws/**`, mit dem `Jwt` als angemeldetem Benutzer (Task 11 liest den Absender daraus wie aus der Session).

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`web-gateway/src/test/java/ch/benedict/m321/webgateway/config/BearerTokenTest.java` — in den Ordner kommt die Datei erst in Schritt 3, Teil B. Vorher sollen die zwei neuen Tests aus Schritt 2 zur Laufzeit rot sein; das ginge nicht, solange `BearerTokenTest` ohne `JwtConfig` das Übersetzen scheitern lässt.

```java
package ch.benedict.m321.webgateway.config;

import ch.benedict.m321.webgateway.TestUsers;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Prüft Bearer-JWTs (Spezifikation 2.6, F14) mit MockMvc, ohne Keycloak.
 *
 * Der Test signiert seine Tokens selbst, mit einem RSA-Schlüssel, den er beim
 * Start erzeugt (NimbusJwtEncoder). Das Gateway prüft sie mit einem Decoder,
 * der genau die Prüfungen der Produktion hat (JwtConfig.tokenValidator), nur
 * mit dem öffentlichen Schlüssel des Tests statt dem JWKS von Keycloak. So
 * prüft jeder Fall wirklich Signatur, Issuer, Ablauf und azp. jwt() aus
 * spring-security-test umginge Decoder und Prüfungen; es dient nur dazu, die
 * Felder von /api/me bei einem Token zu zeigen (Spezifikation 6.4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:gateway-test.properties")
class BearerTokenTest {

    /** Issuer wie im Betrieb (PUBLIC_URL aus gateway-test.properties). */
    private static final String ISSUER = "http://localhost:8080/auth/realms/chat";

    /** Schlüsselpaar, mit dem der Test seine Tokens signiert. */
    private static final KeyPair TEST_KEYS = newKeyPair();

    /** Ein zweites Paar, das das Gateway nicht kennt: falsche Signatur. */
    private static final KeyPair FOREIGN_KEYS = newKeyPair();

    @Autowired
    private MockMvc mockMvc;

    /**
     * Ersetzt im Test den Decoder der Produktion. Die Prüfungen sind dieselben
     * (JwtConfig.tokenValidator); nur der Schlüssel kommt aus dem Test statt vom
     * JWKS. @Primary: Spring Security nimmt diesen Decoder.
     */
    @TestConfiguration
    static class TestDecoderConfig {

        /** Decoder mit dem öffentlichen Testschlüssel und den Prüfungen der Produktion. */
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            RSAPublicKey publicKey = (RSAPublicKey) TEST_KEYS.getPublic();
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
            OAuth2TokenValidator<Jwt> checks = JwtConfig.tokenValidator(ISSUER);
            decoder.setJwtValidator(checks);
            return decoder;
        }
    }

    /** Gültig: richtiger Schlüssel, richtiger Issuer, azp=desktop-client. Dieselbe Antwort wie mit Session. */
    @Test
    void validDesktopTokenIsAccepted() throws Exception {
        String token = token(TEST_KEYS, ISSUER, "desktop-client", 300);

        MockHttpServletResponse response = meWithBearer(token);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus());
        assertEquals("{\"username\":\"alice\",\"displayName\":\"Alice Muster\",\"admin\":false}", body);
    }

    /** Ein Token für den Client web-gateway gilt als Bearer nicht; es verlässt das Gateway ohnehin nie (2.6). */
    @Test
    void tokenForWebGatewayIsRejected() throws Exception {
        String token = token(TEST_KEYS, ISSUER, "web-gateway", 300);

        MockHttpServletResponse response = meWithBearer(token);

        assertRejected(response);
    }

    /** Ein Token aus admin-cli (P7) ist wertlos, egal wie Keycloak den Client einstellt. */
    @Test
    void tokenFromAdminCliIsRejected() throws Exception {
        String token = token(TEST_KEYS, ISSUER, "admin-cli", 300);

        MockHttpServletResponse response = meWithBearer(token);

        assertRejected(response);
    }

    /** Fremder Issuer, z. B. ein Token aus dem Realm master. */
    @Test
    void tokenFromOtherRealmIsRejected() throws Exception {
        String token = token(TEST_KEYS, "http://localhost:8080/auth/realms/master", "desktop-client", 300);

        MockHttpServletResponse response = meWithBearer(token);

        assertRejected(response);
    }

    /** Abgelaufen, und zwar seit 10 min: Spring lässt bei exp 60 s Spielraum. */
    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = token(TEST_KEYS, ISSUER, "desktop-client", -600);

        MockHttpServletResponse response = meWithBearer(token);

        assertRejected(response);
    }

    /** Mit einem Schlüssel signiert, den das Gateway nicht kennt. */
    @Test
    void tokenWithForeignSignatureIsRejected() throws Exception {
        String token = token(FOREIGN_KEYS, ISSUER, "desktop-client", 300);

        MockHttpServletResponse response = meWithBearer(token);

        assertRejected(response);
    }

    /** Gar kein JWT: 401, keine Umleitung zum Login (W7 b). */
    @Test
    void garbageTokenIsRejectedWithoutRedirect() throws Exception {
        MockHttpServletResponse response = meWithBearer("kaputt");

        assertRejected(response);
    }

    /** Bearer gilt nur für /api und /ws (2.1): Auf / zählt selbst ein gültiges Token nicht. */
    @Test
    void bearerTokenOnPageIsIgnored() throws Exception {
        String token = token(TEST_KEYS, ISSUER, "desktop-client", 300);
        MockHttpServletRequestBuilder request = get("/").header("Authorization", "Bearer " + token);

        MvcResult result = mockMvc.perform(request).andReturn();

        MockHttpServletResponse response = result.getResponse();
        String location = response.getRedirectedUrl();
        assertEquals(302, response.getStatus());
        assertTrue(location.endsWith("/oauth2/authorization/keycloak"), location);
    }

    /**
     * jwt() aus spring-security-test legt ein fertiges Jwt in die Anfrage und
     * umgeht dabei Decoder und Prüfungen. Gezeigt wird nur, dass /api/me aus
     * den Claims eines Tokens dieselben Felder liest wie aus der Session.
     */
    @Test
    void jwtPrincipalGivesSameFieldsAsSession() throws Exception {
        List<String> roles = List.of("user", "admin");
        Jwt adminToken = Jwt.withTokenValue("token-aus-dem-test")
                .header("alg", "RS256")
                .subject("ad000000-0000-4000-8000-000000000003")
                .claim("azp", "desktop-client")
                .claim("preferred_username", "admin")
                .claim("name", "Ada Admin")
                .claim("roles", roles)
                .build();
        JwtRequestPostProcessor bearer = jwt().jwt(adminToken);
        MockHttpServletRequestBuilder request = get("/api/me").with(bearer);

        MvcResult result = mockMvc.perform(request).andReturn();

        MockHttpServletResponse response = result.getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus());
        assertEquals("{\"username\":\"admin\",\"displayName\":\"Ada Admin\",\"admin\":true}", body);
    }

    /**
     * Ein signiertes Token für alice, wie Keycloak es ausstellt, mit Schlüssel,
     * Issuer, azp und Gültigkeit nach Wahl. Negative Sekunden: schon abgelaufen.
     */
    private static String token(KeyPair keys, String issuer, String authorizedParty, long secondsValid) {
        RSAPublicKey publicKey = (RSAPublicKey) keys.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keys.getPrivate();
        RSAKey signingKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-key").build();
        JWKSet keySet = new JWKSet(signingKey);
        ImmutableJWKSet<SecurityContext> keySource = new ImmutableJWKSet<>(keySet);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(keySource);

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(secondsValid);
        Instant issuedAt = expiresAt.minusSeconds(300);
        List<String> roles = List.of("user");
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").type("JWT").build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(TestUsers.ALICE_ID)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("azp", authorizedParty)
                .claim("preferred_username", "alice")
                .claim("name", "Alice Muster")
                .claim("roles", roles)
                .build();
        JwtEncoderParameters parameters = JwtEncoderParameters.from(header, claims);
        Jwt jwt = encoder.encode(parameters);
        return jwt.getTokenValue();
    }

    /** GET /api/me mit "Authorization: Bearer <token>", wie der Desktop-Client es schickt. */
    private MockHttpServletResponse meWithBearer(String token) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/me").header("Authorization", "Bearer " + token);
        MvcResult result = mockMvc.perform(request).andReturn();
        return result.getResponse();
    }

    /** Abgelehnt heisst: 401 mit WWW-Authenticate: Bearer error="invalid_token" (F14), keine Umleitung. */
    private static void assertRejected(MockHttpServletResponse response) {
        String challenge = response.getHeader("WWW-Authenticate");
        assertEquals(401, response.getStatus());
        assertTrue(challenge.startsWith("Bearer"), challenge);
        assertTrue(challenge.contains("invalid_token"), challenge);
        assertNull(response.getRedirectedUrl());
    }

    /** Ein RSA-Schlüsselpaar mit 2048 Bit, wie Keycloak es für RS256 benutzt. */
    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
```

- [ ] **Schritt 2: Zwei Tests mit echten Tokens in `LoginIntegrationTest`**

`BearerTokenTest` ersetzt den JWKS durch einen Testschlüssel. Ob der Decoder der Produktion den JWKS wirklich intern holt und ein echtes Token von Keycloak annimmt, zeigt erst ein Test gegen den Container. In `LoginIntegrationTest` (Stand Task 6) nach den Konstanten:

```java
    /** Rückleitung des Desktop-Clients; auf Port 53682 hört niemand zu, der Test folgt nicht. */
    private static final String DESKTOP_REDIRECT = "http://127.0.0.1:53682/callback";
```

und nach `logoutWorksWithExpiredIdToken`:

```java
    /**
     * W7 (a): Ein echtes Token aus desktop-client, geholt mit PKCE über das
     * Gateway wie vom Desktop-Client, öffnet /api/me. Der Decoder holt dafür den
     * JWKS intern bei Keycloak, wie im Betrieb.
     */
    @Test
    void desktopClientTokenOpensApi() throws Exception {
        TestBrowser browser = new TestBrowser();
        String verifier = KeycloakFlow.newVerifier();
        String challenge = KeycloakFlow.challengeFor(verifier);
        String loginUrl = KeycloakFlow.authorizationUrl(PUBLIC_URL + "/auth", "desktop-client", DESKTOP_REDIRECT, challenge);
        HttpResponse<String> form = browser.get(loginUrl);
        String action = TestBrowser.formAction(form.body());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", "alice");
        fields.put("password", "alice-demo");
        HttpResponse<String> answer = browser.postForm(action, fields);
        String callback = TestBrowser.location(answer);
        String code = KeycloakFlow.codeFrom(callback);
        HttpResponse<String> tokens = KeycloakFlow.redeemCode(browser, PUBLIC_URL + "/auth", "desktop-client", null,
                DESKTOP_REDIRECT, code, verifier);
        String accessToken = KeycloakFlow.tokenOf(tokens, "access_token");
        TestBrowser desktop = new TestBrowser();
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + accessToken);

        HttpResponse<String> me = desktop.get(PUBLIC_URL + "/api/me", bearer);

        assertEquals(200, me.statusCode());
        assertEquals("{\"username\":\"alice\",\"displayName\":\"Alice Muster\",\"admin\":false}", me.body());
    }

    /**
     * W7 (c), P7: Ein Token aus dem Password-Grant von admin-cli ist beim Gateway
     * wertlos, weil azp nicht desktop-client ist. invalid_token zeigt, dass der
     * Decoder das Token geprüft und abgelehnt hat, nicht bloss übersehen.
     */
    @Test
    void adminCliTokenIsRejected() throws Exception {
        TestBrowser browser = new TestBrowser();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "password");
        fields.put("client_id", "admin-cli");
        fields.put("username", "alice");
        fields.put("password", "alice-demo");
        HttpResponse<String> tokens = browser.postForm(PUBLIC_URL + "/auth/realms/chat/protocol/openid-connect/token", fields);
        String accessToken = KeycloakFlow.tokenOf(tokens, "access_token");
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + accessToken);

        HttpResponse<String> me = browser.get(PUBLIC_URL + "/api/me", bearer);

        HttpHeaders headers = me.headers();
        List<String> challenges = headers.allValues("WWW-Authenticate");
        String challenge = challenges.toString();
        assertEquals(401, me.statusCode());
        assertTrue(challenge.contains("invalid_token"), challenge);
    }
```

- [ ] **Schritt 3: Tests laufen lassen und Fehlschlag bestätigen**

*Teil A* — nur die zwei neuen Tests, `BearerTokenTest.java` liegt noch nicht im Ordner (Docker läuft):
Ausführen: `mvn -q -pl web-gateway test -Dtest=LoginIntegrationTest; echo "Exit: $?"`
Erwartet: `desktopClientTokenOpensApi` rot (`401` statt `200`: ohne Resource Server zählt der Header nicht), `adminCliTokenIsRejected` rot (`401` kommt zwar, aber ohne `invalid_token`, denn niemand hat das Token geprüft); die sieben Tests aus Task 6 grün.

*Teil B* — jetzt `BearerTokenTest.java` aus Schritt 1 anlegen:
Ausführen: `mvn -q -pl web-gateway test -Dtest=BearerTokenTest`
Erwartet: Übersetzungsfehler — `JwtConfig` gibt es noch nicht.

Beleg: `task-07-rot.txt`, Teil A und B.

- [ ] **Schritt 4: Decoder, Prüfungen und Resource Server**

`config/AuthorizedPartyValidator.java` (neu):
- `public class AuthorizedPartyValidator implements OAuth2TokenValidator<Jwt>`, Konstruktor `AuthorizedPartyValidator(String expectedClientId)`;
- `validate(Jwt token)`: `OAuth2TokenValidatorResult.success()`, wenn der Claim `azp` genau `expectedClientId` ist; sonst `failure(…)` mit einem `OAuth2Error` mit Code `invalid_token` und englischer Beschreibung (sie steht im Header `WWW-Authenticate`). Fehlt `azp`, ist das Token abgelehnt;
- Javadoc: warum `azp` und nicht `aud` (Spezifikation 2.6: ohne `default-roles-chat` gibt es kein `aud`, mit wäre es `account`). Eine benannte Klasse, weil `JwtClaimValidator` ein Lambda verlangte.

`config/JwtConfig.java` (neu) — nach dem Vorbild von `config/JwtConfig.java` im Archiv, geändert: zusätzlich `azp`, die Prüfungen in einer öffentlichen Methode, Javadoc über jeder Methode:
- `public static final String DESKTOP_CLIENT = "desktop-client"`;
- `public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer)`: ein `DelegatingOAuth2TokenValidator` aus `JwtValidators.createDefaultWithIssuer(issuer)` (Issuer, Ablauf mit 60 s Spielraum) und `new AuthorizedPartyValidator(DESKTOP_CLIENT)`. Öffentlich, damit `BearerTokenTest` genau diese Prüfungen benutzt;
- `@Bean public JwtDecoder jwtDecoder(GatewayProperties gatewayProperties)`: `NimbusJwtDecoder.withJwkSetUri(internalRealmUrl() + "/protocol/openid-connect/certs").build()`, dazu `setJwtValidator(tokenValidator(publicRealmUrl()))`. Den JWKS holt der Decoder erst beim ersten Token und wieder, wenn ein unbekannter Schlüssel auftaucht (3.1, Punkt 5); das Gateway startet also ohne Keycloak (F1).

`config/ApiBearerTokenResolver.java` (neu):
- `public class ApiBearerTokenResolver implements BearerTokenResolver`, Konstruktor mit dem `RequestMatcher` für `/api/**` und `/ws/**`;
- `resolve(HttpServletRequest request)`: ausserhalb dieser Pfade `null` (die Anfrage zählt dann als ohne Token), sonst das Ergebnis eines `DefaultBearerTokenResolver`, der den Header `Authorization: Bearer …` liest;
- Javadoc: Seiten und Login laufen immer über die Session (Spezifikation 2.1).

`config/SecurityConfig.java`, dritte Fassung: `http.oauth2ResourceServer(resourceServer -> resourceServer.bearerTokenResolver(bearerTokenResolver).jwt(Customizer.withDefaults()))` mit `new ApiBearerTokenResolver(apiAndWebSocket())`. Den `JwtDecoder` nimmt Spring aus `JwtConfig`. Ein ungültiges Token beantwortet der Resource Server selbst mit `401` und `error="invalid_token"`; die Einstiegspunkte aus Task 6 gelten weiter für Anfragen ganz ohne Anmeldung.

> **Fallen:**
> 1. **`jwt()` beweist keine Ablehnung:** Es legt ein fertiges `Jwt` in die Anfrage und umgeht
>    Decoder und Prüfungen. Mit `jwt()` und fremdem `azp` käme `200`. Deshalb signiert
>    `BearerTokenTest` eigene Tokens (Spezifikation 6.4).
> 2. **Ein Decoder, der alles ablehnt, bestünde alle Ablehnungen.** `validDesktopTokenIsAccepted`
>    zeigt, dass ein richtiges Token durchkommt, und `desktopClientTokenOpensApi` dasselbe mit
>    einem echten Token und dem JWKS von Keycloak.
> 3. **60 s Spielraum bei `exp`:** Ein Token, das vor 30 s abgelaufen ist, gilt bei Spring noch.
>    Der Test nimmt deshalb eines, das seit 10 min abgelaufen ist.

- [ ] **Schritt 5: Tests laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl web-gateway test -Dtest='BearerTokenTest,LoginIntegrationTest'; echo "Exit: $?"`
Erwartet: alle grün, `BearerTokenTest` mit 9 und `LoginIntegrationTest` mit 9 Tests, `Exit: 0`.

- [ ] **Schritt 6: Alle Tests des Moduls und die Code-Regeln**

Ausführen: `mvn -q -pl web-gateway test; echo "Exit: $?"`, dann `grep -rln -- '->' web-gateway/src` und `grep -rn '::' web-gateway/src`
Erwartet: alle Tests grün; die erste Suche nennt nur `SecurityConfig.java`, die zweite findet nichts.
Beleg: `task-07-gruen.txt`, mit Schritt 5.

- [ ] **Schritt 7: Committen**

```bash
git add web-gateway/src \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-07-rot.txt docs/belege/2026-10-02-baustein-1/task-07-gruen.txt
git commit -m "feat: Gateway prüft Bearer-Tokens" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Schritt 8: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `images` und `abnahme` grün. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 8: RabbitMQ gesund erst mit offenem Port, dann das Gateway mit dem einzigen Port

**Warum an dieser Stelle:** Das Gateway arbeitet allein richtig (Task 5 bis 7); jetzt kommt es in den Stack, mit dem einzigen Port des Systems (Spezifikation 4.3, W1). Mit ihm warten drei Dienste darauf, dass RabbitMQ «gesund» ist, und das soll heissen: Port 5672 nimmt Verbindungen an. `ping` meldet «gesund» schon vorher (spec-batch-writer.md 3.3, F7). Das ist ein eigenes Thema und deshalb ein eigener Commit vor dem des Gateways (Arbeitsweise: Ausnahme Task 8).

**Dateien:**
- Ändern: `docker-compose.yml` (Healthcheck `rabbitmq`; Dienst `web-gateway`; Kommentare am Anfang und am Ende)
- Unverändert: `web-gateway/Dockerfile` (seit Task 2; die Stufe für die Web-UI kommt in Task 9)
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-08a-rot.txt`, `task-08a-gruen.txt` (Healthcheck) und `task-08b-rot.txt`, `task-08b-gruen.txt` (Gateway); Ändern: `notizen.md`
- Test: Prüfbefehle für den ganzen Stack, lokal; im CI baut der Job `images` ab jetzt auch das Gateway

**Schnittstellen:**
- Verbraucht: Modul mit Login und Bearer (Task 2 bis 7), Dienst `keycloak` (Task 4)
- Stellt bereit: Compose-Dienst `web-gateway` mit `127.0.0.1:8080:8080`, wartet auf `keycloak` und `rabbitmq` (gesund) und `chat-service` (gestartet); RabbitMQ gilt als gesund, wenn seine Ports Verbindungen annehmen. Task 9 ergänzt das Dockerfile, Task 10 prüft alles mit `abnahme-system.sh`.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

Der ganze Stack, von aussen geprüft wie mit einem Browser, lokal (Docker läuft, `.env` vollständig). `curl` schreibt seine Antworten nach `target/t8/`, nie nach `/dev/null` (Globale Vorgaben):

```bash
export MSYS_NO_PATHCONV=1
mkdir -p target/t8
docker compose up -d --build --wait --wait-timeout 300; echo "Exit: $?"
rabbitmq=$(docker compose ps -q rabbitmq)
docker inspect --format '{{json .Config.Healthcheck.Test}}' "$rabbitmq"
docker compose ps --format '{{.Service}} {{.Ports}}' | grep -- '->'
curl -s -o target/t8/root.html -w '%{http_code} %{redirect_url}\n' http://localhost:8080/
curl -s http://localhost:8080/auth/realms/chat/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
```

- [ ] **Schritt 2: Rot lokal**

Ausführen: die Befehle aus Schritt 1, vor jeder Änderung an `docker-compose.yml`
Erwartet: `docker inspect` zeigt `["CMD","rabbitmq-diagnostics","-q","ping"]`; keine Zeile mit `->`; der erste `curl` meldet `000 `, weil auf `localhost:8080` niemand zuhört; der zweite gibt nichts aus.
Beleg: `task-08a-rot.txt` (der Healthcheck) und `task-08b-rot.txt` (Port und `curl`).

- [ ] **Schritt 3: Healthcheck von RabbitMQ**

`docker-compose.yml`, im Dienst `rabbitmq` an Stelle der Zeile `test:`; die Zeiten bleiben:

```yaml
    healthcheck:
      # Gesund heisst: Die Ports nehmen Verbindungen an. "ping" meldete schon
      # vorher gesund (spec-batch-writer.md 3.3, F7), und drei Dienste warten
      # auf diesen Zustand (spec-web-gateway.md 4.3).
      test: ["CMD", "rabbitmq-diagnostics", "-q", "check_port_connectivity"]
```

Ausführen: `docker compose up -d --wait rabbitmq`, dann `docker inspect` wie in Schritt 1 und `docker compose ps --format '{{.Service}} {{.Health}}'`; danach `bash scripts/abnahme.sh; echo "Exit: $?"`
Erwartet: `["CMD","rabbitmq-diagnostics","-q","check_port_connectivity"]`, die Zeile `rabbitmq healthy`; die Abnahme von Bewertung 1 mit S2 bis S8 `PASS` und `Exit: 0`. Das Gateway fehlt noch, die übrigen Befehle aus Schritt 1 bleiben rot.
Beleg: `task-08a-gruen.txt`.

> **Kein deterministisch roter Nachweis für den Healthcheck selbst:** Dass `ping` zu früh «gesund»
> meldet, passiert nur in einem kurzen Moment beim Start (spec-batch-writer.md F7) und lässt sich
> nicht sicher nachstellen. Belegt wird deshalb, dass der neue Befehl im Container steht, der
> Dienst damit gesund wird und S2 bis S8 grün bleiben.

- [ ] **Schritt 4: Den Healthcheck committen und pushen**

```bash
git add docker-compose.yml \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-08a-rot.txt docs/belege/2026-10-02-baustein-1/task-08a-gruen.txt
git commit -m "fix: RabbitMQ-Healthcheck prüft die Ports statt nur den Prozess" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

Der Push startet einen eigenen Lauf für diesen Commit; ein Push mit zwei neuen Commits liefe nur für den letzten. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

- [ ] **Schritt 5: Dockerfile des Gateways**

`web-gateway/Dockerfile` gibt es seit Task 2 (Stufe «bauen» mit allen drei Modul-POMs, Stufe «laufen» mit `EXPOSE 8080`). Diese Aufgabe ändert es nicht. Neu ist nur, dass der Job `images` es baut, weil der Dienst ab jetzt in `docker-compose.yml` steht.

- [ ] **Schritt 6: Dienst `web-gateway` in `docker-compose.yml`**

Der Kommentar am Anfang der Datei:

```yaml
# Baustein 1: Broker, chat-service, Datenbank, batch-writer, Keycloak und
# web-gateway. Genau ein Port nach aussen: 127.0.0.1:8080 am web-gateway.
```

Nach dem Dienst `keycloak` (Spezifikation 4.3):

```yaml
  # Der einzige Dienst mit Port (spec-web-gateway.md 1.2): Login über Keycloak,
  # Proxy unter /auth, Web-UI, WebSocket. Er braucht beim Start keinen anderen
  # Dienst; depends_on sorgt nur dafür, dass der Login klappt, sobald der Port
  # offen ist (3.5). Ohne chat-service startet er trotzdem, Senden scheitert
  # dann sichtbar (F3).
  web-gateway:
    build:
      context: .
      dockerfile: web-gateway/Dockerfile
    environment:
      PUBLIC_URL: http://localhost:8080
      KEYCLOAK_INTERNAL_URL: http://keycloak:8080/auth
      KEYCLOAK_CLIENT_SECRET: ${KEYCLOAK_CLIENT_SECRET:?KEYCLOAK_CLIENT_SECRET fehlt in .env}
      CHAT_SERVICE_URL: http://chat-service:8080
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: ${RABBITMQ_USER}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
    ports:
      # Der EINZIGE ports:-Eintrag der Datei. 127.0.0.1: nur vom eigenen Rechner erreichbar.
      - "127.0.0.1:8080:8080"
    depends_on:
      keycloak:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      chat-service:
        condition: service_started
    # Nur ein Sicherheitsnetz wie beim batch-writer: Keiner der Fehlerfälle
    # F1 bis F15 lässt das Gateway abstürzen.
    restart: unless-stopped
    networks:
      - chat-net
```

Der Kommentar am Ende, an Stelle von «KEIN ports:-Eintrag in dieser Datei …»:

```yaml
# Genau EIN ports:-Eintrag in dieser Datei, am web-gateway (PLANUNG.md 1).
# Alle anderen Dienste erreicht man nur im internen Netz chat-net.
```

Prüfung ohne Container: `docker compose config --quiet; echo "Exit: $?"` → `Exit: 0`, und `grep -c 'ports:' docker-compose.yml` → `1`.

- [ ] **Schritt 7: Grün lokal**

Ausführen: die Befehle aus Schritt 1, danach `bash scripts/abnahme.sh; echo "Exit: $?"`
Erwartet: `Exit: 0`; `check_port_connectivity`; genau eine Zeile mit `->`, und zwar `web-gateway 127.0.0.1:8080->8080/tcp`; `302 http://localhost:8080/oauth2/authorization/keycloak`; `"issuer":"http://localhost:8080/auth/realms/chat"`. Die Abnahme von Bewertung 1 bleibt grün, denn S2 startet das Gateway nicht: S2 bis S8 `PASS`, `Exit: 0`.
Beleg: `task-08b-gruen.txt`.

- [ ] **Schritt 8: Das Gateway committen**

```bash
git add docker-compose.yml \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-08b-rot.txt docs/belege/2026-10-02-baustein-1/task-08b-gruen.txt
git commit -m "chore: web-gateway mit dem einzigen Port in docker-compose" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Schritt 9: Pushen, CI-Läufe prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions dieser Lauf und der des Healthcheck-Commits aus Schritt 4 grün; `images` baut jetzt auch `web-gateway`. Die Nummern der Läufe kommen beim nächsten Commit in `notizen.md`.

---

## Task 9: Web-UI zeigt den angemeldeten Benutzer

**Warum an dieser Stelle:** `/api/me` gibt es seit Task 6, der Dienst im Stack seit Task 8. Jetzt kommt die Oberfläche dazu, die ein Mensch sieht: «Angemeldet als …» und ein Link «Abmelden» (Spezifikation 1.2). Damit ist Schritt 2 aus PLANUNG.md 6 erfüllt: «React zeigt den Benutzernamen». Den Chat bekommt die Web-UI in Task 13.

**Dateien:**
- Anlegen: `web-ui/package.json`, `web-ui/package-lock.json` (erzeugt `npm install`), `web-ui/index.html`, `web-ui/vite.config.ts`, `web-ui/tsconfig.json`, `web-ui/src/main.tsx`, `web-ui/src/App.tsx`, `web-ui/src/currentUser.ts`
- Ändern: `web-gateway/Dockerfile` (Stufe für die Web-UI), `.github/workflows/build.yml` (Job `web-ui`)
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-09-rot.txt`, `task-09-gruen.txt`; Ändern: `notizen.md`
- Test: `web-ui/src/currentUser.test.ts` (Vitest); Prüfbefehle für den Inhalt des Gateway-Images; beides lokal

**Schnittstellen:**
- Verbraucht: `GET /api/me` (Task 6), `GET /logout` (Task 6), Dockerfile aus Task 2
- Stellt bereit: `web-ui/src/currentUser.ts` mit `type CurrentUser`, `describeUser(user)`, `isLoginRequired(status)`, `readCurrentUser(data)`; die gebaute Web-UI unter `/` im Jar des Gateways; Job `web-ui` im CI. Task 13 baut den Chat in `App.tsx` ein, Task 16 ergänzt Playwright in `package.json`.

- [ ] **Schritt 1: Gerüst der Web-UI**

`web-ui/package.json` (JSON kennt keine Kommentare; die Werkzeuge begründet Spezifikation 4.4):

```json
{
  "name": "web-ui",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "react": "^19.1.0",
    "react-dom": "^19.1.0"
  },
  "devDependencies": {
    "@types/react": "^19.1.0",
    "@types/react-dom": "^19.1.0",
    "@vitejs/plugin-react": "^5.0.0",
    "typescript": "~5.9.2",
    "vite": "^7.1.0",
    "vitest": "^3.2.4"
  }
}
```

`web-ui/tsconfig.json` (wie in der Vorarbeit):

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "noEmit": true
  },
  "include": ["src"]
}
```

`web-ui/vite.config.ts`:

```ts
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Vite baut die React-App zu statischen Dateien (dist/). Das Dockerfile des
// web-gateway kopiert sie in dessen Jar, ausgeliefert werden sie unter /.
// Vitest nimmt dieselbe Konfiguration. Die Tests laufen in Node, ohne Browser,
// weil sie nur reine Funktionen prüfen.
const reactPlugin = react()

export default defineConfig({
  plugins: [reactPlugin],
  test: {
    environment: 'node',
  },
})
```

`web-ui/index.html` — wie `web-ui/index.html` im Archiv: `lang="de"`, Titel «M321 Chat-App», ein `<div id="root">` mit dem Kommentar «Hier hängt React die Oberfläche ein», `<script type="module" src="/src/main.tsx">`.

Ausführen: `cd web-ui && npm install`
Erwartet: `node_modules/` (in `.gitignore`) und `package-lock.json` entstehen. Ab hier installiert jeder Rechner und das CI mit `npm ci` genau diese Versionen (Spezifikation 4.4).

- [ ] **Schritt 2: Den fehlschlagenden Test schreiben**

`web-ui/src/currentUser.test.ts` — benannte Funktionen statt Pfeilfunktionen, wie überall in der Web-UI:

```ts
import { expect, test } from 'vitest'
import { describeUser, isLoginRequired, readCurrentUser } from './currentUser'
import type { CurrentUser } from './currentUser'

/** Der Text, den W12 im Browser sucht: «Angemeldet als Alice Muster». */
function showsDisplayName(): void {
  const alice: CurrentUser = { username: 'alice', displayName: 'Alice Muster', admin: false }
  const text = describeUser(alice)
  expect(text).toBe('Angemeldet als Alice Muster')
}

/** Nur 401 heisst «Session weg, Seite neu laden» (Spezifikation 2.3, F7); andere Fehler nicht. */
function onlyUnauthorizedMeansLogin(): void {
  const unauthorized = isLoginRequired(401)
  const forbidden = isLoginRequired(403)
  const serverError = isLoginRequired(500)
  const ok = isLoginRequired(200)
  expect(unauthorized).toBe(true)
  expect(forbidden).toBe(false)
  expect(serverError).toBe(false)
  expect(ok).toBe(false)
}

/** Eine vollständige Antwort von /api/me wird zum CurrentUser, mit genau den drei Feldern. */
function readsAnswerOfApiMe(): void {
  const answer: unknown = JSON.parse('{"username":"admin","displayName":"Ada Admin","admin":true}')
  const user = readCurrentUser(answer)
  expect(user).toEqual({ username: 'admin', displayName: 'Ada Admin', admin: true })
}

/** Fehlt ein Feld oder hat es den falschen Typ, gibt es keinen Benutzer statt eines halben. */
function rejectsIncompleteAnswer(): void {
  const withoutName = readCurrentUser({ username: 'alice', admin: false })
  const adminAsText = readCurrentUser({ username: 'alice', displayName: 'Alice Muster', admin: 'true' })
  const nothing = readCurrentUser(null)
  const list = readCurrentUser(['alice'])
  expect(withoutName).toBeNull()
  expect(adminAsText).toBeNull()
  expect(nothing).toBeNull()
  expect(list).toBeNull()
}

test('describeUser zeigt den Anzeigenamen', showsDisplayName)
test('isLoginRequired nur bei 401', onlyUnauthorizedMeansLogin)
test('readCurrentUser liest die Antwort von /api/me', readsAnswerOfApiMe)
test('readCurrentUser lehnt unvollständige Antworten ab', rejectsIncompleteAnswer)
```

- [ ] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `cd web-ui && npm test`
Erwartet: Fehlschlag — `Failed to resolve import "./currentUser" from "src/currentUser.test.ts"`, die Datei gibt es noch nicht.
Beleg: `task-09-rot.txt`, Teil A.

- [ ] **Schritt 4: `currentUser.ts` und die Oberfläche**

`web-ui/src/currentUser.ts` (neu), reine Funktionen ohne React, jede mit Kommentar:
- `export type CurrentUser = { username: string; displayName: string; admin: boolean }`: genau die Antwort von `/api/me` (Spezifikation 2.2);
- `export function describeUser(user: CurrentUser): string` → `'Angemeldet als ' + user.displayName`;
- `export function isLoginRequired(status: number): boolean` → `true` genau bei `401`;
- `export function readCurrentUser(data: unknown): CurrentUser | null`: prüft mit `typeof`, dass `data` ein Objekt und kein Array ist und dass `username` und `displayName` Texte sind und `admin` ein Wahrheitswert; sonst `null`. Kein `as any`: Ein Tippfehler in einem Feldnamen fällt beim Bauen auf (Spezifikation 4.4).

`web-ui/src/App.tsx` (neu) — nach dem Vorbild von `web-ui/src/App.tsx` im Archiv, geändert: ohne Räume und Queue-Tiefe (Baustein 2 und 3), ohne `map()` und ohne Pfeilfunktionen, `401` lädt die Seite neu:
- `export function App()` hält `user` (`CurrentUser | null`) und `problem` (`string | null`) im Zustand;
- beim ersten Anzeigen ruft `useEffect(startLoadingUser, [])` die benannte Funktion `startLoadingUser()`; sie startet die asynchrone `loadUser()` (ein Effekt selbst darf nicht asynchron sein);
- `loadUser()`: `fetch('/api/me')`; bei `isLoginRequired(status)` `window.location.reload()`, denn das Gateway leitet die neu geladene Seite zum Login (F7); bei einem anderen Fehler `problem` = «Benutzer konnte nicht geladen werden (HTTP …)»; sonst `readCurrentUser(…)` und bei `null` `problem` = «Unerwartete Antwort von /api/me»;
- Anzeige ohne Ternary, mit `if` und frühem `return`: das Problem in `<p role="alert">`, sonst «Lade Benutzer …», sonst eine Kopfzeile mit dem Text von `describeUser(user)` (vorher in eine Variable) und dem Link `<a href="/logout">Abmelden</a>`. Ein einfacher Link genügt, weil `GET /logout` kein CSRF-Token braucht (Spezifikation 2.1).

`web-ui/src/main.tsx` (neu) — wie `web-ui/src/main.tsx` im Archiv: sucht `#root`, wirft einen Fehler, wenn es fehlt, `createRoot(rootElement)` in eine Variable, dann `root.render(<StrictMode><App /></StrictMode>)`; ein Kommentar oben sagt, warum es die Datei gibt.

- [ ] **Schritt 5: Tests und Build lokal grün bestätigen**

Ausführen: `cd web-ui && npm test && npm run build`
Erwartet: `4 passed`; danach prüft `tsc --noEmit` die Typen ohne Fehler, und `vite build` schreibt `dist/index.html` und `dist/assets/…` (`dist/` steht in `.gitignore`).
Beleg: `task-09-gruen.txt`, Teil A.

- [ ] **Schritt 6: Rot lokal für das Image**

Liegt die gebaute Web-UI im Jar des Gateways? Die Prüfung baut das Image, kopiert das Jar heraus und listet es mit `jar tf` aus dem JDK:

```bash
export MSYS_NO_PATHCONV=1
mkdir -p target/t9
docker build -f web-gateway/Dockerfile -t it3c-m321-web-gateway:task-09 .; echo "Exit: $?"
docker create --name t9-jar it3c-m321-web-gateway:task-09
docker cp t9-jar:/app/app.jar target/t9/app.jar
docker rm t9-jar
jar tf target/t9/app.jar | grep 'BOOT-INF/classes/static/'
```

Erwartet: Das Image baut (`Exit: 0`), aber die letzte Zeile findet nichts: Das Dockerfile aus Task 2 baut die Web-UI noch nicht, im Jar fehlen `static/index.html` und `static/assets/…`.
Beleg: `task-09-rot.txt`, Teil B.

- [ ] **Schritt 7: Stufe für die Web-UI im Dockerfile**

`web-gateway/Dockerfile`, vor der Stufe «Gateway bauen» (Spezifikation 4.4):

```dockerfile
# Stufe 1: Web-UI bauen
FROM node:22-alpine AS ui
WORKDIR /ui
# Erst nur die Paketlisten: Solange sie gleich bleiben, nimmt Docker die
# installierten Pakete aus dem Zwischenspeicher.
COPY web-ui/package.json web-ui/package-lock.json ./
# npm ci installiert genau die Versionen aus package-lock.json.
RUN npm ci
# Dann der Quelltext, Datei für Datei. Ein lokales node_modules/ oder dist/
# kommt so nie ins Image, auch ohne .dockerignore (spec-web-gateway.md 4.4).
COPY web-ui/tsconfig*.json ./
COPY web-ui/vite.config.ts web-ui/index.html ./
COPY web-ui/src ./src
# Erst die Typen prüfen, dann bauen (Skript "build" in package.json).
RUN npm run build
```

Die bisherige Stufe 1 heisst jetzt «Stufe 2: Gateway bauen», die bisherige Stufe 2 «Stufe 3: laufen». In Stufe 2 nach `COPY web-gateway/src web-gateway/src`:

```dockerfile
# Die gebaute Web-UI kommt zu den statischen Dateien des Gateways. Spring Boot
# liefert alles unter static/ aus, index.html unter /.
COPY --from=ui /ui/dist web-gateway/src/main/resources/static
```

> **Falle – `COPY web-ui/ ./`:** So stand es in der Vorarbeit. Liegt lokal ein `node_modules/`
> von Windows im Ordner, käme es mit ins Image und überschriebe die Pakete aus `npm ci`. Die
> einzelnen `COPY`-Zeilen nennen nur, was gebraucht wird.

- [ ] **Schritt 8: Job `web-ui` im CI**

`.github/workflows/build.yml`, nach dem Job `maven`:

```yaml
  # Web-UI: Unit-Tests mit Vitest, dann Typen prüfen und bauen wie im Dockerfile.
  web-ui:
    runs-on: ubuntu-24.04
    defaults:
      run:
        working-directory: web-ui
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-node@v5
        with:
          node-version: "22"
          cache: npm
          cache-dependency-path: web-ui/package-lock.json
      - name: npm ci
        run: npm ci
      - name: npm test
        run: npm test
      - name: npm run build
        run: npm run build
```

- [ ] **Schritt 9: Grün lokal für das Image und die Web-UI**

Ausführen: die Befehle aus Schritt 6, danach in `web-ui/` die Schritte des neuen Jobs: `npm ci && npm test && npm run build`
Erwartet: Die letzte Zeile aus Schritt 6 nennt jetzt `BOOT-INF/classes/static/index.html` und Dateien unter `BOOT-INF/classes/static/assets/`; `npm ci` installiert genau die Versionen aus `package-lock.json`, `4 passed`, Build ohne Fehler.
Beleg: `task-09-gruen.txt`, Teil B.

- [ ] **Schritt 10: Committen**

```bash
git add web-ui web-gateway/Dockerfile .github/workflows/build.yml \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-09-rot.txt docs/belege/2026-10-02-baustein-1/task-09-gruen.txt
git commit -m "feat: Web-UI zeigt den angemeldeten Benutzer" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Erwartet: `git show --stat HEAD` nennt weder `node_modules` noch `dist` noch den Plan.

- [ ] **Schritt 11: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `web-ui`, `images` und `abnahme` grün; `web-ui` zeigt `4 passed`. Die Nummer des Laufs kommt beim nächsten Commit in `notizen.md`.

---

## Task 10: Systemabnahme Login (W1, W2, W3, W7, W8, W10)

**Warum an dieser Stelle:** Login, Proxy, Bearer, Stack und Web-UI sind da (Task 1 bis 9). Jetzt prüft ein Skript den Login so, wie die Abnahme es tut: von aussen, nur über `http://localhost:8080`, auf einem frisch gestarteten Stack (Spezifikation 6.1, 6.2). Es läuft ab hier bei jedem Push. Danach ist Schritt 2 aus PLANUNG.md 6 fertig: Tag `schritt-2`.

**Dateien:**
- Anlegen: `scripts/abnahme-system.sh`
- Ändern: `.github/workflows/build.yml` (Job `abnahme-system`)
- Anlegen: `docs/belege/2026-10-02-baustein-1/task-10-rot.txt`, `task-10-gruen.txt`; Ändern: `notizen.md`
- Test: das Skript selbst; roter Nachweis lokal mit einem absichtlich zweiten Port, der nie committet wird

**Schnittstellen:**
- Verbraucht: den ganzen Stack (Task 4, 8, 9), `scripts/env-pruefen.sh` (Task 1), Demo-Benutzer und Clients aus dem Realm (Task 3)
- Stellt bereit: `bash scripts/abnahme-system.sh` → Tabelle «gemessen / erwartet», Exit-Code 0 nur, wenn alles bestanden ist. Die Hilfen `wait_until`, `status`, `login`, `me`, `pkce_token`, `system_up` aus Spezifikation 6.1. Task 14 ergänzt W4, W5, W6, W9, W11, den WebSocket-Teil von W7 und die Hilfen `sql`, `probe`, `stored_once`, `ms`.

- [ ] **Schritt 1: Das Skript schreiben**

`scripts/abnahme-system.sh` — Stand: Task 10. Die Hilfen stehen wörtlich wie in Spezifikation 6.1, mit Kommentar.

```bash
#!/usr/bin/env bash
# Systemabnahme von Baustein 1 (spec-web-gateway.md, Abschnitt 6): stellt die
# Kriterien W1 bis W11 nach, in der Reihenfolge der Spezifikation und auf
# demselben Stack, ohne Aufräumen dazwischen. W12 (zwei echte Browser) läuft
# getrennt mit Playwright. Stand Task 10: W1, W2, W3, W7 ohne WebSocket, W8, W10.
#
# Gemessen wird von aussen wie ein Benutzer: curl gegen http://localhost:8080,
# den einzigen offenen Port. Kein jq und kein Python, JSON lesen grep und sed.
# Zwischendateien liegen in target/abnahme-system/. curl schreibt auch
# Antworten, die niemand liest, dorthin: Das native curl unter Git Bash kann
# mit MSYS_NO_PATHCONV=1 das Null-Gerät nicht öffnen (Exit-Code 23).
#
# Aufruf im Wurzelverzeichnis:   bash scripts/abnahme-system.sh
#
# ACHTUNG: Das Skript beginnt mit "docker compose down -v" und löscht damit
# alle Daten der Datenbank.
#
# Ergebnis: eine Tabelle "gemessen / erwartet" und Exit-Code 0, wenn alle
# Kriterien bestanden sind, sonst 1.

set -u
# Git Bash unter Windows würde Argumente, die mit "/" beginnen, in
# Windows-Pfade umschreiben. Das schalten wir ab.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.." || exit 1

if [ ! -f .env ]; then
  cp .env.example .env
fi
bash scripts/env-pruefen.sh || exit 1
# Die .env Zeile für Zeile übernehmen, wie in scripts/abnahme.sh: Ein
# Windows-Zeilenende (CR) wird abgeschnitten, leere Zeilen und Kommentare
# werden übersprungen.
while IFS= read -r line; do
  line=${line%$'\r'}
  case "$line" in
    '' | '#'*) continue ;;
  esac
  export "$line"
done < .env

G=http://localhost:8080
T=target/abnahme-system
mkdir -p "$T"
PW_ALICE=${DEMO_PASSWORD_ALICE:-alice-demo}
PW_BOB=${DEMO_PASSWORD_BOB:-bob-demo}
PW_ADMIN=${DEMO_PASSWORD_ADMIN:-admin-demo}
# Rückleitung des Desktop-Clients, URL-kodiert. Auf Port 53682 hört niemand zu.
ENC=http%3A%2F%2F127.0.0.1%3A53682%2Fcallback
SUMMARY=""
FAILURES=0

# ------------------------------------------------------------------ Hilfen

# Wartet höchstens $1 Sekunden, bis der Befehl dahinter gelingt. Prüft alle 2 s.
wait_until() {
  local deadline=$((SECONDS + $1))
  shift
  while [ "$SECONDS" -lt "$deadline" ]; do
    if "$@"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# Hält ein Ergebnis fest: Kriterium, gemessen, erwartet, 0 = bestanden.
report() {
  local verdict="PASS"
  if [ "$4" -ne 0 ]; then
    verdict="FAIL"
    FAILURES=$((FAILURES + 1))
  fi
  local line
  line=$(printf '%-3s %-4s gemessen: %s | erwartet: %s' "$1" "$verdict" "$2" "$3")
  echo "$line"
  SUMMARY="$SUMMARY$line"$'\n'
}

# contains TEXT TEIL: gelingt, wenn TEIL in TEXT vorkommt.
contains() {
  case "$1" in
    *"$2"*) return 0 ;;
  esac
  return 1
}

# status PFAD [CURL-OPTIONEN]: Status und Ziel einer Umleitung, ohne ihr zu folgen.
status() { local p=$1; shift; curl -s -o "$T/status.txt" -w '%{http_code} %{redirect_url}\n' "$@" "$G$p"; }

# login BENUTZER PASSWORT: Login über das Formular von Keycloak, Cookies in
# $T/BENUTZER.jar. Erwartete Ausgabe: "200 http://localhost:8080/".
login() {
  local jar="$T/$1.jar" action
  rm -f "$jar"
  action=$(curl -s -L -c "$jar" -b "$jar" "$G/oauth2/authorization/keycloak" \
    | grep -o 'action="[^"]*"' | head -1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')
  curl -s -L -c "$jar" -b "$jar" -o "$T/login.html" -w '%{http_code} %{url_effective}\n' \
    --data-urlencode "username=$1" --data-urlencode "password=$2" "$action"
}

# Ein Login ist gelungen, wenn er auf / endet; "/?continue" zählt mit (P14).
login_ok() {
  [ "$1" = "200 $G/" ] || [ "$1" = "200 $G/?continue" ]
}

# me BENUTZER: GET /api/me mit der Session dieses Benutzers, dahinter der Status.
me() { curl -s -b "$T/$1.jar" -w ' %{http_code}\n' "$G/api/me"; }

# pkce_token BENUTZER PASSWORT: Access-Token über desktop-client, Authorization
# Code mit PKCE, so wie es der Desktop-Client macht. curl folgt der Rückleitung
# auf Port 53682 nicht, es liest nur den Code daraus.
pkce_token() {
  local jar="$T/pkce.jar" verifier challenge action location code
  rm -f "$jar"
  verifier=$(openssl rand -hex 32)
  challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
  action=$(curl -s -c "$jar" -b "$jar" "$G/auth/realms/chat/protocol/openid-connect/auth?client_id=desktop-client&response_type=code&scope=openid&redirect_uri=$ENC&code_challenge=$challenge&code_challenge_method=S256" \
    | grep -o 'action="[^"]*"' | head -1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')
  location=$(curl -s -c "$jar" -b "$jar" -o "$T/pkce.html" -w '%{redirect_url}' \
    --data-urlencode "username=$1" --data-urlencode "password=$2" "$action")
  code=$(printf '%s' "$location" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  curl -s -X POST "$G/auth/realms/chat/protocol/openid-connect/token" -d grant_type=authorization_code \
    -d client_id=desktop-client -d "redirect_uri=$ENC" --data-urlencode "code=$code" -d "code_verifier=$verifier" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

# Bedingung für wait_until (W1): alle sechs Dienste laufen, keycloak und rabbitmq sind gesund.
system_up() {
  [ "$(docker compose ps --status running --services | sort | tr '\n' ' ')" = \
    "batch-writer chat-service keycloak postgres rabbitmq web-gateway " ] &&
  [ "$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -cE '^(keycloak|rabbitmq) healthy$')" = "2" ]
}

# ---------------------------------------------------------------- Kriterien

criterion_w1() {
  echo "== W1: frischer Start, sechs Dienste, genau ein Port"
  docker compose down -v --remove-orphans >/dev/null 2>&1
  docker compose up -d --build
  # Die Zeit zählt ab dem Ende von "up"; das Bauen zählt nicht mit (6.2).
  local start=$SECONDS
  wait_until 180 system_up
  local ok=$?
  local seconds=$((SECONDS - start))
  local running
  running=$(docker compose ps --status running --services | sort | tr '\n' ' ')
  local health
  health=$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -E '^(keycloak|rabbitmq) ' | tr '\n' ' ')
  local ports
  ports=$(docker compose ps --format '{{.Service}} {{.Ports}}' | grep -- '->')
  if [ "$ports" != "web-gateway 127.0.0.1:8080->8080/tcp" ]; then
    ok=1
  fi
  report W1 "nach $seconds s laufend: ${running}| ${health}| Ports: $ports" \
    "6 Dienste, keycloak und rabbitmq healthy, nur web-gateway 127.0.0.1:8080->8080/tcp, höchstens 180 s" "$ok"
}

criterion_w2() {
  echo "== W2: ohne Anmeldung"
  local root login_start api issuer
  root=$(status /)
  login_start=$(status /oauth2/authorization/keycloak)
  api=$(status /api/me)
  issuer=$(curl -s "$G/auth/realms/chat/.well-known/openid-configuration" | grep -o '"issuer":"[^"]*"')
  local ok=1
  case "$login_start" in
    "302 $G/auth/realms/chat/protocol/openid-connect/auth?"*code_challenge_method=S256*)
      if [ "$root" = "302 $G/oauth2/authorization/keycloak" ] && [ "$api" = "401 " ] \
        && [ "$issuer" = "\"issuer\":\"$G/auth/realms/chat\"" ]; then
        ok=0
      fi
      ;;
  esac
  report W2 "/: $root | Login-Start: ${login_start%%\?*}?… | /api/me: $api| $issuer" \
    "302 zum Login-Start, 302 zu Keycloak mit S256, 401, Issuer $G/auth/realms/chat" "$ok"
}

criterion_w3() {
  echo "== W3: Login über das Formular, /api/me, kein Token im Browser"
  local alice_login alice_me admin_login admin_me jar_names jwt_hits cookie_line
  alice_login=$(login alice "$PW_ALICE")
  alice_me=$(me alice)
  admin_login=$(login admin "$PW_ADMIN")
  admin_me=$(me admin)
  jar_names=$(awk '$3 == "/" {print $6}' "$T/alice.jar" | tr '\n' ' ')
  jwt_hits=$(curl -s -b "$T/alice.jar" "$G/" "$G/api/me" | grep -c eyJ)
  cookie_line=$(curl -s -D - -o "$T/w3-x.txt" "$G/oauth2/authorization/keycloak" | grep -i '^set-cookie: JSESSIONID')
  local cookie_flags="fehlen"
  if contains "$cookie_line" "HttpOnly" && contains "$cookie_line" "SameSite=Lax"; then
    cookie_flags="HttpOnly, SameSite=Lax"
  fi
  local ok=1
  if login_ok "$alice_login" && login_ok "$admin_login" \
    && [ "$alice_me" = '{"username":"alice","displayName":"Alice Muster","admin":false} 200' ] \
    && [ "$admin_me" = '{"username":"admin","displayName":"Ada Admin","admin":true} 200' ] \
    && [ "$jar_names" = "JSESSIONID " ] && [ "$jwt_hits" = "0" ] \
    && [ "$cookie_flags" = "HttpOnly, SameSite=Lax" ]; then
    ok=0
  fi
  report W3 "Login: $alice_login, $admin_login | $alice_me | $admin_me | Cookies für /: $jar_names| eyJ: $jwt_hits | Session-Cookie: $cookie_flags" \
    "200 auf /, beide Antworten von /api/me, nur JSESSIONID, kein eyJ, HttpOnly und SameSite=Lax" "$ok"
}

criterion_w7() {
  echo "== W7: Bearer-Tokens (den Teil mit WebSocket ergänzt Task 14)"
  local at me_a status_b grant status_c status_d forms_d
  at=$(pkce_token alice "$PW_ALICE")
  me_a=$(curl -s -H "Authorization: Bearer $at" -w ' %{http_code}' "$G/api/me")
  status_b=$(curl -s -o "$T/w7b.txt" -w '%{http_code}' -H 'Authorization: Bearer kaputt' "$G/api/me")
  curl -s -o "$T/w7c.json" -X POST "$G/auth/realms/chat/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli -d username=alice --data-urlencode "password=$PW_ALICE"
  grant=$(sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p' "$T/w7c.json")
  # (c) ist bestanden, wenn Keycloak kein Token herausgibt oder das Gateway es ablehnt (P7).
  status_c="kein Token"
  if [ -n "$grant" ]; then
    status_c=$(curl -s -o "$T/w7c-me.txt" -w '%{http_code}' -H "Authorization: Bearer $grant" "$G/api/me")
  fi
  status_d=$(curl -s -o "$T/w7d.txt" -w '%{http_code} %{redirect_url}' \
    "$G/auth/realms/chat/protocol/openid-connect/auth?client_id=desktop-client&response_type=code&scope=openid&redirect_uri=$ENC")
  forms_d=$(grep -c kc-form-login "$T/w7d.txt")
  local ok=0
  case "$me_a" in
    *'"username":"alice"'*' 200') ;;
    *) ok=1 ;;
  esac
  [ "$status_b" = "401" ] || ok=1
  [ "$status_c" = "kein Token" ] || [ "$status_c" = "401" ] || ok=1
  case "$status_d" in
    '302 '*error=invalid_request* | '400 ') ;;
    *) ok=1 ;;
  esac
  [ "$forms_d" = "0" ] || ok=1
  report W7 "(a) /api/me ${me_a##* } | (b) $status_b | (c) admin-cli: $status_c | (d) ${status_d%% *}, Login-Formulare: $forms_d" \
    "(a) 200 mit alice, (b) 401, (c) kein Token oder 401, (d) 302 mit error=invalid_request oder 400, kein Formular" "$ok"
}

criterion_w8() {
  echo "== W8: Admin-Konsole, Realm master und Willkommensseite"
  local results=""
  local p
  for p in /auth/admin/ /auth/admin/master/console/ /auth/realms/master/.well-known/openid-configuration /auth/ /auth; do
    results="$results$(status "$p")"$'\n'
    results="$results$(status "$p" -b "$T/admin.jar")"$'\n'
  done
  local not_found
  not_found=$(printf '%s' "$results" | grep -c '^404 $')
  local ok=1
  if [ "$not_found" = "10" ]; then
    ok=0
  fi
  report W8 "404: $not_found von 10" "10 × 404, ohne und mit Anmeldung" "$ok"
}

criterion_w10() {
  echo "== W10: Abmelden mit einem einzigen Aufruf"
  login alice "$PW_ALICE" >/dev/null
  cp "$T/alice.jar" "$T/alt.jar"
  # Ein Aufruf, der allen Umleitungen folgt: Ein erster Aufruf ohne -L meldete
  # nur beim Gateway ab, und Keycloak meldete danach still wieder an.
  curl -s -L -D "$T/w10-kopf.txt" -b "$T/alice.jar" -c "$T/alice.jar" -o "$T/w10-seite.html" "$G/logout"
  local first forms old
  first=$(grep -i -m1 '^location:' "$T/w10-kopf.txt" | sed 's/^[^:]*: *//' | tr -d '\r')
  forms=$(grep -c kc-form-login "$T/w10-seite.html")
  old=$(curl -s -o "$T/w10-me.txt" -w '%{http_code}' -b "$T/alt.jar" "$G/api/me")
  local ok=1
  case "$first" in
    "$G/auth/realms/chat/protocol/openid-connect/logout?"*post_logout_redirect_uri=*)
      if [ "$forms" -ge 1 ] && [ "$old" = "401" ]; then
        ok=0
      fi
      ;;
  esac
  report W10 "erste Umleitung: ${first%%\?*}?… | Login-Formulare am Ende: $forms | alte Session: $old" \
    "Logout bei Keycloak mit post_logout_redirect_uri, am Ende das Login-Formular, alte Session 401" "$ok"
}

# ------------------------------------------------------------------- Ablauf

criterion_w1
criterion_w2
criterion_w3
criterion_w7
criterion_w8
criterion_w10

echo
echo "================================ Ergebnis ================================"
printf '%s' "$SUMMARY"
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES Kriterium/Kriterien nicht bestanden."
  exit 1
fi
echo "Alle Kriterien bestanden."
```

> **Fallen im Skript:**
> 1. **`-o /dev/null`** endet unter Git Bash mit `MSYS_NO_PATHCONV=1` mit Exit-Code 23, weil das
>    native `curl` den Pfad nicht kennt. Jede Antwort, die niemand liest, geht nach `$T/…`.
>    Umleitungen von bash selbst (`>/dev/null`) sind davon nicht betroffen.
> 2. **Abmelden in zwei Aufrufen** meldete nur beim Gateway ab; der zweite Aufruf fände bei
>    Keycloak noch die SSO-Session und meldete still wieder an. W10 folgt deshalb mit einem
>    einzigen `curl -L` allen Umleitungen und liest die erste aus der Kopfdatei.
> 3. **`401 ` mit Leerzeichen:** `status` gibt Status und Ziel aus; ohne Umleitung bleibt nach dem
>    Status ein Leerzeichen stehen. W2 und W8 vergleichen deshalb mit `"401 "` und `'^404 $'`.
> 4. **Session-Cookie aus einem frischen Aufruf:** W3 liest `Set-Cookie: JSESSIONID` aus dem
>    Login-Start ohne Cookie-Datei; nur dort setzt das Gateway das Cookie sicher neu.
> 5. **`mvn clean` im Wurzelordner löscht `target/`** mit allen Zwischendateien des Skripts (beim
>    Bauen von Task 2 gefunden). Was als Beleg bleiben soll, liegt deshalb nicht nur dort.

- [ ] **Schritt 2: Syntax lokal prüfen**

Ausführen: `bash -n scripts/abnahme-system.sh; echo "Exit: $?"`
Erwartet: `Exit: 0`, keine Meldung. Laufen lassen wir das Skript in Schritt 4 und 5; es beginnt mit `docker compose down -v`.

- [ ] **Schritt 3: Job `abnahme-system` im CI**

`.github/workflows/build.yml`, nach dem Job `abnahme`:

```yaml
  # Kriterien von Baustein 1 auf dem ganzen Stack, von aussen über den einzigen
  # Port (scripts/abnahme-system.sh, spec-web-gateway.md 6.2).
  abnahme-system:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v5
      - name: .env aus den Beispielwerten
        run: cp .env.example .env
      - name: Systemabnahme
        run: bash scripts/abnahme-system.sh
      - name: Protokolle bei Fehler
        if: failure()
        run: docker compose logs --no-color --tail 300
```

- [ ] **Schritt 4: Rot lokal, mit einem absichtlichen Fehler**

Ein Abnahmeskript, das nie rot war, beweist nichts. Im Dienst `rabbitmq` von `docker-compose.yml` steht **nur lokal**, nie committet, ein zweiter Port:

```yaml
    # Nur lokal für den roten Nachweis: ein zweiter Port, den W1 finden muss.
    ports:
      - "127.0.0.1:15672:15672"
```

Ausführen: `bash scripts/abnahme-system.sh; echo "Exit: $?"`
Erwartet: W1 `FAIL` mit zwei Zeilen unter «Ports»; W2, W3, W7, W8 und W10 `PASS`; am Schluss «1 Kriterium/Kriterien nicht bestanden.» und `Exit: 1`.
Beleg: `task-10-rot.txt`.

- [ ] **Schritt 5: Grün lokal**

Den zweiten Port wieder entfernen: `git diff --quiet -- docker-compose.yml; echo "Exit: $?"` → `Exit: 0`.
Ausführen: `bash scripts/abnahme-system.sh; echo "Exit: $?"`
Erwartet: alle sechs Kriterien `PASS`, «Alle Kriterien bestanden.», `Exit: 0`. Die gemessenen Werte (Sekunden bis W1, welcher Fall bei W7 c eintrat, für P7) kommen in den Beleg und in `notizen.md`.
Beleg: `task-10-gruen.txt`.

- [ ] **Schritt 6: Committen**

```bash
git add scripts/abnahme-system.sh .github/workflows/build.yml \
  docs/belege/2026-10-02-baustein-1/notizen.md \
  docs/belege/2026-10-02-baustein-1/task-10-rot.txt docs/belege/2026-10-02-baustein-1/task-10-gruen.txt
git commit -m "test: Systemabnahme Login (W1, W2, W3, W7, W8, W10)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Erwartet: `git show --stat HEAD` nennt weder `docker-compose.yml` noch den Plan.

- [ ] **Schritt 7: Pushen, CI-Lauf prüfen**

Ausführen: `git push`
Erwartet: in GitHub Actions `maven`, `web-ui`, `images`, `abnahme` und `abnahme-system` grün. Die Nummer des Laufs kommt in `notizen.md`.

- [ ] **Schritt 8: Meilenstein `schritt-2`**

Erst wenn der Lauf aus Schritt 7 grün ist:
1. Sammel-Commit für den Plan (Arbeitsweise): Häkchen von Task 1 bis 10, die Läufe und Fallen aus `notizen.md` und die Läufe aller Commits bis hier.
   ```bash
   git add docs/plan-web-gateway.md docs/belege/2026-10-02-baustein-1/notizen.md
   git commit -m "docs: Plan, Häkchen, Läufe und Fallen bis Task 10" \
     -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
   git push
   ```
2. Ist auch dieser Lauf grün, `main` per Fast-Forward nachziehen und den Meilenstein markieren:
   ```bash
   git switch main
   git merge --ff-only baustein/web-gateway
   git push
   git tag schritt-2
   git push origin schritt-2
   git switch baustein/web-gateway
   ```

Erwartet: `git log --oneline -1 schritt-2` zeigt `docs: Plan, Häkchen, Läufe und Fallen bis Task 10`, und der Plan in diesem Stand hat alle Häkchen bis Task 10. Das Häkchen dieses Schritts und der Lauf des Sammel-Commits kommen in den nächsten Sammel-Commit.

---
