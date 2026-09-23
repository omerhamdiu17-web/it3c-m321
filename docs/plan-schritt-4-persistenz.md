# Schritt 4: Persistenz — Implementation Plan

> **Für agentische Mitarbeiter:** Umsetzung Aufgabe für Aufgabe, jede mit Test zuerst. Die Kästchen (`- [ ]`) zeigen den Stand.

**Ziel:** Jede Nachricht landet in der Datenbank, ohne dass der Zustellweg darauf wartet. Wer einen Raum öffnet, sieht die letzten 50 Nachrichten. Es gibt mehrere Räume, und eine Nachricht geht nur an die Browser, die im betroffenen Raum sind.

**Architektur:** Ein neuer Dienst `batch-writer` ist der **einzige Schreiber** in die Datenbank. Er holt Nachrichten stapelweise aus `chat.persist` und schreibt jeden Stapel mit einem einzigen Bulk-INSERT. Gelesen wird im `chat-service` (PLANUNG.md, Abschnitt 3.1: `CS → SELECT Historie`). Das Gateway reicht die Lese-Anfragen nur durch.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP (Batch-Listener), Spring `JdbcTemplate`, PostgreSQL 16, JUnit 5, Testcontainers.

**Spec:** [`../PLANUNG.md`](../PLANUNG.md) — Abschnitt 3.4 (Nachrichtenfluss), 3.6 (Batch-Writer und At-least-once), 3.7 (Datenmodell) und Schritt 4 der Umsetzungsreihenfolge.

## Globale Vorgaben

Es gelten alle Vorgaben aus [`plan-chat-service.md`](plan-chat-service.md) und [`../CLAUDE.md`](../CLAUDE.md):
Code auf Englisch, alles andere auf Deutsch, keine verschachtelten Aufrufe, `for` statt Stream, Lombok nur für Logger und Konstruktor, `record` für Daten, **kein neuer `ports:`-Eintrag**, keine Geheimnisse im Repository.

Zusätzlich für diesen Schritt:

- **Der Datenbank-Test benutzt die echten Init-Skripte.** Der Postgres-Testcontainer bekommt den Ordner `postgres/init` eingespielt, genau wie in `docker-compose.yml`. So prüft jeder Test auch das Schema, und es gibt keine zweite Kopie davon.
- **`JdbcTemplate`, kein JPA** (PLANUNG.md, Abschnitt 2.1). `batchUpdate` ist genau das, was wir zeigen wollen.

---

## Abgrenzung

| Bewusst **nicht** in diesem Schritt | Warum |
|---|---|
| Räume anlegen oder löschen über die Oberfläche | Die drei Räume kommen fest aus `postgres/init/03-rooms.sql`. Für M321 ist die Verteilung spannend, nicht die Raumverwaltung |
| Mitgliedschaften (`room_member`) | Jeder angemeldete Benutzer darf jeden Raum sehen. Die Tabelle bleibt für einen späteren Ausbau |
| Blättern im Verlauf ("ältere laden") | Die einzige Abfrage ist "die letzten 50" (PLANUNG.md, Abschnitt 3.7) |
| Aufräumen alter Nachrichten | Offener Punkt 2 in PLANUNG.md, nicht Teil von Schritt 4 |
| Zählen der Zustellversuche (Quorum-Queue mit `x-delivery-limit`) | Siehe Entscheidung "Fehlerbehandlung" unten |

---

## Entscheidungen in diesem Schritt

### Stapel: 500 Stück oder 200 ms Ruhe

Spring AMQP kann Nachrichten selbst zu Stapeln sammeln (`consumerBatchEnabled`). Wir brauchen dafür keinen eigenen Puffer und keinen Timer, nur vier Einstellungen in `RabbitConfig`:

| Einstellung | Wert | Bedeutung |
|---|---|---|
| `batchSize` | 500 | So viele Nachrichten höchstens pro Stapel |
| `receiveTimeout` | 200 ms | Kommt so lange nichts Neues, geht der angefangene Stapel trotzdem los |
| `prefetchCount` | 500 | So viele unbestätigte Nachrichten schickt RabbitMQ auf Vorrat — genau ein Stapel |
| `acknowledgeMode` | `MANUAL` | Wir bestätigen selbst, und zwar erst nach dem COMMIT |

### Fehlerbehandlung: zwei Arten von Fehlern

Die Planung (Abschnitt 3.5) sieht "Dead Letter nach 3 fehlgeschlagenen Versuchen" vor. Eine klassische RabbitMQ-Queue zählt aber keine Versuche; das könnte erst eine Quorum-Queue. Wir unterscheiden stattdessen die **Art** des Fehlers — das ist ehrlicher als eine Zahl:

| Fehler | Beispiel | Was der batch-writer tut |
|---|---|---|
| **Die Nachricht ist kaputt** | kein gültiges JSON, Raum existiert nicht, Pflichtfeld fehlt | Sofort `reject` ohne Wiederholung → landet in `chat.dlq`. Ein zweiter Versuch würde genauso scheitern |
| **Die Datenbank ist weg** | Verbindung abgelehnt, Timeout | `nack` mit Wiederholung → RabbitMQ liefert den ganzen Stapel erneut. Nichts geht verloren |

Scheitert ein Stapel an einer einzelnen kaputten Nachricht, wird er **einzeln** wiederholt: die guten Nachrichten kommen in die Datenbank, nur die kaputte geht in die Dead-Letter-Queue.

### Räume und Zustellung nach Raum

Bisher ging jede Nachricht an alle Browser. Jetzt meldet der Browser beim Verbindungsaufbau, welchen Raum er offen hat (`/ws/chat?roomId=…`). Das Gateway merkt sich das und schickt eine Nachricht nur an die Verbindungen dieses Raums. Wechselt der Benutzer den Raum, baut die Oberfläche eine neue Verbindung auf.

Das wird spätestens in Schritt 5 wichtig: der `load-generator` schreibt in den Raum "Lasttest", und wer in der Lobby sitzt, soll davon nichts merken.

---

## Dateistruktur

```
postgres/init/03-rooms.sql                   # die drei festen Räume
batch-writer/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/ch/benedict/m321/batchwriter/
    │   ├── BatchWriterApplication.java
    │   ├── config/
    │   │   ├── QueueNames.java               # eigene Kopie der Namen
    │   │   └── RabbitConfig.java             # Queue, DLQ, Batch-Listener-Einstellungen
    │   ├── dto/ChatMessage.java              # eigene Kopie des Vertrags
    │   ├── repository/MessageRepository.java # Bulk-INSERT mit ON CONFLICT DO NOTHING
    │   └── service/
    │       ├── ReceivedMessage.java          # Nachricht + Lieferschein (delivery tag)
    │       └── MessageBatchListener.java     # Stapel lesen, schreiben, bestätigen
    ├── main/resources/application.yml
    └── test/java/ch/benedict/m321/batchwriter/
        ├── BatchWriterApplicationTest.java
        ├── ChatDatabase.java                 # Postgres-Testcontainer mit den echten Init-Skripten
        ├── repository/MessageRepositoryIntegrationTest.java
        └── service/MessageBatchListenerIntegrationTest.java
chat-service/  (neu dazu)
    ├── controller/RoomController.java        # GET /rooms, GET /rooms/{id}/messages
    ├── service/RoomService.java              # die Regel "die letzten 50"
    ├── repository/RoomRepository.java        # SELECT auf room und message
    └── dto/Room.java
web-gateway/  (neu dazu bzw. geändert)
    ├── controller/RoomController.java        # GET /api/rooms, GET /api/rooms/{id}/messages
    ├── dto/Room.java
    ├── service/ChatServiceClient.java        # + loadRooms, loadHistory
    ├── service/ChatSessionRegistry.java      # merkt sich den Raum pro Verbindung
    ├── service/DeliveryListener.java         # sendToRoom statt sendToAll
    └── websocket/ChatSocketHandler.java      # liest roomId beim Verbindungsaufbau
web-ui/src/
    ├── App.tsx                               # Raumliste
    └── Chat.tsx                              # Verlauf laden, Verbindung pro Raum
```

---

## Task 1: Feste Räume in der Datenbank

- [ ] `postgres/init/03-rooms.sql` legt Lobby (`…0001`), M321 (`…0002`) und Lasttest (`…0003`) an. Die Lobby-ID ist dieselbe, die die Oberfläche seit Schritt 3 benutzt.
- [ ] README: Hinweis, dass nach dem Update einmal `docker compose down -v` nötig ist.

**Warum zuerst:** `message.room_id` ist ein Fremdschlüssel auf `room`. Ohne Raum scheitert jeder INSERT.

## Task 2: Modul batch-writer und Anwendungsstart

- [ ] Test `BatchWriterApplicationTest.contextLoads` — Kontext fährt ohne Broker und ohne Datenbank hoch (Listener aus, Verbindung zur DB erst bei Bedarf).
- [ ] `batch-writer/pom.xml`, Eintrag im Eltern-POM, `BatchWriterApplication`, `application.yml` (kein Webserver: `web-application-type: none`).

## Task 3: Bulk-INSERT

- [ ] Test `MessageRepositoryIntegrationTest`:
  - `storesWholeBatch` — drei Nachrichten, danach drei Zeilen.
  - `ignoresDuplicate` — dieselbe Nachricht zweimal, danach eine Zeile (At-least-once, PLANUNG.md 3.6).
  - `rejectsUnknownRoom` — Raum existiert nicht → `DataIntegrityViolationException`.
- [ ] `MessageRepository.insertBatch` mit `batchUpdate` und `ON CONFLICT (id) DO NOTHING`, in einer Transaktion. `insertOne` für den Einzelversuch.
- [ ] JDBC-URL mit `reWriteBatchedInserts=true`: der Treiber macht aus 500 Einzel-INSERTs einen einzigen mehrzeiligen INSERT.

## Task 4: Batch-Listener mit Bestätigung nach dem COMMIT

- [ ] Test `MessageBatchListenerIntegrationTest` gegen echtes RabbitMQ **und** echtes Postgres:
  - `storesMessagesFromQueue` — Nachrichten in `chat.persist` → Zeilen in `message`.
  - `sendsBrokenJsonToDeadLetterQueue` — kaputtes JSON → `chat.dlq`, die Queue läuft weiter.
  - `storesGoodMessagesAndRejectsUnknownRoom` — gemischter Stapel: gute Nachricht gespeichert, schlechte in `chat.dlq`.
- [ ] `RabbitConfig` mit den vier Stapel-Einstellungen, `persistQueue` mit **denselben** Argumenten wie im chat-service (sonst lehnt RabbitMQ die zweite Anmeldung ab).
- [ ] `MessageBatchListener`: lesen → schreiben → `basicAck(letzterTag, multiple=true)`; bei Datenbankfehler `basicNack(…, requeue=true)` nach einer Sekunde Pause.

## Task 5: batch-writer im docker-compose

- [ ] `batch-writer/Dockerfile` (wie beim chat-service), Dienst in `docker-compose.yml` **ohne** `ports:`.
- [ ] Alle Dockerfiles kopieren das neue Modul-POM mit (Maven liest alle Module aus dem Eltern-POM).

## Task 6: Verlauf und Raumliste im chat-service

- [ ] Test `RoomControllerIntegrationTest` (Postgres-Testcontainer mit den Init-Skripten):
  - `listsSeededRooms` — `GET /rooms` liefert die drei Räume.
  - `returnsLatestMessagesOldestFirst` — `GET /rooms/{id}/messages` liefert höchstens 50, die älteste zuerst.
- [ ] `RoomRepository`, `RoomService` (Regel: 50), `RoomController`, `Room`.
- [ ] Datenbank weg → 503, wie beim Broker.
- [ ] `docker-compose.yml`: chat-service bekommt Datenbank-Zugang und wartet auf Postgres.

## Task 7: Gateway reicht Verlauf und Räume durch

- [ ] Test `ChatServiceClientTest` (+ `loadsRooms`, `loadsHistory`), `RoomControllerTest` im Gateway mit simuliertem Login.
- [ ] `GET /api/rooms` und `GET /api/rooms/{id}/messages`.

## Task 8: Zustellung nur an den richtigen Raum

- [ ] Test `ChatSessionRegistryTest` — eine Nachricht für Raum A kommt nur bei Verbindungen in Raum A an.
- [ ] `ChatSocketHandlerTest` — Verbindung ohne gültige `roomId` wird geschlossen.
- [ ] `DeliveryListenerIntegrationTest` prüft jetzt `sendToRoom`.

## Task 9: Oberfläche mit Räumen und Verlauf

- [ ] Raumliste aus `/api/rooms`, Klick wechselt den Raum.
- [ ] Beim Öffnen eines Raums: zuerst WebSocket verbinden, **dann** Verlauf laden. So geht keine Nachricht verloren, die zwischen beiden Schritten geschrieben wird; doppelte fallen über die ID heraus.

## Task 10: Dokumentation

- [ ] README: Stand-Tabelle, Weg einer Nachricht mit Datenbank, `docker compose down -v`.
- [ ] PLANUNG.md, Abschnitt 8: Entscheidung zur Fehlerbehandlung festhalten.

---

## Prüfen von Hand

```bash
docker compose down -v            # einmalig: neue Init-Skripte (Räume) einspielen
docker compose up --build
```

1. Als `alice` anmelden, in der Lobby etwas schreiben.
2. Seite neu laden: die Nachricht steht noch da (kommt jetzt aus der Datenbank).
3. In einem privaten Fenster als `bob` in den Raum "M321" wechseln: Lobby-Nachrichten von alice erscheinen dort **nicht**.
4. Direkt in der Datenbank nachsehen:
   ```bash
   docker compose exec postgres psql -U chat -d chat -c "SELECT sender_name, content, sent_at FROM message ORDER BY sent_at DESC LIMIT 5;"
   ```
