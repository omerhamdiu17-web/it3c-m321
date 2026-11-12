# Live-Zustellung: Spezifikation

**Modul M321 · Klasse IT3c · Bewertung 2 · Omer Hamdiu · Stand 12.11.2026**

## 1. Vertrag

### 1.1 Was am Exchange ankommt

Chat Service leitet eine Nachricht weiter, sie schickt eine Nachricht zweimal, also doppelt. Und
dann wird sie bei chat.delivery gesendet, und bei chat.persist wird sie nur gespeichert. Bei
chat.delivery wird dann angezeigt, wer es war, wo und was als Text steht.

### 1.2 Woher ich das weiss

Man sieht das bei MessagePublisher.java. Man sieht es bei Zeile 35 und 36, dort ist die Persist,
also sie wird dort in die Queue, und dann dort ist auch die Delivery.

### 1.3 Was am Stream hinausgeht

Ein Client fragt eine Nachricht ab. Das heisst, er merkt sich die ID, also die Nummer der
Nachricht, damit er sie nachholen kann. Data, also das ist wie chat.persist, also das JSON, kann
man sagen. Und der Client bekommt nur eine Nachricht aus seinem Raum.

## 2. Verhalten

### 2.1 Normalfall: nur der eigene Raum

Die Clients gehören alle zu einem Raum. Zum Beispiel gibt es drei Clients: Client 1, Client 2,
Client 3. Zwei Clients wollen in Raum A, also in einem Chat XY, und der dritte Client will zu einem
anderen Raum. Das heisst, jeder Client gehört zu einem Raum, und so vermischen sich die Nachrichten
nicht.

### 2.2 Reihenfolge

Er nimmt also, es funktioniert so, dass jede Nachricht der Reihe nach genommen wird. Es hat nur
einen Leser. Das heisst, die anderen Nachrichten werden in einer Queue gegeben. Genau, dann wird
jede Nachricht in der richtigen Reihenfolge gesendet.

### 2.3 Hängende Instanz

Also die Nachrichten sind ja limitiert auf 1000 und was nach der 1000 kommt, ist dann in einer
Queue. Und dann checkt RabbitMQ, checkt dann, ob diese Instanz noch existiert, ob sie noch lebt,
und wenn sie nicht mehr lebt, dann trennt RabbitMQ die Verbindung und löscht ihre Queue.

### 2.4 Nachholen nach einem Abbruch

Also, die Nachrichten sind ja alle mit einer ID, mit einer Nummer, beschriftet. Und wenn man eine
Nachricht schickt und man einen Verbindungsfehler hat, dann verschickt es diese Nachricht nicht.
Man schickt dann aber 50 andere Nachrichten, die ankommen. Dann schaut auch der Client danach,
welche ID diese Nachricht hatte, sucht dann diese auch in der richtigen Reihenfolge und dann nimmt
er sie wieder, dann verschickt er sie live.

### 2.5 Datenbank weg (S5)

Warum die Nachricht trotzdem ankommt: Weil die Nachrichten über chat.persist und chat.delivery
laufen. Bei chat.persist werden sie dort einfach nachgetragen. Und bei chat.delivery werden sie
gesendet, und dafür braucht es keine Datenbank. Dann sieht man die Nachrichten direkt und sie
gehen nicht verloren.

### 2.6 Unbekannte ID

Was soll dann passieren? Also eine Lösung wäre, dass er sie nicht mehr nachholt, weil sie auch
vielleicht zu alt ist und diese ID dann nicht mehr finden kann. Genau, also das heisst jetzt
einfach lieber die Nachricht gar nicht jetzt finden oder suchen und vielleicht eine Warnung geben.

## 3. Queues

### 3.1 Warum jede Instanz eine eigene Queue hat

Weil wären sie auf einem gemeinsamen Queue, also auf einem Briefkasten, dann könnte man es nicht
unterscheiden, was wem gehört. Das heisst, Person A sieht nur die eine Hälfte, also die in einer
Queue sind, und die andere Person B sieht auch nur seine andere Hälfte. Das heisst, sie sehen nur
die eigene Seite und nicht alle Nachrichten. Deswegen sollen sie lieber aufgeteilt sein, damit
jeder jede Nachricht lesen kann.

### 3.2 Lebensdauer

Wenn die Instanz stoppt in der Queue, dann wird sie auch dann automatisch gelöscht, weil sich dann
immer mehr Nachrichten in der Queue ansammeln bei RabbitMQ, deswegen werden sie gelöscht.

### 3.3 Grenze

Die Queue darf höchstens 1000 Nachrichten haben, und wenn ich jetzt z.B. 1010 oder 1001
Nachrichten schicke, dann kommt diese 1001, die eins, kommt rein, und die älteste Nachricht von
den anderen 1000, die wird einfach weggeworfen, weil für einen Live-Chat, z.B. wie auf WhatsApp,
ist die älteste Nachricht nicht so wichtig wie die neuste.

## 4. Abnahmekriterien

Man zählt, was wirklich passiert ist, und vergleicht es mit dem, was der Lehrer will. Wenn es
gleich ist, ist der Test bestanden. Das macht ein Skript automatisch.

| Nr | Erwartet (aus dem Auftrag) | Befehl, der misst |
|---|---|---|
| S1 | ein Lauf, alles grün | `mvn clean test` |
| S2 | alle Dienste laufen, kein Dienst veröffentlicht einen Port | `docker compose ps --format '{{.Service}} {{.State}} {{.Ports}}'`: alle `running`, nirgends `->` |
| S3 | beide Clients in Raum A bekommen alle 400, jede genau einmal, pro Absender in Reihenfolge; Client in Raum B keine | Clients mit `curl -sN "http://<IP der Instanz>:8080/stream?roomId=…"` in einem Container im Netz `chat-net`, Zeilen `id:` zählen, doppelte zählen, Reihenfolge pro Absender prüfen |
| S4 | vorher genau 2 Queues am Fanout, je 1 Consumer; nach höchstens 30 s nur noch 1; `chat.persist` und `chat.dlq` bleiben | `rabbitmqctl list_bindings source_name destination_name` (Queues an `chat.delivery` zählen) und `rabbitmqctl list_queues name consumers` |
| S5 | alle 100 nach höchstens 5 s beim Client; nach dem Neustart alle 100 in der Tabelle | `id:` beim Client zählen; `psql -c "SELECT count(*) FROM message WHERE content LIKE 'S5 %'"` |
| S6 | Queue der angehaltenen Instanz nie über 1000; Client an der anderen Instanz bekommt alle 5000; nach dem Fortsetzen stellt sie wieder zu | `rabbitmqctl list_queues name messages` mehrmals pro Sekunde, grösster Wert; `id:` beim Client zählen; neue Nachricht nach dem Fortsetzen |
| S7 | genau die 50 fehlenden, in Reihenfolge, keine doppelt, danach wieder live | Neuverbindung mit `curl -H "Last-Event-ID: <letzte ID>"`, `id:` zählen und vergleichen |
| S8 | keine Streams, Kommentar über jeder Klasse und Methode, `.env` nicht im Repo | `grep -rnE --exclude-dir=target 'java\.util\.stream\|\.stream\(\|parallelStream\|Stream\.of\|Collectors\.' chat-service/` (keine Ausgabe); `bash scripts/kommentare.sh chat-service/src` (keine Ausgabe); `git ls-files .env` und `git log --all --format=%h -- .env` (keine Ausgabe) |

---

Text von mir. Claude hat gegengelesen, einzelne falsche Wörter korrigiert und die Tabelle mit den
Befehlen in Abschnitt 4 geliefert.
