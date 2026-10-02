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

1. **Ein Pfad mit `..`** (`/auth/realms/chat/%2e%2e/%2e%2e/admin/`, auch unkodiert) erreicht die Admin-Konsole nicht. Der Proxy würde den Pfad unverändert weitergeben, Keycloak ihn auflösen → Task 5, `KeycloakProxyIntegrationTest.dotSegmentsNeverReachKeycloak`.
2. **Gefälschte `X-Forwarded-*`-Header** aus dem Browser kommen nicht bei Keycloak an (P10) → Task 5, `forgedForwardedHeadersNeverReachKeycloak`.
3. **Keycloak startet gerade neu:** Der Browser bekommt innert 5 s einen Fehler statt einer hängenden Seite (P11, F1) → Task 5, `KeycloakProxyDownIntegrationTest.answersServerErrorWithinFiveSeconds`.
4. **Das Login-Formular kommt ohne CSRF-Token des Gateways durch**, ausserhalb von `/auth` gilt CSRF weiter → Task 5, `passesLoginFormPostWithoutCsrfToken`; Task 6, `SecurityConfigTest.postOutsideAuthNeedsCsrfToken`.
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

**Abweichung bei P11:** Spezifikation 6.4 und 6.5 nennen `KeycloakProxyIntegrationTest`. Geprüft wird P11 in der eigenen Klasse `KeycloakProxyDownIntegrationTest` daneben: Dafür braucht das Gateway eine Keycloak-Adresse, an der niemand zuhört, und damit einen eigenen Spring-Kontext. In derselben Klasse müsste ein Test den Mini-Server anhalten, und die anderen Tests hingen von der Reihenfolge ab.

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
├── Dockerfile                           # Maven- und JRE-Stufe (8), + Node-Stufe für web-ui/ (9)
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
- **Test zuerst.** Jede Aufgabe beginnt mit dem Test aus dem Plan. Das Rot sieht je nach Art anders aus:
  - *lokal:* Übersetzungsfehler, Vitest, Shell-Prüfungen und Spring-Tests ohne Container — Task 1 (`env-pruefen.sh`), 2, 5, 6 und 7 (ohne `LoginIntegrationTest`), 9 (Vitest).
  - *roter Probelauf in GitHub Actions* für alles mit Containern (Keycloak, RabbitMQ, ganzer Stack) — Task 1 (Abnahme), 3, 4, 6 und 7 (`LoginIntegrationTest`), 8, 9 (Image), 10.
- **Probelauf** für Task N, immer gleich:
  ```bash
  export MSYS_NO_PATHCONV=1
  git switch -c probe-tN                         # ab dem aktuellen Stand von baustein/web-gateway
  git add <Testdateien>                          # 1. nur der Test (und was er zum Laufen braucht)
  git commit -m "test: Probe Task N rot, <was fehlt>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  git push -u origin probe-tN                    # roter Lauf: gh run watch
  git add <Code>                                 # 2. der Code dazu
  git commit -m "test: Probe Task N grün" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  git push                                       # grüner Lauf: gh run watch
  git switch baustein/web-gateway                # 3. zurück, den Stand übernehmen
  git restore --source probe-tN --worktree -- <Test- und Code-Dateien>
  ```
  Im Plan steht bei jedem Probelauf zuerst «Roter Lauf: Link folgt», im Commit der Aufgabe dann der Link. Probe-Branches kommen nie auf `main`. Sie bleiben auf GitHub, damit die verlinkten Läufe ihren Commit behalten. Was nur der Probe dient (der Dummy-Dienst in Task 1, die Prüfjobs in Task 4, 8 und 9), wird nicht übernommen.
- **Lokal und im CI.** Lokal laufen `mvn -q -pl web-gateway test -Dtest=<Klassen ohne Container>` und in `web-ui/` `npm test`. Alle Tests aller Module laufen sicher im CI (`mvn -B clean test`, Job `maven`), lokal nur mit laufendem Docker.
- **Ein Commit pro Aufgabe** auf `baustein/web-gateway`, mit der Message aus dem Plan. Er enthält Test, Code und diesen Plan mit den Häkchen der erledigten Schritte, den Links der Probeläufe und jeder beim Bauen gefundenen Falle. **Ausnahme Task 8** mit zwei Commits: Der Healthcheck von RabbitMQ ist ein eigenes Thema (ein `fix:` für alle drei Dienste, die darauf warten), das erst mit dem Gateway nötig wird.
- **Erst committen, dann pushen.** Ein CI-Lauf braucht einen Commit. Der letzte Schritt jeder Aufgabe («Pushen und CI-Lauf prüfen») geschieht deshalb nach dem Commit. Sein Häkchen und der Link kommen in den Commit der nächsten Aufgabe, beim letzten Task in die Commit-Übersicht. Ist der Lauf rot, folgt ein Korrektur-Commit mit eigener Zeile in diesem Plan. Nichts wird umgeschrieben: kein `--amend`, kein `push --force`.
- **`main` nur per Fast-Forward** von `baustein/web-gateway` und nur mit grünem Lauf (docs/fahrplan.md 3): `git switch main`, `git merge --ff-only baustein/web-gateway`, `git push`. Tag `schritt-2` nach Task 10, Tag `schritt-3` nach Task 17 und der Abschlussprüfung.
- **Plan und `git log` bleiben deckungsgleich.** Jede Commit-Message steht im Plan, die Commit-Übersicht am Ende nennt zu jedem Commit seinen Eintrag und seine Läufe.

## Reihenfolge und warum

PLANUNG.md 6: «Auth zuerst, sonst wird es später nachträglich eingebaut und ist dann falsch.» Innerhalb des Logins von innen nach aussen: erst der Schutz der bestehenden Abnahme, dann Modul und Realm, dann Proxy, Anmeldung und Bearer, zuletzt Betrieb, Oberfläche und Abnahme. Danach derselbe Weg für den Chat.

| # | Aufgabe (Commit-Message) | Warum an dieser Stelle |
|---|---|---|
| 1 | `test: Abnahme von Bewertung 1 startet nur ihre vier Dienste` | Ab Task 4 verlangt `docker-compose.yml` ein Secret mit `:?`, ab Task 8 gibt es einen Port. Vorher muss `scripts/abnahme.sh` auf die vier Dienste von Bewertung 1 begrenzt sein, sonst wird der Job `abnahme` rot, ohne dass am `batch-writer` etwas falsch ist |
| 2 | `chore: Modul web-gateway anlegen` | Alles Weitere braucht ein übersetzbares Modul, auch der Test der Realm-Datei läuft darin. Die `COPY`-Zeilen gehören dazu, sonst bricht der Image-Bau der anderen Dienste im selben Moment |
| 3 | `feat: Realm chat als JSON-Import für Keycloak` | Der innerste Vertrag des Logins: Clients, Redirect-URIs, PKCE, Benutzer, Claims. Mit Keycloak allein testbar, ganz ohne Gateway. P2, P3, P4 und P7 entscheiden sich hier und können die Realm-Datei noch ändern |
| 4 | `chore: Keycloak in docker-compose` | Healthcheck, Import und Issuer im Stack prüfen, solange noch kein Gateway mitspielt. Ein Fehler dort soll nicht erst zusammen mit dem Gateway auffallen |
| 5 | `feat: Gateway reicht den Realm chat an Keycloak durch` | Ohne Proxy erreicht der Browser das Login-Formular nicht, es gibt nur einen Port. Mit einem Mini-Server testbar, ohne Login. Die Sperre für Admin-Konsole und Realm `master` (W8) entsteht hier |
| 6 | `feat: Anmeldung über Keycloak, /api/me nennt den Benutzer` | Braucht den Realm (3) und den Proxy (5): Das Login-Formular kommt durch das Gateway |
| 7 | `feat: Gateway prüft Bearer-Tokens` | Der zweite Weg hinein, für Desktop-Client und Abnahme. Setzt die Einstiegspunkte aus 6 voraus (401 statt Umleitung) |
| 8 | `fix: RabbitMQ-Healthcheck prüft die Ports statt nur den Prozess`, dann `chore: web-gateway mit dem einzigen Port in docker-compose` | Erst wenn das Gateway allein richtig arbeitet, lohnt der Betrieb im Stack. Mit ihm warten drei Dienste darauf, dass RabbitMQ «gesund» ist |
| 9 | `feat: Web-UI zeigt den angemeldeten Benutzer` | Braucht `/api/me` (6) und das Image (8). Schliesst Schritt 2 aus PLANUNG.md 6 ab: «React zeigt den Benutzernamen» |
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
- Test: lokale Prüfung von `env-pruefen.sh` mit Kopien von `.env.example` unter `target/env-test/`; Probelauf `probe-t1` mit einem Dummy-Dienst in `docker-compose.yml`, nur auf dem Probe-Branch

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

- [ ] **Schritt 5: Roter Probelauf für die Abnahme**

Branch `probe-t1` (Ablauf in «Arbeitsweise»). Commit 1 enthält nur einen fünften Dienst mit Port in `docker-compose.yml`, vor `networks:`, so wie später das Gateway. `scripts/abnahme.sh` ist noch im alten Stand.

```yaml
  # Nur auf probe-t1: ein fünfter Dienst mit Port, wie später das Gateway.
  dummy:
    image: busybox:1.36
    command: ["sleep", "3600"]
    ports:
      - "127.0.0.1:18080:80"
    networks:
      - chat-net
```

Erwartet: Job `abnahme` rot. S2 wartet 180 s auf genau vier Dienste und meldet `S2  FAIL gemessen: laufend: batch-writer chat-service dummy postgres rabbitmq | veröffentlichte Ports: 1 | erwartet: 4 Dienste laufen, 0 veröffentlichte Ports`.
Roter Lauf: Link folgt.

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

- [ ] **Schritt 7: Grüner Probelauf**

Commit 2 auf `probe-t1`: `scripts/abnahme.sh` und `scripts/env-pruefen.sh`. Der Dummy-Dienst bleibt.
Erwartet: Job `abnahme` grün, S2 bis S8 `PASS`. S2 meldet `laufend: batch-writer chat-service postgres rabbitmq | veröffentlichte Ports: 0`, der Dummy-Dienst startet nie.
Grüner Lauf: Link folgt.

- [ ] **Schritt 8: Nachtrag und README**

Zurück auf `baustein/web-gateway`:

```bash
git switch baustein/web-gateway
git restore --source probe-t1 --worktree -- scripts/abnahme.sh scripts/env-pruefen.sh
```

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
git add scripts/abnahme.sh scripts/env-pruefen.sh docs/spec-batch-writer.md README.md docs/plan-web-gateway.md
git commit -m "test: Abnahme von Bewertung 1 startet nur ihre vier Dienste" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Erwartet: `git show --stat HEAD` nennt `docker-compose.yml` nicht; der Dummy-Dienst bleibt auf `probe-t1`.

- [ ] **Schritt 10: Pushen und CI-Lauf prüfen**

Ausführen: `git push`, dann `gh run watch`
Erwartet: `maven`, `images` und `abnahme` grün; `abnahme` zeigt `== S2: frischer Start, .env aus .env.example, docker compose up -d --build rabbitmq chat-service postgres batch-writer`.

---

## Task 2: Modul web-gateway anlegen

**Warum an dieser Stelle:** Alles Weitere braucht ein übersetzbares Modul, auch der Test der Realm-Datei (Task 3) läuft darin. Die `COPY`-Zeilen in den Dockerfiles von `chat-service` und `batch-writer` gehören in denselben Commit: Sobald das Eltern-POM das neue Modul nennt, bricht der Image-Bau der beiden anderen Dienste. Das Dockerfile des Gateways selbst entsteht erst in Task 8. Vorher baut es kein Lauf (der Job `images` baut nur, was in `docker-compose.yml` steht), es wäre also nur behauptet.

**Dateien:**
- Ändern: `pom.xml` (Modulliste, Stückliste Spring Cloud)
- Ändern: `chat-service/Dockerfile`, `batch-writer/Dockerfile` (je eine `COPY`-Zeile)
- Anlegen: `web-gateway/pom.xml`
- Anlegen: `web-gateway/src/main/java/ch/benedict/m321/webgateway/WebGatewayApplication.java`
- Anlegen: `web-gateway/src/main/resources/application.yml`
- Anlegen: `web-gateway/src/test/resources/gateway-test.properties`
- Test: `web-gateway/src/test/java/ch/benedict/m321/webgateway/WebGatewayApplicationTest.java`; Probelauf `probe-t2` für die `COPY`-Zeilen

**Schnittstellen:**
- Verbraucht: Eltern-POM
- Stellt bereit: Paketwurzel `ch.benedict.m321.webgateway`, Artefakt `ch.benedict.m321:web-gateway:0.1.0-SNAPSHOT`; ein Spring-Kontext, der ohne Keycloak, RabbitMQ und `chat-service` startet; `gateway-test.properties` mit den vier Variablen aus Spezifikation 4.1, alle auf Adressen ohne Dienst. Jede Spring-Testklasse bindet sie mit `@TestPropertySource(locations = "classpath:gateway-test.properties")` ein und überschreibt nur, was sie selbst braucht.

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
Erwartet: `WebGatewayApplicationTest` grün. Im Protokoll steht `Using generated security password`: Ohne eigene Regeln sichert Spring Security vorerst alles mit einem Formular-Login. Das ersetzt Task 5.

- [ ] **Schritt 8: Roter Probelauf für die `COPY`-Zeilen**

Branch `probe-t2`, Commit 1: Eltern-POM, `web-gateway/pom.xml`, `web-gateway/src`, aber noch **ohne** die `COPY`-Zeilen.
Erwartet: Job `images` rot. Der Bau von `chat-service` und `batch-writer` bricht ab mit `Child module /build/web-gateway of /build/pom.xml does not exist`; `maven` grün.
Roter Lauf: Link folgt.

- [ ] **Schritt 9: `COPY`-Zeilen ergänzen**

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

Commit 2 auf `probe-t2` mit beiden Dockerfiles.
Erwartet: `maven` und `images` grün.
Grüner Lauf: Link folgt.

> **Falle:** Am `chat-service` und am `batch-writer` ändert sich nichts, und trotzdem baut ihr Image
> nicht mehr: `mvn -pl … -am` liest das Eltern-POM, und das nennt jetzt drei Module. Genau diesen
> Fehler zeigt der rote Lauf.

- [ ] **Schritt 10: Committen**

```bash
git switch baustein/web-gateway
git restore --source probe-t2 --worktree -- pom.xml web-gateway chat-service/Dockerfile batch-writer/Dockerfile
git add pom.xml web-gateway chat-service/Dockerfile batch-writer/Dockerfile docs/plan-web-gateway.md
git commit -m "chore: Modul web-gateway anlegen" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Schritt 11: Pushen und CI-Lauf prüfen**

Ausführen: `git push`, dann `gh run watch`
Erwartet: `maven` (jetzt mit `WebGatewayApplicationTest`), `images` und `abnahme` grün.

---

