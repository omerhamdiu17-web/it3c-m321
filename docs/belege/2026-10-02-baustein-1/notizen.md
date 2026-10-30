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
| **CI (Schritt 13):** Commit `45f917f`, Lauf [37013934257](https://github.com/omerhamdiu17-web/it3c-m321/actions/runs/37013934257): `maven`, `images` und `abnahme` grün. Der Plan-Commit `9665250` (Task 3 bis 10) danach ebenso: Lauf [37018493213](https://github.com/omerhamdiu17-web/it3c-m321/actions/runs/37018493213) | nachgetragen beim nächsten Commit, wie vereinbart | Läufe 37013934257, 37018493213 |

## Fortsetzung am 30.10.2026: anderer Rechner, Grundlinie

Ab Task 3 läuft die Umsetzung auf einem anderen Rechner. Die Belege bleiben im Ordner
`2026-10-02-baustein-1/`, damit Baustein 1 an einer Stelle steht; das Datum steht in jedem Beleg.

| Was | Warum | Beleg |
|---|---|---|
| **Rechner:** macOS 27.2 auf Apple Silicon (arm64), OrbStack 2.2.5 mit Docker 29.8.2 (linux/arm64) und Compose v5.5.0, OpenJDK 21.0.13 und Maven 3.10.0 aus Homebrew. Bisher Windows 11, Git Bash, Docker Desktop, Maven 3.9.16 | Java und Maven fehlten auf diesem Rechner und wurden mit `brew install openjdk@21 maven` installiert. `JAVA_HOME` zeigt in jeder Sitzung auf `/opt/homebrew/opt/openjdk@21` | `task-03-rot.txt` (Kopf) |
| Die lokale `.env` ist eine frische Kopie von `.env.example` | Auf diesem Rechner gab es keine `.env` | — |
| **Grundlinie** vor Task 3: `mvn -B clean test` → `chat-service` 14 und `batch-writer` 29 Tests grün (wie am 02.10.2026), Dauer 3:02 min. Im `web-gateway` lief `WebGatewayApplicationTest` grün; dazu schon die 12 Tests von `RealmImportIntegrationTest` rot | Die Testdateien von Task 3 entstanden, während der Lauf noch im `chat-service` war; Maven hat sie beim Übersetzen des `web-gateway` mitgenommen. Das Rot ist dasselbe wie in `task-03-rot.txt` | Lauf lokal, 15:35 bis 15:38 |
| `MSYS_NO_PATHCONV=1` und die Regel «kein `/dev/null` für curl» stammen von Windows. Unter macOS sind sie wirkungslos, aber unschädlich; die Skripte bleiben, wie sie sind | Dieselben Skripte laufen weiter unter Windows, macOS und Linux (CI) | — |

## Task 3: Realm chat als JSON-Import für Keycloak

| Was | Warum | Beleg |
|---|---|---|
| **Rot anders als erwartet:** Der Plan erwartete einen Fehler beim Start des Containers, weil `../keycloak/realm-chat.json` fehlt. Testcontainers kopiert eine fehlende Datei aber stillschweigend nicht (keine Warnung im Protokoll). Keycloak startet ohne Realm `chat`, und alle 12 Tests scheitern mit `404` bzw. `{"error":"Realm does not exist"}` | Überraschung. Der Grund des Rots ist trotzdem der richtige: Der Realm fehlt | `task-03-rot.txt` |
| **Falle: Keycloak setzt Secure-Cookies, wenn man es unter `localhost` anfragt.** Unter `localhost` und `127.0.0.1` kommen `AUTH_SESSION_ID`, `KC_AUTH_SESSION_HASH` und `KC_RESTART` mit `Secure; SameSite=None`, unter `keycloak:8080` mit `SameSite=Lax` ohne `Secure`. E4 sah `Lax`, weil dort `exp-kc:8080` angefragt wurde. Der `CookieManager` des JDK schickt Secure-Cookies nur über `https`; ohne sie antwortet Keycloak auf das Login-Formular mit `400`. Die sechs Tests, die das Formular abschicken, waren deshalb zuerst rot | curl und Browser behandeln `http://localhost` als sichere Umgebung und schicken die Cookies mit, deshalb fiel es in E1 bis E6 nicht auf | `task-03-gruen.txt`, Teil A und B |
| **Abweichung vom Plan:** `TestBrowser` hat zusätzlich die verschachtelte Klasse `LocalhostCookieHandler`. Für `http://localhost` fragt sie den `CookieManager` mit der `https`-Adresse, damit Secure-Cookies mitgehen, wie im Browser; gespeichert wird unverändert. Die übrigen drei Testdateien und die Realm-Datei stehen wörtlich wie im Plan | Der Testbrowser soll sich wie ein Browser verhalten (sein Javadoc). Die Regel trifft auch Task 6: Dort spricht der Proxy Keycloak im Test unter `localhost:<Port>` an, und dieselben Cookies kämen über das Gateway zum Testbrowser | `task-03-gruen.txt`, Teil B |
| **Für den Betrieb:** Hinter dem Gateway sieht Keycloak den Host `keycloak:8080`, sofern der Proxy den `Host`-Header nicht durchreicht; dann sind die Cookies `Lax` ohne `Secure`. Browser nähmen auch die Secure-Variante an, denn `http://localhost:8080` gilt bei ihnen als sicher. Welche Variante im Stack ankommt, misst W3 bzw. W12 | offen bis Task 10 | — |
| **P2 bestanden:** jeder Port bei `http://127.0.0.1/callback` (53682 und 61234) | Spezifikation 6.5 | `task-03-gruen.txt`, Teil D |
| **P3 bestanden:** `${PUBLIC_URL}` auch in der Post-Logout-URI ersetzt, fremdes Ziel → `400` | Spezifikation 6.5 | `task-03-gruen.txt`, Teil D |
| **P4 bestanden:** `sub` = feste `id` aus der Realm-Datei, im ID- und Access-Token | Spezifikation 6.5 | `task-03-gruen.txt`, Teil D |
| **P7 entschieden:** Keycloak 26.7.3 gibt über `admin-cli` im Realm `chat` ein Token heraus (`200`, `azp=admin-cli`). Das Gateway muss es an `azp` ablehnen; das prüfen in Task 7 `BearerTokenTest.tokenFromAdminCliIsRejected` und `LoginIntegrationTest.adminCliTokenIsRejected` | Spezifikation 2.6 und 6.5: «Das Token ist dank `azp` wertlos» | `task-03-gruen.txt`, Teil D |
| Keycloak ist auf diesem Rechner nach 8 bis 10 s bereit, nicht nach 32 s wie in E1 | Messung am Rande; `start_period: 90s` und 3 min Geduld im Test bleiben für langsame Runner | Protokolle der Testläufe |
| Grün: `RealmImportIntegrationTest` 12 Tests, Modul mit `WebGatewayApplicationTest` 13 Tests, Kommentarprüfung Exit 0, kein «stream», kein `->`, kein `::`; in der Realm-Datei nur Platzhalter | Plan Schritt 5 und Globale Vorgaben | `task-03-gruen.txt`, Teil C und E |
