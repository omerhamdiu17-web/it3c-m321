# Checkliste: Vorgaben, Stand, Nachweis

**Modul M321 · Klasse IT3c · Omer Hamdiu · Stand 02.10.2026 (nach Bewertung 1, vor Baustein 1)**

Jede Vorgabe des Lehrers und des Projekts mit ihrem Stand und dem Beleg dafür. Die Liste wird am
Ende jedes Bausteins nachgeführt (siehe [Fahrplan](fahrplan.md)). Zeilenangaben beziehen sich auf den
Tag [`bewertung-1`](https://github.com/omerhamdiu17-web/it3c-m321/tree/bewertung-1).

Stand: **erfüllt** · **teilweise** · **fehlt** · **bewusst offen** (mit Begründung)

---

## 1. Vorgaben des Lehrers

Quelle: PLANUNG.md Kapitel 1 («vorgegeben und stehen nicht zur Diskussion») und der Auftrag vom
27.08.2026.

| # | Vorgabe | Stand | Nachweis / was fehlt | Baustein |
|---|---|---|---|---|
| L1 | Java 21 | erfüllt | `pom.xml:23` `<java.version>21</java.version>`, Spring Boot 3.5.16 (`pom.xml:12`) | – |
| L2 | Keycloak als Login-Dienst, kein selbstgebautes Login | **fehlt** | kein Keycloak in `docker-compose.yml` | 1 |
| L3 | Message Queues | erfüllt | RabbitMQ: `chat.persist`, `chat.delivery`, `chat.dlq` (`chat-service/…/config/QueueNames.java:13-19`) | – |
| L4 | Alles in `docker-compose`, ein `up` startet das System | teilweise | 4 Dienste (`docker-compose.yml`), Keycloak und Gateway fehlen | 1 |
| L5 | Internes Docker-Netzwerk, Dienste über Service-Namen | erfüllt | Netz `chat-net` (`docker-compose.yml:83-85`), z. B. `RABBITMQ_HOST: rabbitmq` (`:23`) | – |
| L6 | Nur die Web-App über localhost: genau **ein** Port-Mapping | teilweise | heute **kein** Port (Szenario S2, `scripts/abnahme.sh:156`); der eine Port `127.0.0.1:8080` am `web-gateway` fehlt | 1 |
| L7 | Skaliertes System mit 100'000+ Nachrichten pro Minute zeigen | **fehlt** | keine Messung auf `main` | 4 |
| L8 | Abschnitt «Verlauf», den die KI schreibt | erfüllt | PLANUNG.md Kapitel 8; Spezifikation batch-writer Abschnitt 7 | jede Spezifikation |

## 2. Projektregeln (CLAUDE.md)

| # | Regel | Stand | Nachweis / was fehlt | Baustein |
|---|---|---|---|---|
| R1 | Code englisch, alles andere deutsch | erfüllt | ganzer Code; Commit-Messages deutsch (`git log`) | – |
| R2 | Kommentar über jeder Klasse und jeder Methode | teilweise | batch-writer: 0 fehlend (`bash scripts/kommentare.sh batch-writer/src`). **chat-service: 17 fehlend** (2 im Code: `main`, `handleBrokerNotAvailable`; 15 Testmethoden), Code des Lehrers | 2 |
| R3 | Keine verschachtelten Aufrufe, `for` statt Stream | erfüllt | batch-writer: Szenario S8 (0 × «stream»); wird Kriterium jedes neuen Moduls | alle |
| R4 | Lombok für Boilerplate, `record` für Daten, keine Vorrats-Abstraktion | erfüllt | z. B. `MessageBatchListener` (`@Service @Slf4j @RequiredArgsConstructor`), `ChatMessage` als `record` | – |
| R5 | Kleine Schritte, ein Thema pro Commit | erfüllt | Commit-Übersicht in `docs/plan-batch-writer.md` | alle |
| R6 | Nichts behaupten, was nicht geprüft wurde | erfüllt | Abnahmeprotokoll mit CI-Link in `docs/spec-batch-writer.md` Abschnitt 6 | alle |
| R7 | Keine Geheimnisse im Repository | erfüllt | `.env` in `.gitignore:17`; S8 prüft den ganzen Verlauf | 1 (Keycloak-Secret über `.env`) |

## 3. Bewertung 1: batch-writer, Szenarien S1 bis S8

| Nr | Stand | Nachweis |
|---|---|---|
| S1–S8 | erfüllt | Probelauf in der Reihenfolge des Lehrers: CI-Lauf [36695032988](https://github.com/omerhamdiu17-web/it3c-m321/actions/runs/36695032988); Abnahmeprotokoll in `docs/spec-batch-writer.md` Abschnitt 6; letzter Lauf auf `main`: [36706431497](https://github.com/omerhamdiu17-web/it3c-m321/actions/runs/36706431497) |
| lokal | erfüllt | Nachlauf am 02.10.2026 auf meinem Rechner (Windows 11, Docker Engine 29.3.1, Compose v5.1.1): `mvn -B clean test` 14 + 29 Tests grün; `bash scripts/abnahme.sh` S2–S8 PASS (S4: 16 Transaktionen, davon 2 schreibend; S7: 300 Zeilen 12 s nach dem Neustart) |

## 4. Umsetzungsreihenfolge (PLANUNG.md Kapitel 6)

| # | Schritt | Ergebnis laut PLANUNG | Stand | Was fehlt | Baustein |
|---|---|---|---|---|---|
| 1 | Gerüst | compose mit RabbitMQ, Postgres, Keycloak; Maven-Elternprojekt | teilweise | Keycloak | 1 |
| 2 | Login | Keycloak-Realm, Gateway mit Proxy und JWT-Prüfung, React zeigt den Benutzernamen | **fehlt** | alles | 1 |
| 3 | Ein Weg durch | Nachricht vom Browser bis zum zweiten Browser, ohne Datenbank | teilweise | Gateway, WebSocket, Web-UI; der `chat-service` veröffentlicht schon auf `chat.delivery` | 1 |
| 4 | Persistenz | `batch-writer` mit Bulk-Insert, Historie beim Öffnen eines Raums | teilweise | Schreibweg erfüllt (Bewertung 1); Historie fehlt | 2 |
| 5 | Last | `load-generator`, Queue-Tiefe in der Oberfläche | **fehlt** | alles | 3 |
| 6 | Skalieren | `--scale`, Competing Consumers, Messreihe | teilweise | Competing Consumers am batch-writer belegt (S6); Messreihe fehlt | 4 |
| 7 | Desktop (Kür) | JavaFX-Client an derselben API | **fehlt** | alles | 5 |

## 5. Offene Punkte (PLANUNG.md Kapitel 7)

| # | Punkt | Stand | Weg | Baustein |
|---|---|---|---|---|
| 1 | Gateway skaliert nicht | bewusst offen | in der Spezifikation des Gateways begründen; nginx davor bleibt zurückgestellt | 1 |
| 2 | Datenbank wächst um 1,2 GB/Stunde | bewusst offen | Begründung folgt im Abschluss | – |
| 3 | Reihenfolge der Nachrichten | fehlt | Client sortiert nach `sentAt` und entfernt Duplikate nach `id` | 1 |
| 4 | Login im JavaFX-Client | fehlt | Systembrowser, PKCE, Rückleitung auf `127.0.0.1` (RFC 8252) | 5 |
| 5 | Keycloak-Admin-Oberfläche nicht erreichbar | fehlt | Gateway reicht nur den Realm `chat` durch | 1 |
| 6 | Rechte und Rollen | fehlt | Rollen `user` und `admin`, nur `admin` sieht die Queue-Tiefe | 3 |
| 7 | 100k/min auf einem Laptop | fehlt | Messreihe mit vorher festgelegtem Kriterium | 4 |
| 8 | Lastverteilung auf `chat-service` | fehlt | messen, Verteilung je Instanz zählen | 4 |
| 9 | Kein Puffer auf dem Sendeweg | fehlt | Client zeigt «Nachricht nicht gesendet» und behält den Text | 1 |

## 6. README

| Zeile der Tabelle «Was gebaut wird» | Stand | Baustein |
|---|---|---|
| chat-service, rabbitmq, batch-writer, postgres: «vorhanden» | stimmt | – |
| keycloak: «folgt» | stimmt, fehlt noch | 1 |
| web-gateway: «nginx», «folgt» | **falsch**: laut PLANUNG.md Kapitel 5 Spring Boot | Korrektur vor Baustein 1 |
| Web-UI: «folgt» | stimmt, fehlt noch | 1 |

## 7. Handlungsziele des Moduls M321

| HZ | Handlungsziel | Stand | Nachweis / was fehlt |
|---|---|---|---|
| 1 | Analysiert Softwaresysteme und überführt sie in verteilte Systeme | erfüllt | PLANUNG.md, Spezifikation batch-writer |
| 2 | Verwendet Systemkomponenten in verteilten Systemen | teilweise | RabbitMQ und PostgreSQL im Einsatz; Keycloak fehlt (Baustein 1) |
| 3 | Verbindet Systemteile über wohldefinierte Schnittstellen | teilweise | `POST /messages` und das Nachrichtenformat auf der Queue sind spezifiziert; Gateway-API und WebSocket fehlen (Baustein 1, 2) |
| 4 | Implementiert Systemkomponenten und überprüft deren Funktion | teilweise | batch-writer mit 29 Tests und Abnahme S2–S8; die übrigen Dienste fehlen (Bausteine 1–5) |

## 8. Technische Mängel am bestehenden Stand

| Mangel | Folge | Behebung | Baustein |
|---|---|---|---|
| Healthcheck von RabbitMQ prüft nur, ob der Prozess lebt (`ping`) | ein Dienst kann starten, bevor RabbitMQ Verbindungen annimmt | `rabbitmq-diagnostics check_port_connectivity` | 1 |
| CI läuft auf `ubuntu-latest` | GitHub stellt ab 19.10.2026 auf Ubuntu 26 um, der Lauf kann sich ohne Commit ändern | Runner fest auf `ubuntu-24.04` | vor 1 |
| `chat-service`: veröffentlicht er auf `chat.persist`, aber nicht mehr auf `chat.delivery`, bekommt der Client `503`, obwohl die Nachricht gespeichert wird | ein erneutes Senden erzeugt eine zweite Nachricht mit neuer `id` | als Fehlerfall in der Spezifikation des Gateways beschreiben | 1 |
| PLANUNG.md 3.5: `chat.dlq` «nach 3 fehlgeschlagenen Versuchen» | – | bereits begründete Abweichung in `docs/spec-batch-writer.md` Abschnitt 5 | erfüllt |
