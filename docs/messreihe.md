# Schritt 6: Messreihe

**Frage aus der Planung:** Schafft das System 100'000 Nachrichten pro Minute (offener Punkt 7)?
Wirkt `--scale batch-writer=N` (Competing Consumers)? Verteilt sich die Last bei
`--scale chat-service=N` wirklich (offener Punkt 8)?

**Kurzantwort:**

| Frage | Ergebnis auf der Messmaschine |
|---|---|
| 100'000/min? | **Knapp nicht im Takt.** Alle 99'960 Nachrichten kommen an, ohne Fehler und ohne Verlust — aber in 64–72 s statt 60 s, also mit **83'000–93'000 pro Minute**. Der Engpass ist der Sendeweg, nicht die Datenbank. |
| Schreibweg (batch-writer) | **Zehnmal mehr als nötig.** Ein einziger batch-writer schreibt rund **17'500 Nachrichten pro Sekunde** (≈ 1 Million pro Minute) und braucht im Normalbetrieb nur ~10 % CPU. Die Queue wird nie tiefer als ein bis drei Stapel. |
| `--scale batch-writer` | **Hilft, bis die Maschine voll ist.** 1 → 2 Instanzen: +24 %. 2 → 3: kein Gewinn mehr, alle 4 Kerne sind belegt. |
| `--scale chat-service` | **Verteilt nicht.** Mit 3 Instanzen arbeitet zu jedem Zeitpunkt nur **eine**. Auch ohne DNS-Cache. Offener Punkt 8 ist damit bestätigt, nicht gelöst. |

---

## Messaufbau

- **Maschine:** GitHub-Actions-Runner `ubuntu-latest`, 4 CPU-Kerne (AMD EPYC 9V74), 15 GB RAM.
  Alle Container teilen sich diese 4 Kerne — auch der load-generator selbst.
- **Werkzeug:** [`scripts/messreihe.sh`](../scripts/messreihe.sh), gestartet vom Workflow
  [`.github/workflows/messreihe.yml`](../.github/workflows/messreihe.yml) (Lauf vom 23.09.2026,
  10:03–10:17 UTC).
- **Last:** der `load-generator` ruft den `chat-service` direkt per REST auf, Raum «Lasttest»,
  Ziel 100'000/min = 1'666 Anfragen pro Sekunde, höchstens 500 gleichzeitig unterwegs.
- **Gemessen:** alle 5 Sekunden die Tiefe von `chat.persist` (`rabbitmqctl`) und die Zahl der
  gespeicherten Nachrichten (`SELECT count(*)`); zur Hälfte der Last einmal `docker stats`.
- **Nicht dabei:** `web-gateway` und `keycloak`. Gemessen wird die Architektur hinter dem Gateway
  (PLANUNG.md, Abschnitt 4.2).
- **Rohdaten:** [`messreihe-2026-09-23/`](messreihe-2026-09-23/) — Tabellen (`ergebnis.md`),
  Verläufe als CSV, Protokolle des load-generator und `docker stats`.

Eine Messung ist eine Momentaufnahme **dieser** Maschine. Auf einem Laptop mit 8 oder mehr Kernen
kommen andere Zahlen heraus — genau deshalb gibt es das Skript: `scripts/messreihe.sh` misst auf
dem eigenen Rechner dasselbe.

---

## Versuch 1: Normalbetrieb mit 1, 2 und 3 batch-writer

| batch-writer | angenommen | Fehler | Dauer | Ist-Rate pro Minute | grösste Queue-Tiefe | gespeichert |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 99'960 | 0 | 72,1 s | 83'232 | 490 | 99'960 |
| 2 | 99'960 | 0 | 64,6 s | 92'844 | 898 | 99'960 |
| 3 | 99'960 | 0 | 64,2 s | 93'392 | 1'365 | 99'960 |

Verlauf beim Lauf mit einem batch-writer (Sekunde, Queue-Tiefe, gespeichert):

```
  0 s      0         0
 13 s    293     6'619
 27 s    249    24'658
 43 s     77    50'658
 56 s     62    72'806
 69 s    216    94'464
 76 s      0    99'960
```

**Was man sieht:**

- Die Queue ist nie tiefer als ein bis drei Stapel à 500. Der batch-writer holt ab, was kommt;
  die Nachricht steht spätestens eine Sekunde nach ihrer Annahme in der Datenbank.
- Kein einziger Verlust: angenommen = gespeichert, in jedem Lauf.
- Der erste Lauf ist langsamer als die anderen: die JVMs sind noch «kalt» (der JIT-Compiler
  übersetzt den heissen Code erst nach einigen Sekunden). Deshalb sind Lauf 2 und 3 aussagekräftiger.
- Wo die Rechenzeit hingeht (`docker stats` zur Hälfte der Last, Lauf 1):

  | Container | CPU |
  |---|---:|
  | load-generator | 102 % |
  | rabbitmq | 99 % |
  | chat-service | 67 % |
  | batch-writer | 11 % |
  | postgres | 5 % |

  **Der Engpass ist der Sendeweg**: pro Nachricht eine HTTP-Anfrage an den chat-service und zwei
  Veröffentlichungen in RabbitMQ (Schreibweg und Zustellweg), jede persistent. Die Datenbank
  langweilt sich — der Bulk-INSERT macht aus 1'666 Nachrichten pro Sekunde gut drei Transaktionen.
  Dass der load-generator selbst einen ganzen Kern braucht, gehört zur Wahrheit dazu: auf
  derselben Maschine misst man immer auch den Messenden mit.

## Versuch 2: Stau abbauen

Um zu sehen, was der Schreibweg **kann** (und nicht nur, was er im Normalbetrieb **muss**), läuft
die Last 120 Sekunden **ohne** batch-writer. Die Queue füllt sich auf knapp 200'000 Nachrichten.
Dann werden 1, 2 oder 3 batch-writer gestartet, und wir messen, wie schnell die Queue leer ist.

| batch-writer | Stau | Abbau-Rate pro Sekunde | pro Minute | CPU der batch-writer | CPU rabbitmq | CPU postgres |
|---:|---:|---:|---:|---|---:|---:|
| 1 | 199'920 | **17'500** | 1'050'000 | 50 % | 78 % | 38 % |
| 2 | 199'920 | **21'600** | 1'300'000 | 90 % + 38 % | 104 % | 40 % |
| 3 | 199'920 | **19'300** | 1'160'000 | 110 % + 110 % + 94 % | 62 % | 38 % |

Die Raten sind aus den Verläufen (`stau-N-writer.csv`) berechnet: Nachrichten, die zwischen der
ersten und der letzten Messung abgebaut wurden, geteilt durch die Zeit dazwischen. (Die Tabelle in
`ergebnis.md` rechnet ab dem Moment, in dem alle batch-writer verbunden sind, und unterschätzt
leicht, weil die ersten schon vorher losgelegt haben.)

**Was man sieht:**

- **Competing Consumers funktioniert:** RabbitMQ verteilt die Queue ohne unser Zutun auf alle
  batch-writer, keine Nachricht geht doppelt oder verloren (gespeichert = Stau).
- **Aber Skalieren auf EINER Maschine hat eine Decke.** Mit drei batch-writern sind die vier Kerne
  voll: drei Java-Prozesse, RabbitMQ und Postgres streiten sich um dieselbe CPU. Der dritte
  batch-writer nimmt den anderen Rechenzeit weg, statt neue dazuzubringen. `--scale` erzeugt
  Container, keine Hardware. Echte Skalierung heisst: mehr Maschinen.
- Für das Ziel reicht schon einer: 17'500 pro Sekunde sind das Zehnfache von 1'666.

## Versuch 3: 3 chat-service-Instanzen

| Versuch | angenommen | Fehler | Ist-Rate pro Minute | Antworten pro Instanz | CPU der drei Instanzen (Momentaufnahme) |
|---|---:|---:|---:|---|---|
| mit DNS-Cache (Standard) | 99'960 | 0 | 85'892 | A: 54'470 · B: 45'490 · C: 0 | 253 % · 3 % · 0 % |
| ohne DNS-Cache (`-Dnetworkaddress.cache.ttl=0`) | 99'951 | 9 | 84'205 | A: 44'982 · B: 54'969 · C: 0 | 185 % · 0 % · 0 % |

Die Instanz-Namen stammen aus dem Antwort-Header `X-Chat-Service-Instance`, den der load-generator
zählt.

**Was man sieht:**

- **Mehr Instanzen, kein Gewinn.** Die Rate ist nicht höher als mit einer Instanz.
- **Zu jedem Zeitpunkt arbeitet genau eine Instanz** (die `docker stats`-Momentaufnahme zeigt eine
  bei 185–253 %, die anderen bei 0). Über den ganzen Lauf kommen zwei Instanzen vor, weil die Last
  mittendrin einmal von einer zur anderen **gewandert** ist; die dritte hat nie etwas bekommen.
- **Warum:** Docker-DNS löst `chat-service` zwar auf drei Adressen auf. Aber der HTTP-Client des
  load-generator öffnet seine Verbindungen am Anfang und benutzt sie dann immer wieder
  (Keep-Alive, Verbindungspool). Alle Verbindungen, die in denselben paar Sekunden aufgemacht
  werden, zeigen auf dieselbe Adresse. Genau das hat PLANUNG.md in offenem Punkt 8 vorhergesagt:
  *«ein HTTP-Client mit Verbindungspool merkt sich gern die erste Adresse und schickt dann alles
  dorthin»*.
- **Der DNS-Cache allein ist nicht die Ursache.** Ihn auszuschalten ändert nichts — vermutlich, weil gar nicht neu
  aufgelöst wird — die Verbindungen bleiben ja offen. Die 9 Fehler fielen alle in dieselbe Sekunde
  (Sekunde 28); vermutlich genau der Moment, in dem die Verbindungen gewechselt haben.
- Die Anfragen des **web-gateway** an den chat-service laufen über denselben Mechanismus
  (`RestClient` mit Verbindungspool). Dort ist es vermutlich genauso; gemessen haben wir es nicht.

**Mögliche Wege**, jeweils mit ihrem Preis:

| Weg | Preis |
|---|---|
| Ein Lastverteiler (nginx, Traefik) vor den chat-service-Instanzen | Ein Container mehr; er verteilt pro Anfrage statt pro Verbindung |
| Keine Keep-Alive-Verbindungen (neue Verbindung pro Anfrage) | Jede Anfrage zahlt den Verbindungsaufbau; bei 1'666 pro Sekunde spürbar |
| Doch eine Eingangs-Queue (Competing Consumers auch auf dem Sendeweg) | Genau die Variante, die in Runde 3 der Planung verworfen wurde |

Entschieden ist das nicht; es bleibt offener Punkt 8, jetzt aber mit Messung statt Vermutung.

---

## Was wir daraus lernen

1. **Messen schlägt Vermuten.** Die Planung vermutete RabbitMQ als Engpass — das stimmt, aber
   zusammen mit dem chat-service und dem Messwerkzeug selbst. Die Datenbank, um die sich die ganze
   Batch-Architektur dreht, ist mit Faktor 10 Reserve der entspannteste Teil.
2. **Der Batch-Writer wirkt.** 1'666 Einzel-INSERTs pro Sekunde wären für Postgres viel Arbeit;
   als Bulk-INSERT sind es rund drei Transaktionen pro Sekunde bei 5 % CPU.
3. **Entkopplung trägt.** Selbst wenn gar kein batch-writer läuft (Versuch 2), nimmt der
   chat-service weiter an; die Queue puffert 200'000 Nachrichten, und nachher wird alles
   nachgeschrieben. Nichts geht verloren.
4. **`--scale` ist kein Zaubertrick.** Competing Consumers verteilt sauber, solange CPU frei ist.
   Lastverteilung über DNS verteilt praktisch gar nicht, sobald der Client Verbindungen wiederverwendet.
5. **Fehler in der Messung gehören dazu.** Der erste Versuch «3 chat-service» lief unbemerkt mit
   einer einzigen Instanz (siehe PLANUNG.md, Runde 4). Aufgefallen ist es nur, weil `docker stats`
   mitprotokolliert wurde. Seither steht die Zahl der laufenden Instanzen in jeder Tabellenzeile.

## Selbst messen

```bash
docker compose up -d --build
scripts/messreihe.sh                                          # beide Versuche, 1–3 batch-writer
CHAT_SERVICE_COUNT=3 WRITER_COUNTS=1 PHASES=durchsatz scripts/messreihe.sh
```

Ergebnisse landen in `messreihe/ergebnis.md`. Oder auf GitHub: Reiter «Actions» → «messreihe» →
«Run workflow»; die Ergebnisse liegen danach als Artefakt beim Lauf.
