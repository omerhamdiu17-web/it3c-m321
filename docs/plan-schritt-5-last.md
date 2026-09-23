# Schritt 5: Last — Implementation Plan

> **Für agentische Mitarbeiter:** Umsetzung Aufgabe für Aufgabe, jede mit Test zuerst. Die Kästchen (`- [x]`) zeigen den Stand.

**Ziel:** Es gibt etwas zu messen. Ein eigener Container erzeugt Last (Ziel: 100'000 Nachrichten pro Minute), und die Web-Oberfläche zeigt die Tiefe der Queue `chat.persist` als Balken — so sieht die Klasse, ob der `batch-writer` hinterherkommt.

**Architektur:** Der `load-generator` ist ein eigener Spring-Boot-Dienst im internen Netz. Er ruft den `chat-service` **direkt** per REST auf (PLANUNG.md, Abschnitt 3.1: `LG → REST: Last → CS`) und nicht über das Gateway — wir wollen die Architektur messen, nicht den einen offenen Port. Die Queue-Tiefe holt das Gateway intern über die Management-API von RabbitMQ; die Management-Oberfläche selbst bleibt geschlossen.

**Tech-Stack:** Java 21 (virtuelle Threads), Spring Boot 3.5.16, `RestClient`, RabbitMQ-Management-API, React.

**Spec:** [`../PLANUNG.md`](../PLANUNG.md) — Abschnitt 4 (Mengengerüst und Skalierung), offene Punkte 6 (Rollen), 7 (100k/min auf einem Laptop?) und 8 (Lastverteilung auf `chat-service`).

## Globale Vorgaben

Wie in [`plan-schritt-4-persistenz.md`](plan-schritt-4-persistenz.md). Zusätzlich:

- **Der `load-generator` läuft nur auf Wunsch.** Er steht im docker-compose-Profil `load` und startet bei einem normalen `docker compose up` nicht mit.
- **Die Management-API bekommt keinen Port.** Das Gateway erreicht sie intern unter `http://rabbitmq:15672`.

---

## Abgrenzung

| Bewusst **nicht** in diesem Schritt | Warum |
|---|---|
| Last über das Gateway und WebSocket | Dann misst man das Gateway (offener Punkt 1: es skaliert nicht). Ziel ist die Queue und der batch-writer |
| Eigenes Monitoring (Prometheus, Grafana) | Zwei Container mehr, die man erklären müsste. Der Balken in der Oberfläche reicht für den Unterricht |
| Das Skalieren selbst und die Messreihe | Schritt 6 |

---

## Entscheidungen in diesem Schritt

### Wie der load-generator Last erzeugt

Jede Sekunde schickt er `messagesPerMinute / 60` Anfragen los, also bei 100'000/min rund 1'667 pro Sekunde. Jede Anfrage läuft in einem **virtuellen Thread** (Java 21): die kosten fast nichts, man darf tausende davon haben. Damit der Generator nicht selbst zum Problem wird, sind höchstens 500 Anfragen gleichzeitig unterwegs (Semaphore). Kommt der `chat-service` nicht nach, sinkt die tatsächliche Rate — und genau das zeigt das Protokoll: **Soll** und **Ist** stehen jede Sekunde nebeneinander.

Die Nachrichten gehen in den Raum **Lasttest**. Wer in der Lobby sitzt, merkt davon nichts (Zustellung nach Raum, Schritt 4).

### Welche chat-service-Instanz hat geantwortet?

Offener Punkt 8 sagt: "Muss gemessen werden, sonst glaubt man an eine Verteilung, die nicht stattfindet." Deshalb schreibt der `chat-service` seinen Container-Namen in den Antwort-Header `X-Chat-Service-Instance`, und der `load-generator` zählt am Ende, wie viele Antworten von welcher Instanz kamen.

### Wer sieht die Queue-Tiefe?

Offener Punkt 6 wird hier entschieden, so wie vorgeschlagen und wie es im Realm schon angelegt ist: **nur die Rolle `admin`**. Keycloak schreibt die Realm-Rollen dafür als Claim `roles` ins Token (Protocol Mapper im Realm). Das Gateway prüft die Rolle selbst und antwortet sonst mit 403.

---

## Dateistruktur

```
keycloak/realm-chat.json                        # + Protocol Mapper "roles"
load-generator/
├── pom.xml
├── Dockerfile
└── src/main/java/ch/benedict/m321/loadgenerator/
    ├── LoadGeneratorApplication.java
    ├── LoadProperties.java                     # Rate, Dauer, Raum, Adresse
    ├── dto/SendMessageRequest.java             # eigene Kopie des Vertrags
    ├── service/LoadStatistics.java             # zählt Erfolge, Fehler, Instanzen
    ├── service/LoadStarter.java                # startet den Versuch nach dem Hochfahren
    └── service/LoadRunner.java                 # die Schleife: jede Sekunde N Anfragen
chat-service/
    └── controller/MessageController.java       # + Header X-Chat-Service-Instance
web-gateway/
    ├── service/LoggedInUser.java               # liest Kennung, Namen und Rolle aus den Claims
    ├── dto/QueueStats.java
    ├── dto/CurrentUser.java                    # + admin
    ├── service/QueueStatsClient.java           # GET /api/queues/%2F/chat.persist
    ├── controller/QueueStatsController.java    # GET /api/admin/queue, nur Rolle admin
    └── controller/CurrentUserController.java   # meldet, ob admin
web-ui/src/QueueDepth.tsx                       # der Balken
```

---

## Task 1: Rollen ins Token

- [x] Protocol Mapper `realm-roles` am Client `web-gateway`: Realm-Rollen als Claim `roles` in ID-Token, Access-Token und Userinfo.
- [x] Test `CurrentUserControllerTest.reportsAdminRole` — mit Claim `roles: [admin]` meldet `/api/me` `admin: true`, ohne `false`.

## Task 2: Queue-Tiefe im Gateway

- [x] Test `QueueStatsClientTest` (MockRestServiceServer) — liest `messages`, `consumers` und die Raten aus dem JSON der Management-API.
- [x] Test `QueueStatsControllerTest` — `admin` bekommt 200, `alice` bekommt 403.
- [x] `QueueStatsClient`, `QueueStatsController`, `QueueStats`, Konfiguration in `application.yml` und `docker-compose.yml`.

## Task 3: Balken in der Oberfläche

- [x] `QueueDepth.tsx` fragt jede Sekunde `/api/admin/queue` und zeigt Tiefe, Anzahl batch-writer und die Raten rein/raus.
- [x] Nur sichtbar, wenn `/api/me` `admin: true` meldet.

## Task 4: Instanz im Antwort-Header des chat-service

- [x] Test `MessageControllerIntegrationTest` prüft den Header `X-Chat-Service-Instance`.
- [x] Der Name kommt aus der Umgebungsvariable `HOSTNAME` (setzt Docker für jeden Container).

## Task 5: load-generator

- [x] Test `LoadStatisticsTest` — zählt Erfolge, Fehler und Antworten pro Instanz richtig.
- [x] Test `LoadRunnerTest` — gegen einen Test-Webserver: bei Rate 600/min und 2 Sekunden Dauer kommen 20 Anfragen im Raum Lasttest an.
- [x] `LoadRunner` mit virtuellen Threads und Semaphore, Protokoll jede Sekunde (Soll/Ist), Zusammenfassung am Ende.
- [x] Dockerfile, Dienst im Profil `load` in `docker-compose.yml`, Rate und Dauer über `.env`.

## Task 6: Dokumentation

- [x] README: Last erzeugen, Balken ansehen, Rolle admin.
- [x] PLANUNG.md, Abschnitt 8: Entscheidung zu offenem Punkt 6.

---

## Prüfen von Hand

```bash
docker compose up -d --build
# als admin anmelden (Passwort admin): der Balken erscheint unter dem Chat
docker compose --profile load up load-generator
```

Der `load-generator` schreibt jede Sekunde eine Zeile wie

```
second 12: target 1666, accepted 1666, failed 0, in flight 3
```

und am Ende, welche `chat-service`-Instanz wie viele Anfragen beantwortet hat.
