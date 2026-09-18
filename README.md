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
mvn test                         # alle Tests, RabbitMQ und Keycloak kommen per Testcontainers
docker compose up --build        # alle Container im Netz chat-net, Web-UI wird im Build gebaut
```

Danach im Browser `http://localhost:8080` öffnen. Das Gateway leitet zur Anmeldemaske von Keycloak
weiter (Testbenutzer siehe unten); nach dem Login zeigt die Seite den angemeldeten Benutzer, einen
Link zum Abmelden und den Chat. Beim ersten Start braucht Keycloak rund 30 Sekunden, solange
antwortet `/auth` mit einem Fehler.

**Chat ausprobieren:** in einem normalen Fenster als `alice` anmelden, in einem privaten Fenster
(oder einem zweiten Browser) als `bob`. Was alice schreibt, erscheint bei bob und umgekehrt. Es gibt
vorerst einen einzigen Raum («Lobby») und noch keinen Verlauf: wer die Seite neu lädt, sieht nur
neue Nachrichten. Der Weg einer Nachricht: Browser → WebSocket `/ws/chat` → `web-gateway` → REST →
`chat-service` → RabbitMQ (Fanout `chat.delivery`) → `web-gateway` → WebSocket → alle Browser.

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

Der Realm (`keycloak/realm-chat.json`) wird nur beim **ersten** Start importiert, genauso wie die
Datenbank-Skripte in `postgres/init/`. Nach einer Änderung daran: `docker compose down -v`, das
löscht die Datenbank-Dateien und löst beim nächsten Start Import und Skripte erneut aus.

Der Client `web-gateway` im Realm hat ein festes Beispiel-Secret. Das ist im Unterricht in Ordnung,
weil der Realm-Import keine Werte aus `.env` lesen kann; in einem echten System gehörte es dort hin.

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank | folgt |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf; Keycloak hat eine eigene Datenbank im selben Container | vorhanden |
| keycloak | Keycloak 26 | Login (OIDC), Realm `chat` wird beim ersten Start importiert | vorhanden |
| web-gateway | Spring Boot 3, Java 21 | Einziger nach aussen offener Port: Login über Keycloak, Proxy auf `/auth`, liefert die Web-UI aus, WebSocket `/ws/chat`, Zustellung aus RabbitMQ | vorhanden (Login, Senden, Zustellen) |
| Web-UI | React 19 + Vite | Browser-Client, wird im Docker-Build des Gateways gebaut | zeigt den Benutzer und den Chat in einem Raum |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
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
