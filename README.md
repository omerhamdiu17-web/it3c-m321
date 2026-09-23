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
mvn test                         # alle Tests, RabbitMQ, Postgres und Keycloak kommen per Testcontainers
docker compose up --build        # alle Container im Netz chat-net, Web-UI wird im Build gebaut
```

> **Nach einem Update aus Schritt 4 oder 5 einmal `docker compose down -v`.** Die festen Räume
> (`postgres/init/03-rooms.sql`) und die Rollen im Token (Protocol Mapper in
> `keycloak/realm-chat.json`) werden nur beim **ersten** Start mit leerem Volume eingespielt.
> `down -v` löscht die Datenbank-Dateien, beim nächsten `up` kommt alles frisch.

Danach im Browser `http://localhost:8080` öffnen. Das Gateway leitet zur Anmeldemaske von Keycloak
weiter (Testbenutzer siehe unten); nach dem Login zeigt die Seite den angemeldeten Benutzer, einen
Link zum Abmelden, die Räume und den Chat. Beim ersten Start braucht Keycloak rund 30 Sekunden,
solange antwortet `/auth` mit einem Fehler.

**Chat ausprobieren:** in einem normalen Fenster als `alice` anmelden, in einem privaten Fenster
(oder einem zweiten Browser) als `bob`. Was alice in einem Raum schreibt, sieht bob, wenn er
denselben Raum offen hat — und nur dann. Wer die Seite neu lädt oder einen Raum öffnet, sieht die
letzten 50 Nachrichten aus der Datenbank.

**Der Weg einer Nachricht:**

```
Browser ──WebSocket /ws/chat?roomId=…──► web-gateway ──REST POST /messages──► chat-service
                                                                                  │
                                         ┌──── Fanout chat.delivery ◄─────────────┤
                                         ▼                                        ▼
   alle Browser im Raum ◄──WebSocket── web-gateway                    Queue chat.persist
                                                                                  │
                                                                                  ▼
                                   Postgres ◄── ein INSERT pro Stapel ── batch-writer
```

Zustellung und Speicherung sind entkoppelt: die Nachricht ist beim Empfänger, bevor sie in der
Datenbank steht. Der `batch-writer` sammelt bis zu 500 Nachrichten (oder wartet 200 ms) und schreibt
sie mit einem einzigen INSERT; bestätigt wird erst nach dem COMMIT (At-least-once, Duplikate
verwirft die Datenbank über die ID).

Nachschauen, was in der Datenbank steht:

```bash
docker compose exec postgres psql -U postgres -d chat -c "SELECT sender_name, content, sent_at FROM message ORDER BY sent_at DESC LIMIT 5;"
```

**Der einzige offene Port ist 8080 am `web-gateway`.** Keycloak selbst hat keinen Port; das
Gateway reicht `/auth/**` intern weiter. Keycloak hat deshalb zwei Adressen: der Browser sieht
`http://localhost:8080/auth`, das Gateway spricht intern `http://keycloak:8080/auth` an. Der
Aussteller (`issuer`) in jedem Token trägt die äussere Adresse, prüfbar mit:

```bash
curl -s http://localhost:8080/auth/realms/chat/.well-known/openid-configuration
```

### Testbenutzer im Realm `chat`

| Benutzer | Passwort | Rollen |
|---|---|---|
| `alice` | `alice` | user |
| `bob` | `bob` | user |
| `admin` | `admin` | user, admin |

Wer als `admin` angemeldet ist, sieht unter dem Chat zusätzlich einen **Balken mit der Tiefe der
Queue `chat.persist`**: wie viele Nachrichten noch auf den `batch-writer` warten, wie viele
`batch-writer` angeschlossen sind und wie viele Nachrichten pro Sekunde herein- und in die
Datenbank hinausgehen. Die Rolle prüft das Gateway selbst (`/api/admin/queue`, sonst 403). Keycloak
schreibt die Realm-Rollen dafür als Claim `roles` ins Token.

Der Realm (`keycloak/realm-chat.json`) wird nur beim **ersten** Start importiert, genauso wie die
Datenbank-Skripte in `postgres/init/`. Nach einer Änderung daran: `docker compose down -v`, das
löscht die Datenbank-Dateien und löst beim nächsten Start Import und Skripte erneut aus.

Der Client `web-gateway` im Realm hat ein festes Beispiel-Secret. Das ist im Unterricht in Ordnung,
weil der Realm-Import keine Werte aus `.env` lesen kann; in einem echten System gehörte es dort hin.

## Last erzeugen und skalieren (Schritte 5 und 6)

Der `load-generator` schickt Nachrichten **direkt** an den `chat-service` (nicht über das Gateway)
in den Raum «Lasttest». Er läuft nur mit dem Profil `load` und beendet sich nach der eingestellten
Dauer von selbst:

```bash
docker compose up -d --build                      # Grundsystem
docker compose --profile load up load-generator   # Last: 100'000/min für 60 s (Werte in .env)
```

Jede Sekunde schreibt er Soll und Ist ins Protokoll (`second 12: target 1666, accepted 1666,
failed 0, in flight 3`), am Ende die tatsächliche Rate und welche `chat-service`-Instanz wie viele
Anfragen beantwortet hat.

Während die Last läuft, als `admin` den Balken beobachten und live dazuschalten:

```bash
docker compose up -d --scale batch-writer=3       # Competing Consumers an chat.persist
docker compose up -d --scale chat-service=3       # Verteilung über Docker-DNS (offener Punkt 8)
```

Die ganze Messreihe macht ein Skript: Normalbetrieb und Stauabbau mit 1, 2 und 3 `batch-writer`,
auf Wunsch mit mehreren `chat-service`. Die Ergebnisse landen in `messreihe/`:

```bash
scripts/messreihe.sh
```

Die Ergebnisse unserer Messung und was man daraus lernt stehen in
[`docs/messreihe.md`](docs/messreihe.md). Kurz: alle Nachrichten kommen ohne Verlust an, aber auf
einer Maschine mit 4 Kernen mit 83'000–93'000 statt 100'000 pro Minute; der Engpass ist der
Sendeweg, nicht die Datenbank. Ein einziger batch-writer schreibt ~17'500 Nachrichten pro Sekunde.
`--scale chat-service=3` verteilt die Last nicht (offener Punkt 8).

## Automatische Prüfung (GitHub Actions)

Bei jedem Push baut GitHub das Projekt und prüft es ([`.github/workflows/build.yml`](.github/workflows/build.yml)):

- `mvn test` mit allen Testcontainers-Tests,
- Typprüfung und Build der Web-UI,
- Bau aller Container-Images,
- ein **Rauchtest gegen das ganze System**: `docker compose up`, echter Login von alice, bob und
  admin bei Keycloak, Nachricht per WebSocket, Zustellung nach Raum, Speicherung im Verlauf,
  Rollenprüfung und ein kurzer Lastlauf ([`scripts/smoke-test.py`](scripts/smoke-test.py)).

Die Messreihe läuft als eigener Workflow ([`.github/workflows/messreihe.yml`](.github/workflows/messreihe.yml)),
von Hand gestartet im Reiter «Actions».

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange; liest Räume und Verlauf (`GET /rooms`, `GET /rooms/{id}/messages`) | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank: Stapel aus `chat.persist`, ein Bulk-INSERT, ACK nach dem COMMIT, kaputte Nachrichten nach `chat.dlq` | vorhanden |
| postgres | PostgreSQL 16 | Speichert Räume und Chat-Verlauf; Keycloak hat eine eigene Datenbank im selben Container | vorhanden |
| keycloak | Keycloak 26 | Login (OIDC), Realm `chat` wird beim ersten Start importiert, Rollen als Claim `roles` | vorhanden |
| web-gateway | Spring Boot 3, Java 21 | Einziger nach aussen offener Port: Login über Keycloak, Proxy auf `/auth`, liefert die Web-UI aus, WebSocket `/ws/chat` mit Zustellung nach Raum, Räume und Verlauf, Queue-Tiefe für admin | vorhanden |
| load-generator | Spring Boot 3, Java 21 | Erzeugt Last direkt auf den chat-service (Profil `load`) | vorhanden |
| Web-UI | React 19 + Vite | Browser-Client, wird im Docker-Build des Gateways gebaut | Räume, Verlauf, Chat, Queue-Balken für admin |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`docs/plan-schritt-4-persistenz.md`](docs/plan-schritt-4-persistenz.md) — Plan für Schritt 4:
  batch-writer, Verlauf, Räume, Zustellung nach Raum.
- [`docs/plan-schritt-5-last.md`](docs/plan-schritt-5-last.md) — Plan für Schritt 5:
  load-generator, Queue-Tiefe, Rolle admin.
- [`docs/messreihe.md`](docs/messreihe.md) — Schritt 6: Messreihe, Ergebnisse und was sie bedeuten.
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
