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
