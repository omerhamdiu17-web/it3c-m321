# M321 — Chat-App (Klasse IT3c)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Bauen, testen, starten

```bash
mvn clean test                   # alle Tests, RabbitMQ und PostgreSQL kommen per Testcontainers
docker compose up -d --build     # RabbitMQ, chat-service, PostgreSQL und batch-writer im Netz chat-net
docker compose down -v           # alles stoppen UND die Datenbank löschen (frischer Start)
bash scripts/abnahme.sh          # Bewertung 1: Szenarien S2 bis S8 nachstellen (beginnt mit "down -v"!)
bash scripts/abnahme-live.sh     # Bewertung 2: Live-Zustellung S2 bis S8 (beginnt mit "down -v"!)
```

Kein Dienst veröffentlicht einen Port auf den Host. Der einzige offene Port des Gesamtsystems
gehört später dem Gateway. Gesendet und gemessen wird deshalb von innen, zum Beispiel:

```bash
docker run --rm --network chat-net curlimages/curl -s -X POST http://chat-service:8080/messages \
  -H 'Content-Type: application/json' \
  -d '{"roomId":"3f2b1c4e-0000-0000-0000-000000000001","senderId":"anna","senderName":"Anna Muster","content":"Hallo"}'
docker compose exec postgres psql -U chat -P pager=off -c "SELECT sender_name, content, sent_at FROM message ORDER BY sent_at DESC LIMIT 5"
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

Einen Raum live mitlesen (Server-Sent Events). Jede Nachricht kommt als `id:` und `data:`; die
erste Zeile `:verbunden` ist nur ein Kommentar, damit der Client sofort weiss, dass er verbunden ist:

```bash
docker run --rm --network chat-net curlimages/curl -sN \
  "http://chat-service:8080/stream?roomId=3f2b1c4e-0000-0000-0000-000000000001"
```

Nach einem Abbruch holt `-H "Last-Event-ID: <letzte id>"` genau die verpassten Nachrichten nach.
Mehrere Instanzen: `docker compose up -d --scale chat-service=2`. Jede hängt eine eigene Queue
`chat.delivery.<zufällig>` an den Fanout (`rabbitmqctl list_bindings source_name destination_name`).

Das Schema der Datenbank (`postgres/init/01-schema.sql`) läuft nur beim ersten Start mit leerem
Volume. Nach einer Änderung daran: `docker compose down -v`.

Jeder Push läuft in GitHub Actions durch `mvn clean test`, den Bau aller Images und die Abnahme
(`.github/workflows/build.yml`).

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange. Live-Zustellung: holt jede Nachricht über eine eigene Queue vom Fanout `chat.delivery` und schiebt sie per Server-Sent Events unter `GET /stream?roomId=…` an die Clients des Raums, mit Nachholen über `Last-Event-ID` | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank: liest `chat.persist` in Stapeln bis 500, eine Transaktion pro Stapel, ACK nach dem COMMIT | vorhanden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf in der Tabelle `message` | vorhanden |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | Spring Boot 3, Java 21 | Einziger nach aussen offener Port: Login über Keycloak, REST, WebSocket, liefert die Web-UI aus | folgt |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/fahrplan.md`](docs/fahrplan.md) — was nach Bewertung 1 noch gebaut wird, in welcher
  Reihenfolge, wie gearbeitet wird und welche Entscheide schon gefallen sind.
- [`docs/checkliste.md`](docs/checkliste.md) — jede Vorgabe des Lehrers und des Projekts mit
  ihrem Stand und dem Nachweis dafür.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation des `batch-writer`:
  Vertrag, Verhalten in jedem Fehlerfall, Datenmodell, Abnahmekriterien.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzungsplan des `batch-writer`,
  Schritt für Schritt mit Test.
- [`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md) — Spezifikation der Live-Zustellung
  (Bewertung 2): Vertrag, Verhalten, Queues, Abnahmekriterien.
- [`docs/plan-live-zustellung.md`](docs/plan-live-zustellung.md) — Umsetzungsplan der Live-Zustellung,
  Schritt für Schritt mit KI-Auftrag, Test und Prüfnotiz.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
