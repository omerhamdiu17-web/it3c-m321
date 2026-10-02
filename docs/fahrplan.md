# Fahrplan bis zum fertigen System

**Modul M321 · Klasse IT3c · Omer Hamdiu · Stand 02.10.2026**

Was nach Bewertung 1 noch fehlt, in welcher Reihenfolge es gebaut wird, wie gearbeitet wird und
welche Entscheide schon gefallen sind. Was davon erledigt ist und womit es belegt ist, steht in der
[Checkliste](checkliste.md).

Grundlagen: [`PLANUNG.md`](../PLANUNG.md) (Kapitel 1, 6 und 7), [`CLAUDE.md`](../CLAUDE.md), der Stand
des Tags [`bewertung-1`](https://github.com/omerhamdiu17-web/it3c-m321/tree/bewertung-1) (`b972f3c`).

---

## 1. Ausgangslage

Seit Bewertung 1 laufen `chat-service`, RabbitMQ, `batch-writer` und PostgreSQL in
`docker-compose.yml`. Eine Nachricht, die per `POST /messages` ankommt, landet in der Tabelle
`message`. Es fehlt alles, was ein Mensch sieht: Login, Gateway, Oberfläche, Räume und Verlauf, Last,
Messreihe und Desktop-Client.

In der Reihenfolge von PLANUNG.md Kapitel 6 sind damit Schritt 1 (ohne Keycloak) und der
Schreibweg von Schritt 4 erledigt. Schritt 3 «Ein Weg durch» ist zur Hälfte gebaut: Der
`chat-service` legt jede Nachricht auf den Fanout `chat.delivery`, aber niemand holt sie dort ab.

## 2. Bausteine

Jeder Baustein schliesst einen oder mehrere Schritte aus PLANUNG.md Kapitel 6 ab und endet mit einem
Tag.

| # | Baustein | Schritte (PLANUNG 6) | Fertig, wenn … | Abnahme | Tag |
|---|---|---|---|---|---|
| 1 | Login, Gateway, Web-UI | 1 (Keycloak), 2, 3 | eine Nachricht vom Browser in einen zweiten Browser kommt, beide über Keycloak angemeldet, und `docker-compose.yml` genau einen Port hat (`127.0.0.1:8080` am `web-gateway`) | W1–W12 | `schritt-3` |
| 2 | Räume und Verlauf | 4 (Lesepfad) | der Browser beim Öffnen eines Raums dessen letzte 50 Nachrichten zeigt | R1–R7 | `schritt-4` |
| 3 | Last und Rollen | 5 | der `load-generator` im Profil `load` Last erzeugt und nur `admin` die Queue-Tiefe als Balken sieht | L1–L7 | `schritt-5` |
| 4 | Skalieren und Messreihe | 6 | eine Messreihe zeigt, was `--scale` bewirkt und ob 100'000 Nachrichten pro Minute erreicht werden | M1–M5 | `schritt-6` |
| 5 | Desktop-Client (Kür) | 7 | ein JavaFX-Client an derselben Schnittstelle chattet, mit Login über den Systembrowser | K1–K4 | `schritt-7` |

Am Schluss: Probe-Bewertung des ganzen Projekts, Korrekturen, Tag `abgabe`.

Die Szenarien jedes Bausteins legt seine Spezifikation fest, **bevor** Code entsteht. Die Kürzel in
der Tabelle sind Platzhalter dafür.

## 3. Arbeitsweise

Wie bei Bewertung 1, für jeden Baustein:

1. **Spezifikation** `docs/spec-<baustein>.md`: Zweck und Abgrenzung, Vertrag, Verhalten mit
   Fehlerfällen, Konfiguration, Abweichungen von PLANUNG.md, messbare Abnahmekriterien, Verlauf.
   Eigener Commit.
2. **Umsetzungsplan** `docs/plan-<baustein>.md`: Aufgaben in fester Reihenfolge, jede mit Test und
   Commit-Message. Eigener Commit.
3. **Jede Aufgabe testgetrieben:** zuerst der fehlschlagende Test, dann der Code, dann grün. Braucht
   der rote Lauf Container, läuft er als Wegwerf-Commit auf einem Branch `probe-*` in GitHub Actions.
   Ein Commit pro Aufgabe, mit der Message aus dem Plan.
4. **Abnahme:** die Szenarien als Skript, das bei jedem Push in GitHub Actions läuft. Danach die
   README.
5. **Review** mit frischem Blick gegen Spezifikation und CLAUDE.md. Jeder Befund bekommt eine Zeile
   im Plan und einen eigenen Commit.
6. **Abschluss-Prüfung** und Abnahmeprotokoll in der Spezifikation, mit Link auf den CI-Lauf und
   einem Lauf auf meinem Rechner.
7. **Probe-Bewertung** nach dem Raster von Bewertung 1 (Spezifikation, Plan und Ablauf, Szenarien,
   Tests), Korrekturen, Commit-Übersicht.
8. Checkliste nachführen, auf `main` bringen, Tag setzen.

Gearbeitet wird auf einem Branch `baustein/<name>`. Auf `main` kommt nur ein Stand, dessen
CI-Lauf grün war, und nur als Fast-Forward. `main` wird nicht mehr überschrieben.

## 4. Bewertung 1 bleibt prüfbar

Der bewertete Stand ist der Tag `bewertung-1` und bleibt unverändert. Damit die Szenarien S2 bis S8
auch auf dem wachsenden `main` grün bleiben, startet `scripts/abnahme.sh` ab Baustein 1 nur die vier
Dienste von Bewertung 1 (`rabbitmq`, `chat-service`, `postgres`, `batch-writer`). Das Gesamtsystem mit
Keycloak und Gateway prüft ein eigenes Skript `scripts/abnahme-system.sh`. Die Änderung bekommt einen
datierten Nachtrag in [`spec-batch-writer.md`](spec-batch-writer.md) Abschnitt 6.

## 5. Entscheide

**Von mir entschieden (02.10.2026)**

| Thema | Entscheid | Warum |
|---|---|---|
| Login im Browser | Das Gateway ist OIDC-Client und hält die Anmeldung in einer Server-Session. Der Browser bekommt nur ein Session-Cookie, nie ein Token. Zusätzlich prüft das Gateway Bearer-JWTs gegen den JWKS-Endpunkt von Keycloak (für den Desktop-Client und die Tests) | Ein Browser-WebSocket kann keinen `Authorization`-Header setzen, das Token müsste in die URL. Ein Token in JavaScript ist bei einer XSS-Lücke lesbar. Die JWT-Prüfung aus PLANUNG.md 3.3 gibt es trotzdem. Die Abweichung wird in der Spezifikation des Gateways begründet |
| Desktop-Client | wird gebaut, als letzter Baustein | Kür laut PLANUNG.md Kapitel 6, beweist die Clientneutralität. Erst wenn alle Pflichtteile fertig sind |
| Bewertungsraster | Probe-Bewertungen nach den Kategorien von Bewertung 1 | Für eine weitere Bewertung liegt noch kein Auftrag vor. Kommt einer, gehen seine Szenarien zuerst in die Spezifikation |

**Vorgesehen, die Spezifikation des Bausteins entscheidet endgültig**

| Thema | Vorgesehen | Warum |
|---|---|---|
| Datenhaltung von Keycloak | eingebaute Datei-Datenbank im Keycloak-Container (`start-dev`), Realm als JSON-Import | PLANUNG.md 3.1: «Keycloak bringt seine eigene Datenhaltung mit». Ein Ausfall von PostgreSQL (Szenario S7) bricht den Login nicht |
| Was das Gateway unter `/auth` durchreicht | nur den Realm `chat`, nicht die Admin-Konsole und nicht den Realm `master` | Offener Punkt 5 in PLANUNG.md: die Admin-Oberfläche ist «nicht erreichbar, das ist so gewollt» |
| Räume | eigene Tabelle `room` mit festen Räumen, **ohne** Fremdschlüssel von `message` darauf | Mit Fremdschlüssel landeten bei der Vorarbeit gültige Nachrichten in der DLQ (Spezifikation batch-writer, Abschnitt 7) |
| Gateway-Technik | Spring Boot, nicht nginx | PLANUNG.md Kapitel 5 und 3.1. Die README nannte nginx, das wird korrigiert |
| Abnahme ohne Python | bash und curl für den Login, ein kleines Java-Programm für den WebSocket, Playwright für zwei echte Browser | Auf meinem Rechner ist kein Python installiert, Java 21 und Node 22 schon |

## 6. Offene Punkte aus PLANUNG.md Kapitel 7

| # | Punkt | Wird behandelt in |
|---|---|---|
| 1 | Gateway skaliert nicht | Baustein 1: bewusst zurückgestellt, in der Spezifikation begründet |
| 2 | Datenbank wächst um 1,2 GB/Stunde | bleibt offen, Begründung in der Checkliste |
| 3 | Reihenfolge der Nachrichten | Baustein 1: der Client sortiert nach `sentAt` |
| 4 | Login im JavaFX-Client | Baustein 5 |
| 5 | Keycloak-Admin-Oberfläche | Baustein 1 |
| 6 | Rechte und Rollen | Baustein 3 |
| 7 | 100k/min auf einem Laptop | Baustein 4 |
| 8 | Lastverteilung auf `chat-service` | Baustein 4 |
| 9 | Kein Puffer auf dem Sendeweg | Baustein 1: «Nachricht nicht gesendet» im Client |

Die Antworten kommen in die Checkliste und in die Spezifikationen. PLANUNG.md bleibt der Text des
Lehrers und wird nicht geändert.

---

Dieser Fahrplan entstand mit Claude (KI) aus einer Durchsicht des Repositorys, des Archiv-Branchs
und der Dokumente von Bewertung 1. Die Entscheide in Abschnitt 5 habe ich getroffen.
