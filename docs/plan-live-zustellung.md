# Live-Zustellung: Umsetzungsplan

**Modul M321 · Klasse IT3c · Bewertung 2 · Omer Hamdiu · Stand 2026-11-12**

Der Plan ist das Drehbuch für meine KI (Claude). Jeder Schritt ist klein, hat einen Test und
endet mit genau einem Commit. Grundlage ist meine Spezifikation
[`spec-live-zustellung.md`](spec-live-zustellung.md), dazu [`CLAUDE.md`](../CLAUDE.md) und der Auftrag
«Bewertung 2: Live-Zustellung» (Ausgabe 30.10.2026).

**Ziel:** Jede Instanz des `chat-service` holt sich über eine eigene Queue ihre Kopie jeder
Nachricht vom Fanout `chat.delivery` und schiebt sie per Server-Sent Events an die offenen
Verbindungen im Raum der Nachricht. Ein Client, der die Verbindung verloren hat, holt mit
`Last-Event-ID` genau nach, was ihm fehlt.

---

## Regeln für jeden Schritt

- Java 21, Spring Boot 3.5.16, keine neue Abhängigkeit im `chat-service` (SSE und AMQP sind schon da).
- Code auf Englisch, Kommentare, Commit-Messages und Doku auf Deutsch.
- Über jeder Klasse und jeder Methode ein Kommentar, der sagt, warum es sie gibt. Auch in den Tests.
- Keine Java-Streams (`.stream()`, `java.util.stream`, `Collectors`). `for`-Schleifen.
- Keine verschachtelten Aufrufe: ein Ergebnis pro Zeile in eine benannte Variable.
- Lambdas nur als Einzeiler dort, wo Spring einen Rückruf verlangt (Ende einer SSE-Verbindung).
- Keine Mock-Frameworks. Tests laufen gegen einen echten RabbitMQ (Testcontainers) oder mit
  handgeschriebenen Testhilfen.
- Kein `ports:`-Eintrag und kein `container_name` in `docker-compose.yml`.
- Ein Thema pro Commit. Jeder Commit, den die KI geschrieben hat, endet mit
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Arbeitsweise

1. Ich gebe der KI den Auftrag aus dem Schritt, im Wortlaut wie unten.
2. Ich lese, was sie liefert, lasse den Test laufen und korrigiere.
3. Erst dann wird committet. Danach trage ich unter «Geprüft und korrigiert» ein, was ich an
   ihrem Vorschlag geändert habe und warum.
4. Der Plan ist ab dem ersten Push fest. Muss ich umplanen, bleibt der alte Text stehen und ein
   neuer Eintrag mit Änderungsgrund kommt in Abschnitt «Änderungen am Plan», in einem eigenen Commit.

## Reihenfolge und warum

| # | Schritt | Warum an dieser Stelle |
|---|---|---|
| 1 | Fehlende Kommentare im `chat-service` | S8 misst den ganzen `chat-service`. Zuerst auf 0, dann hält jeder Schritt die 0 |
| 2 | Eigene Queue pro Instanz am Fanout | Ohne Queue verwirft RabbitMQ jede Kopie. Alles Weitere baut darauf |
| 3 | Raum → offene Verbindungen | Reine Java-Logik ohne Broker und Web, darum zuerst und mit schnellem Unit-Test |
| 4 | Listener an der eigenen Queue | Verbindet Schritt 2 mit Schritt 3. Erst jetzt kommt eine Nachricht im Raum an |
| 5 | Endpunkt `GET /stream` | Erst jetzt sieht ein Client etwas. Davor gäbe es nichts zu streamen |
| 6 | Nachholen mit `Last-Event-ID` | Braucht den laufenden Endpunkt aus Schritt 5 |
| 7 | Test mit zwei Instanzen | Prüft das Zusammenspiel von 2 bis 5 so, wie S3 es misst |
| 8 | Abnahmeskript S2 bis S8 und CI | Misst am echten Stack, was die Tests im Kleinen zeigen, auch S4, S5, S6 |
| 9 | README und `.env.example` | Erst wenn feststeht, was gebaut ist |
| 10 | Abnahme und Protokoll | Zum Schluss, auf dem fertigen Stand, in der Reihenfolge des Lehrers |

---

## Schritt 1: Fehlende Kommentare im chat-service

- **Ziel:** `bash scripts/kommentare.sh chat-service/src` meldet nichts mehr. Heute sind es 17
  Stellen (2 im Code, 15 Testmethoden, `docs/checkliste.md` R2).
- **Auftrag an die KI:**
  > Lies CLAUDE.md. Führe `bash scripts/kommentare.sh chat-service/src` aus und ergänze über jeder
  > gemeldeten Klasse und Methode einen deutschen Kommentar, der erklärt, warum es sie gibt. Ändere
  > keinen Code, nur Kommentare. Danach muss das Skript ohne Ausgabe enden und `mvn -pl chat-service test` grün sein.
- **Test:** `bash scripts/kommentare.sh chat-service/src; echo $?` vorher 17 Zeilen und `1`, nachher keine Zeile und `0`.
- **Commit:** `docs: Kommentare über allen Klassen und Methoden des chat-service`
- **Geprüft und korrigiert:** 12.11.2026, geprüft von Claude: `kommentare.sh` vorher 17 Zeilen und Exit 1, nachher keine Zeile und Exit 0. `mvn -pl chat-service test`: 14 Tests grün. Nur Kommentare dazu, kein Code geändert (`git diff --stat`: 17 Zeilen eingefügt, 0 gelöscht). Nichts korrigiert.

## Schritt 2: Eigene Queue pro Instanz am Fanout

- **Ziel:** Beim Start legt jede Instanz eine Queue `chat.delivery.<zufällig>` an: nicht durable,
  `exclusive`, `auto-delete`, `x-max-length = 900`, `x-overflow = drop-head`, gebunden an
  `chat.delivery`. Heartbeat zum Broker 10 s (Spezifikation 2.3, 3.1 bis 3.3).
- **Warum 900 und nicht 1000:** `x-max-length` zählt nur Nachrichten, die in der Queue warten.
  Die bis zu 100, die RabbitMQ der Instanz schon auf Vorrat geschickt hat (`prefetch`, Schritt 4),
  zählen nicht dazu. `rabbitmqctl list_queues messages` zählt aber beide. 900 + 100 = 1000, so hält
  die Queue nie mehr als 1000 (Spezifikation 3.3, S6).
- **Auftrag an die KI:**
  > Ergänze im chat-service in `RabbitConfig` eine Queue für die Live-Zustellung, wie sie in
  > `docs/spec-live-zustellung.md` im Abschnitt Queues steht: Name `chat.delivery.` plus eine
  > zufällige UUID, nicht durable, exclusive, auto-delete, `x-max-length` 900 und
  > `x-overflow` drop-head, und ein Binding an den Fanout `chat.delivery`. Lege den Namensanfang in
  > `QueueNames` ab. Setze in `application.yml` den Heartbeat auf 10 s. Schreib zuerst einen
  > Integrationstest mit echtem RabbitMQ, der rot ist: nach dem Start hängt genau eine Queue mit
  > diesem Namensanfang und diesen Argumenten am Fanout, und nach dem Schliessen des
  > Spring-Kontexts ist sie weg.
- **Test:** `DeliveryQueueIntegrationTest` (neu), rot vor dem Code, grün danach. `mvn -pl chat-service test`.
- **Commit:** `feat: eigene Queue pro Instanz am Fanout chat.delivery`
- **Geprüft und korrigiert:** 12.11.2026, geprüft von Claude: Test zuerst rot (`expected: <1> but was: <0>`, keine Queue am Fanout), nach dem Code grün, 16 Tests. Korrigiert: Die Testhilfe las die Argumente als JSON-Objekt, `rabbitmqctl` liefert sie aber als Liste `[Name, Typ, Wert]` (von Hand an einer Probe-Queue nachgeschaut). Dazu gekommen: zwei Testhilfen, `ChatServiceStarter` (startet eine Instanz mit eigenem Kontext, gebraucht auch in Schritt 7) und `BrokerInspector` (fragt `rabbitmqctl` wie die Abnahme).

## Schritt 3: Raum → offene Verbindungen

- **Ziel:** Eine Klasse `RoomSubscribers` merkt sich pro Raum die offenen SSE-Verbindungen. Eine
  Nachricht geht nur an die Verbindungen ihres Raums. Eine Verbindung, an die das Senden scheitert,
  fliegt raus. Alle Methoden laufen unter einer Sperre (`synchronized`), damit die Reihenfolge
  gleich bleibt.
- **Auftrag an die KI:**
  > Baue im chat-service eine Klasse `RoomSubscribers` (Paket `service`). Sie hat `subscribe(roomId, emitter)`,
  > `unsubscribe(roomId, emitter)` und `deliver(message)`. `deliver` schickt ein SSE-Ereignis mit
  > `id:` = Nachrichten-ID und `data:` = das JSON der Nachricht unverändert, und zwar nur an die
  > Verbindungen im Raum der Nachricht. Scheitert das Senden, entferne die Verbindung. Alles
  > `synchronized`. Schreib zuerst einen Unit-Test ohne Spring mit einer handgeschriebenen
  > Testhilfe, die `SseEmitter` erweitert und mitschreibt, was gesendet wurde: zwei Verbindungen
  > in Raum A bekommen die Nachricht, eine in Raum B nicht, eine kaputte Verbindung wird entfernt.
- **Test:** `RoomSubscribersTest` (neu).
- **Commit:** `feat: Nachricht nur an die Verbindungen ihres Raums`
- **Geprüft und korrigiert:** 12.11.2026, geprüft von Claude: Test zuerst rot (Klasse fehlt), danach 3 von 3 grün. Der Test vergleicht das ganze Ereignis Zeichen für Zeichen (`id:…\ndata:…\n\n`). Korrigiert: zwei verschachtelte Aufrufe im Test (`deliver(createMessage(…))`) in eigene Zeilen geteilt (CLAUDE.md). Dazu: Das JSON geht unverändert als `data:` weiter (Record `DeliveredMessage`), so ist das Format sicher dasselbe wie in `chat.persist`. Gesendet wird an eine Kopie der Liste, weil sich die Liste beim Abmelden ändern kann.

## Schritt 4: Listener an der eigenen Queue

- **Ziel:** Ein `DeliveryListener` liest jede Nachricht aus der eigenen Queue, liest `id` und
  `roomId` aus dem JSON und gibt sie mit dem unveränderten JSON an `RoomSubscribers`. Ein
  Verbraucher, `prefetch` 100, Bestätigung durch Spring (`AUTO`). Unlesbares JSON
  wird mit einer Warnung im Log verworfen und nicht zurückgelegt.
- **Auftrag an die KI:**
  > Baue im chat-service einen `DeliveryListener`, der die Queue aus Schritt 2 liest. Richte den
  > Container in `RabbitConfig` von Hand ein (`SimpleMessageListenerContainer`): diese eine Queue,
  > ein Verbraucher, `prefetch` 100, `AcknowledgeMode.AUTO`. Lies den Body selbst
  > mit dem ObjectMapper von Spring Boot, ohne `__TypeId__`. Gib Nachrichten-ID, Raum und das
  > unveränderte JSON an `RoomSubscribers.deliver`. Ist der Body kein lesbares JSON, schreib eine
  > Warnung ins Log und wirf die Nachricht weg, ohne Fehler (sonst käme sie immer wieder).
  > Test zuerst, mit echtem RabbitMQ: eine Testverbindung in Raum A bekommt nach einem
  > `POST /messages` genau ein Ereignis mit der ID aus der Antwort und dem JSON aus `chat.persist`.
- **Test:** `DeliveryListenerIntegrationTest` (neu).
- **Commit:** `feat: Kopie vom Fanout lesen und an den Raum geben`
- **Geprüft und korrigiert:** _nach dem Schritt_

## Schritt 5: Endpunkt GET /stream

- **Ziel:** `GET /stream?roomId=<UUID>` antwortet mit `Content-Type: text/event-stream` und
  bleibt offen, ohne Zeitlimit. Endet die Verbindung (Client weg, Fehler), wird sie aus
  `RoomSubscribers` entfernt. Fehlt `roomId` oder ist es keine UUID: 400. Beim Herunterfahren
  werden offene Verbindungen sofort geschlossen (`server.shutdown: immediate`), damit die Queue
  nicht auf sie wartet.
- **Auftrag an die KI:**
  > Baue im chat-service einen `StreamController` mit `GET /stream?roomId=…`, der einen `SseEmitter`
  > ohne Zeitlimit zurückgibt, ihn bei `RoomSubscribers` anmeldet und bei Ende, Zeitüberschreitung
  > und Fehler wieder abmeldet. Setze `server.shutdown: immediate` in `application.yml` und begründe
  > es im Kommentar. Test zuerst, gegen den laufenden Dienst auf einem freien Port mit einem echten
  > HTTP-Client (ohne Java-Streams, Zeilen über einen eigenen `Flow.Subscriber`):
  > Content-Type stimmt, ein Ereignis hat `id:` und `data:` mit den 6 Feldern, ein Client in Raum B
  > bekommt nichts, ein ungültiger Raum gibt 400.
- **Test:** `StreamEndpointIntegrationTest` (neu), Testhilfe `SseTestClient` (neu).
- **Commit:** `feat: Server-Sent Events unter GET /stream`
- **Geprüft und korrigiert:** _nach dem Schritt_

## Schritt 6: Nachholen mit Last-Event-ID

- **Ziel:** Jede Instanz merkt sich pro Raum die letzten 1000 Nachrichten (Spezifikation 2.4). Kommt ein Client mit Header `Last-Event-ID`, bekommt er zuerst alle Nachrichten
  nach dieser ID, dann live. Nachholen und Anmelden passieren unter derselben Sperre wie
  `deliver`, darum fehlt nichts und nichts kommt doppelt. Unbekannte ID: nichts nachholen, nur
  live, Warnung im Log (Spezifikation 2.6).
- **Auftrag an die KI:**
  > Erweitere `RoomSubscribers`: merke dir pro Raum die letzten 1000 Nachrichten (die älteste
  > fällt raus). `subscribe` bekommt die `Last-Event-ID` (darf fehlen)
  > und schickt vor dem Anmelden alles nach dieser ID, in derselben Sperre. Bei einer unbekannten ID
  > holst du nichts nach, meldest nur an und schreibst eine Warnung ins Log. `StreamController` liest den Header `Last-Event-ID`.
  > Tests zuerst: Unit-Test für das Nachholen und die unbekannte ID, und ein Integrationstest wie
  > S7: Client bekommt 1 Nachricht und trennt, 50 werden gesendet, Neuverbindung mit
  > `Last-Event-ID` bringt genau diese 50 in Reihenfolge ohne Doppelte, danach kommt eine neue live.
- **Test:** `RoomSubscribersTest` (erweitert), `ReconnectIntegrationTest` (neu).
- **Commit:** `feat: verpasste Nachrichten nach Last-Event-ID nachholen`
- **Geprüft und korrigiert:** _nach dem Schritt_

## Schritt 7: Test mit zwei Instanzen

- **Ziel:** Ein Test startet zwei `chat-service` im selben Test gegen denselben RabbitMQ, je ein
  Client in Raum A an jeder Instanz, ein dritter in Raum B. Über jede Instanz gehen Nachrichten für
  Raum A. Beide Clients in A bekommen alle, jede genau einmal, pro Absender in Reihenfolge. B bekommt keine.
- **Auftrag an die KI:**
  > Schreib einen Integrationstest, der zwei Spring-Kontexte des chat-service auf freien Ports gegen
  > denselben RabbitMQ-Testcontainer startet. Je ein `SseTestClient` in Raum A an jeder Instanz,
  > ein dritter in Raum B an Instanz 1. Schick über jede Instanz 50 Nachrichten für Raum A mit je
  > einem eigenen Absender. Prüfe: beide Clients in A haben 100 verschiedene IDs, die Texte jedes
  > Absenders kommen in ihrer Reihenfolge, der Client in B hat keine. Ändere keinen Produktionscode;
  > scheitert der Test, melde es mir.
- **Test:** `TwoInstancesIntegrationTest` (neu).
- **Commit:** `test: Verteilung an zwei Instanzen`
- **Geprüft und korrigiert:** _nach dem Schritt_

## Schritt 8: Abnahmeskript S2 bis S8 und CI

- **Ziel:** `bash scripts/abnahme-live.sh` stellt S2 bis S8 am echten Stack nach, in der
  Reihenfolge des Auftrags, misst jeden Wert und druckt «gemessen / erwartet». Clients sind
  `curl -N` in Containern im Netz `chat-net`, je Instanz über ihre IP. GitHub Actions führt es bei
  jedem Push aus. Das alte `scripts/abnahme.sh` (Bewertung 1) bleibt unverändert.
- **Auftrag an die KI:**
  > Schreib `scripts/abnahme-live.sh` nach dem Vorbild von `scripts/abnahme.sh` für die Szenarien
  > S2 bis S8 aus dem Auftrag von Bewertung 2, mit den Abnahmekriterien und Befehlen aus der
  > Spezifikation. S6 misst die Länge der pausierten Queue während des Sendens mehrmals pro Sekunde
  > und meldet den grössten Wert. S8 zählt Java-Streams, fehlende Kommentare und `.env` im Verlauf.
  > Füge in `.github/workflows/build.yml` einen Job dafür hinzu. Lass das Skript lokal laufen und
  > zeig mir die Tabelle.
- **Test:** das Skript selbst, lokal und in GitHub Actions, Exit-Code 0.
- **Commits:** `test: Abnahmeskript für die Live-Zustellung`, danach `ci: Abnahme der Live-Zustellung bei jedem Push`
- **Geprüft und korrigiert:** _nach dem Schritt_

## Schritt 9: README und .env.example

- **Ziel:** README-Tabelle «Was gebaut wird»: `chat-service` mit SSE unter `GET /stream`, Befehle
  zum Mitlesen eines Raums und zur neuen Abnahme. `.env.example`: neue Variablen, falls es welche gibt.
- **Auftrag an die KI:**
  > Führe die README nach: Zeile chat-service in der Tabelle «Was gebaut wird», ein Beispiel, wie man
  > einen Raum mit curl im Netz chat-net mitliest, und `bash scripts/abnahme-live.sh`. Prüfe, ob
  > der Code neue Umgebungsvariablen braucht; wenn ja, mit Beispielwert in `.env.example`.
- **Test:** Befehle aus der README einmal von Hand ausführen.
- **Commit:** `docs: README mit Live-Zustellung`
- **Geprüft und korrigiert:** _nach dem Schritt_

## Schritt 10: Abnahme und Protokoll

- **Ziel:** Auf dem fertigen Stand: `mvn clean test` (S1) und `bash scripts/abnahme-live.sh`
  (S2 bis S8) lokal, dazu der grüne CI-Lauf. Messwerte und Link hier im Plan unter «Abnahmeprotokoll».
  Danach Tag `bewertung-2` auf `main` und pushen.
- **Auftrag an die KI:**
  > Lass auf einem frischen Klon `mvn clean test` und `bash scripts/abnahme-live.sh` laufen und
  > trag die Messwerte mit Datum und Link auf den grünen CI-Lauf in den Plan ein.
- **Test:** S1 bis S8 bestanden.
- **Commit:** `docs: Abnahmeprotokoll der Live-Zustellung`
- **Geprüft und korrigiert:** _nach dem Schritt_

---

## Änderungen am Plan

Noch keine. Jede Änderung kommt hier als eigener Eintrag mit Datum, was sich ändert und
Änderungsgrund, in einem eigenen Commit.

## Abnahmeprotokoll

_Nach Schritt 10._
