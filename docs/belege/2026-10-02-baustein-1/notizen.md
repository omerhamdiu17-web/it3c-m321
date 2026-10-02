# Notizen zu Baustein 1 (02.10.2026)

Was beim Umsetzen von [`plan-web-gateway.md`](../../plan-web-gateway.md) vom Plan abweicht, jede
Falle und jede Überraschung, je Aufgabe. Diese Notizen kommen später in den Plan (Häkchen, Links,
Fallen). Die Belege liegen im selben Ordner: `task-NN-rot.txt` und `task-NN-gruen.txt`.

## Für alle Aufgaben: geänderter Ablauf wegen des Abgabetermins

- **Rot und Grün lokal statt Probe-Branches.** Es gibt keine Branches `probe-tN`. Auch Tests mit
  Containern laufen rot und grün auf dem eigenen Rechner (Docker Desktop, Testcontainers). Befehl,
  Branch, Commit und der wichtige Ausschnitt der Ausgabe stehen im Beleg der Aufgabe.
- **Der Plan ist nicht im Commit der Aufgabe.** Ein anderer Agent schreibt `docs/plan-web-gateway.md`
  gleichzeitig weiter (Task 3 ff.). Statt der Häkchen im Plan gibt es diese Notizen. Gestagt werden
  nur ausdrücklich genannte Pfade.
- **CI nicht synchron abwarten.** Nach jedem Commit wird `baustein/web-gateway` gepusht; die Nummer
  des Laufs steht hier beim nächsten Commit. Vor dem nächsten Push wird geprüft, ob der vorige Lauf
  grün war.

## Task 1: Abnahme von Bewertung 1 startet nur ihre vier Dienste

| Was | Warum | Beleg |
|---|---|---|
| Roter Nachweis der Abnahme lokal, nicht als Probelauf `probe-t1` in GitHub Actions | geänderter Ablauf (oben) | `task-01-rot.txt`, Teil B |
| Rot nur für S2: Kopie des alten Skripts unter `target/abnahme-nur-s2.sh`, in der nur die sechs Aufrufe `scenario_s3` bis `scenario_s8` fehlen | Der Dummy betrifft nur S2; ein voller roter Lauf hätte danach noch S3 bis S8 durchgespielt, ohne mehr zu zeigen | `task-01-rot.txt`, Teil B (mit `diff`) |
| Dummy-Dienst (`busybox:1.36`, Port `127.0.0.1:18080:80`) nur lokal in `docker-compose.yml`, nie committet; danach wieder entfernt, `git diff --quiet -- docker-compose.yml` ohne Unterschied | wie im Plan: Was nur der Probe dient, wird nicht übernommen | `task-01-rot.txt`, `task-01-gruen.txt` |
| Zwei grüne Läufe statt einem: zuerst das neue Skript **mit** Dummy (Plan Schritt 7), dann der Endstand **ohne** Dummy | Der erste zeigt, dass der Dummy nie startet, auch nicht in S6; der zweite prüft genau das, was committet wird | `task-01-gruen.txt`, Teil B und C |
| Rote S2-Meldung wörtlich wie im Plan erwartet: `S2  FAIL gemessen: laufend: batch-writer chat-service dummy postgres rabbitmq \| veröffentlichte Ports: 1 \| …` | — | `task-01-rot.txt` |
| **Beobachtung S6:** Mit `up -d --scale batch-writer=2 batch-writer` entsteht nur `batch-writer-2`. Der `chat-service` wird **nicht** mehr neu erstellt (bis Bewertung 1 tat das erste `up` ohne `--build` das, laut dem alten Kommentar im Skript und spec-batch-writer.md 6, S6). Der neue Kommentar im Skript sagt genau das; `wait_until 120 chat_service_answers` bleibt als Sicherheit | — | `task-01-gruen.txt`, Ausgabe von S6 |
| `env-pruefen.sh` zusätzlich mit Windows-Zeilenenden geprüft (`.env` und `.env.example` mit CRLF): richtige Exit-Codes, kein CR im ausgegebenen Namen | Falle unter Windows: ein CR hinge sonst am Schlüssel | `task-01-gruen.txt`, Teil A |
| Die echte `.env` hat alle Schlüssel aus `.env.example` (`bash scripts/env-pruefen.sh` → Exit 0, nur der Exit-Code wurde ausgegeben) | Die `.env` enthält Geheimnisse und wird nie ausgegeben | `task-01-gruen.txt`, Teil A |
| **CI (Schritt 10):** Commit `000280a`, Lauf [37012599893](https://github.com/omerhamdiu17-web/it3c-m321/actions/runs/37012599893): `maven`, `images` und `abnahme` grün. `abnahme` zeigt `== S2: frischer Start, .env aus .env.example, docker compose up -d --build rabbitmq chat-service postgres batch-writer`, S2 bis S8 `PASS` | erster Push von `baustein/web-gateway` | Lauf 37012599893 |

## Task 2: Modul web-gateway anlegen

| Was | Warum | Beleg |
|---|---|---|
| Roter Nachweis lokal in zwei Teilen statt Probelauf `probe-t2`: Teil A `mvn -q -pl web-gateway test` (nur die Testdateien da), Teil B `docker compose build` mit neuem Eltern-POM, aber ohne die `COPY`-Zeilen | geänderter Ablauf (oben) | `task-02-rot.txt` |
| Beide roten Meldungen wörtlich wie im Plan: `Could not find the selected project in the reactor: web-gateway` und für beide Images `Child module /build/web-gateway of /build/pom.xml does not exist` | — | `task-02-rot.txt` |
| **`web-gateway/Dockerfile` schon in Task 2** (Maven- und JRE-Stufe, kopiert alle drei Modul-POMs), nicht erst in Task 8 | Auftrag für diese Aufgabe. Damit es nicht nur behauptet ist (Einwand im Plan), lokal mit `docker build -f web-gateway/Dockerfile .` gebaut und der Container kurz gestartet. Der Job `images` baut es erst, wenn der Dienst in `docker-compose.yml` steht (Task 8). Task 8 muss das Dockerfile also nicht mehr anlegen, Task 9 setzt die Node-Stufe davor | `task-02-gruen.txt` |
| **Plan Schritt 7 erwartet im Protokoll `Using generated security password`. Die Zeile erscheint nicht.** Der Bedingungsbericht (`-Ddebug=true`) sagt warum: `UserDetailsServiceAutoConfiguration` greift nicht, weil `ClientRegistrationRepository` und `OpaqueTokenIntrospector` auf dem Klassenpfad liegen (`spring-boot-starter-oauth2-client` und `-resource-server`). Die Standard-Filterkette (`SpringBootWebSecurityConfiguration`) greift trotzdem: Bis Task 5 verlangt jede Anfrage eine Anmeldung, es gibt aber keinen Benutzer, mit dem sie gelänge | Überraschung; am Test ändert das nichts | `task-02-gruen.txt` |
| **Falle: `mvn clean` im Wurzelordner löscht auch `./target/`** (das Eltern-POM ist selbst ein Projekt). Damit verschwinden `target/env-test/` aus Task 1 und alles, was ein Skript dort ablegt. Prüfdateien und Protokolle für Belege deshalb nicht nur unter `target/` aufbewahren | beim Bauen gefunden: die Zwischenprotokolle des roten Laufs lagen dort, ihre Ausschnitte standen zum Glück schon im Beleg | — |
| `cp .env.example .env` vor `docker compose build` entfällt lokal | Die lokale `.env` ist da und hat alle Schlüssel (`task-01-gruen.txt`) | — |
| Grün geprüft: `mvn -q -pl web-gateway test` (1 Test), `mvn -B clean test` über alle Module (14 + 29 + 1 = 44 Tests, 0 Fehler), `docker compose build` (beide Images mit der neuen `COPY`-Zeile), `docker build` des Gateways (Build-Kontext 4,33 kB, Start in 3,4 s, 0 Zeilen mit `WARN`/`ERROR`), `bash scripts/kommentare.sh web-gateway/src` → Exit 0, kein «stream» in `web-gateway/` (auch nicht in `pom.xml` und `Dockerfile`), kein `->` und kein `::` | Auftrag und Globale Vorgaben | `task-02-gruen.txt` |
| Gegenprobe zur Kommentarprüfung: In einer Kopie von `web-gateway/src` ohne den Javadoc über `main` meldet `kommentare.sh` genau diese Zeile und endet mit Exit 1 | Exit 0 allein zeigt nicht, dass das Skript die neuen Dateien überhaupt prüft | `task-02-gruen.txt`, Teil E |
| Zwischen den Commits von Task 1 und Task 2 liegt `96c103e` (`docs: Spezifikation des Gateways, Widersprüche aus dem Umsetzungsplan bereinigt`, nur `docs/spec-web-gateway.md`) vom Agenten, der den Plan schreibt. Er kommt mit dem Push von Task 2 auf GitHub | gleichzeitiges Arbeiten auf demselben Branch; berührt weder Task 1 noch Task 2 | `git log` |
