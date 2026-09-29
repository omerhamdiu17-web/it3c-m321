# batch-writer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Ziel:** Der `batch-writer` holt jede Nachricht aus `chat.persist`, schreibt sie stapelweise mit einem INSERT pro Stapel dauerhaft in die Tabelle `message` und bestätigt sie erst nach dem COMMIT. Er übersteht doppelte Nachrichten, einen Rückstau, mehrere Instanzen und einen Datenbank-Ausfall ohne Verlust.

**Architektur:** Schichtung wie beim `chat-service`: `config` richtet beim Start die Queues und das Lesen in Stapeln ein, `service` liest und bestätigt, `repository` schreibt, `dto` ist die eigene Kopie des Vertrags. Der Listener kennt kein SQL, das Repository kein RabbitMQ. Das Schema liegt nicht im Dienst, sondern in `postgres/init` und wird von PostgreSQL beim ersten Start ausgeführt.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP 3.2 (Stapel-Listener), Spring `JdbcTemplate`, PostgreSQL 16, RabbitMQ 3.13, JUnit 5, Testcontainers, Maven Multi-Modul, GitHub Actions.

**Spec:** [`spec-batch-writer.md`](spec-batch-writer.md) — Vertrag (2), Verhalten und Fehlerfälle (3), Datenmodell und Konfiguration (4), Abweichungen von PLANUNG.md (5), Abnahmekriterien (6).

## Globale Vorgaben

Diese Punkte gelten für **jede** Aufgabe in diesem Plan:

- **Java 21**, Spring Boot **3.5.16** (Eltern-POM des Lehrers, unverändert).
- **Code auf Englisch** — Klassen, Methoden, Variablen, Dateinamen und **Log-Meldungen**. **Alles andere auf Deutsch** — Kommentare, Javadoc, Commit-Messages, Doku.
- **Über jeder Klasse und jeder Methode ein Kommentar**, der erklärt, *warum* es sie gibt, auch über `main`, Testmethoden, `@BeforeEach` und `@Bean`. Der Javadoc steht über den Annotationen.
- **Keine Streams und nichts, was so heisst.** `for`-Schleifen; auch kein `InputStream` und das Wort nicht im Kommentar (Szenario S8 sucht danach).
- **Keine verschachtelten Aufrufe.** Ein Ergebnis pro Zeile, in eine benannte Variable. Gilt auch in Tests.
- **Keine Lambdas und keine anonymen Klassen**, keine Mock-Frameworks: Tests arbeiten gegen echte Container oder mit handgeschriebenem Code.
- **Keine Interfaces mit einer einzigen Implementierung**, keine Abstraktion auf Vorrat.
- **Lombok** für `@Slf4j` und `@RequiredArgsConstructor`. Datenklassen sind `record`.
- **Kein `ports:`-Eintrag** und kein `container_name` in `docker-compose.yml`.
- **Keine Geheimnisse im Repository.** Zugangsdaten kommen aus `.env`, im Repo steht nur `.env.example`.
- **Jeder Commit endet mit dieser Zeile:**
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- **Voraussetzung:** Docker läuft (Testcontainers startet echte RabbitMQ- und PostgreSQL-Container), und `docs/spec-batch-writer.md` ist committet.

## Prüfschwerpunkte

Fälle, die kein Szenario direkt prüft, die aber jemanden treffen würden, der den Dienst benutzt. Jeder hat einen Test in der Aufgabe, die den Code dazu baut:

1. **Umlaute und Emoji im Text** kommen unverändert in der Datenbank an → Task 5, `storesAllFieldsUnchanged`.
2. **Nanosekunden in `sentAt`** (so schickt sie der `chat-service`) werden auf Mikrosekunden gerundet und nicht abgelehnt → Task 5, `storesAllFieldsUnchanged`; Task 4, `readsCapturedMessageFromBodyAlone`.
3. **Ein Zeitpunkt mit anderer Zeitzone** (`+02:00` statt `Z`) ist derselbe Zeitpunkt → Task 4, `readsTimestampWithOffset`.
4. **Ein zusätzliches, unbekanntes Feld** im JSON bricht nichts → Task 4, `ignoresUnknownField`.
5. **Der batch-writer startet, bevor RabbitMQ oder PostgreSQL bereit sind**, und stürzt nicht ab → Task 2, `BatchWriterApplicationTest`.

## Abgrenzung

| Bewusst **nicht** in diesem Plan | Warum |
|---|---|
| Historie lesen, Räume, Mitgliedschaften | Nicht Teil der Aufgabe (Spezifikation 1.3). Deshalb auch kein Fremdschlüssel auf `room` |
| Keycloak, web-gateway, load-generator | Nicht Teil der Aufgabe |
| Änderungen am Verhalten des `chat-service` | Er ist die Ausgangslage. Einzige Änderung: eine `COPY`-Zeile in seinem `Dockerfile`, ohne die sein Image nicht mehr baut (Task 2) |
| Eigener Datenbank-Benutzer mit minimalen Rechten | Zurückgestellt (Spezifikation 1.3) |
| Quorum-Queue mit Zählung der Zustellversuche | Würde die Queue-Definition des `chat-service` ändern; wir unterscheiden nach Ursache (Spezifikation 5) |
| Flyway oder Schema aus dem Dienst | Schema entsteht in `postgres/init` (Spezifikation 4.2) |

## Dateistruktur

```
pom.xml                                  # + Modul batch-writer
chat-service/Dockerfile                  # + COPY batch-writer/pom.xml (sonst baut das Image nicht)
docker-compose.yml                       # + postgres, + batch-writer, + Volume chat-history, KEIN ports:
.env.example                             # + POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
.gitattributes                           # LF für *.sh und *.sql
.github/workflows/build.yml              # mvn clean test, Images bauen, Abnahme
postgres/init/01-schema.sql              # Tabelle message und Index
scripts/abnahme.sh                       # Szenarien S2 bis S8 nachgestellt
README.md                                # Stand, Starten, Testen, Abnahme
batch-writer/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/ch/benedict/m321/batchwriter/
    │   ├── BatchWriterApplication.java
    │   ├── config/
    │   │   ├── QueueNames.java               # die zwei Namen an genau einer Stelle
    │   │   └── RabbitConfig.java             # Queues wie im chat-service, Stapel-Listener
    │   ├── dto/
    │   │   └── ChatMessage.java              # eigene Kopie des Vertrags
    │   ├── repository/
    │   │   └── MessageRepository.java        # INSERT ... ON CONFLICT DO NOTHING, eine Transaktion
    │   └── service/
    │       ├── ChatMessageReader.java        # Body → ChatMessage, Header egal
    │       ├── ReceivedMessage.java          # Nachricht + Lieferschein (deliveryTag)
    │       └── MessageBatchListener.java     # lesen, schreiben, bestätigen, zurückgeben
    ├── main/resources/application.yml
    └── test/java/ch/benedict/m321/batchwriter/
        ├── BatchWriterApplicationTest.java
        ├── TestDatabase.java                 # PostgreSQL-Testcontainer mit dem echten Schema
        ├── SchemaIntegrationTest.java
        ├── config/RabbitConfigIntegrationTest.java
        ├── repository/MessageRepositoryIntegrationTest.java
        └── service/
            ├── ChatMessageReaderTest.java
            ├── MessageBatchListenerIntegrationTest.java
            └── DatabaseOutageIntegrationTest.java
```

**Wer wen kennt** — und zwar nur in dieser Richtung:

```
RabbitMQ ──► MessageBatchListener ──ruft auf──► ChatMessageReader      (JSON → ChatMessage)
                      │
                      └────────ruft auf──► MessageRepository ──► PostgreSQL
                      │
                      └── kennt dto (ChatMessage, ReceivedMessage) und config (QueueNames, RabbitConfig)
```

Testklassen heissen `...Test` oder `...IntegrationTest`, damit Surefire sie ohne weiteres Plugin findet (wie beim `chat-service`).

## Arbeitsweise

- **Test zuerst.** Jede Aufgabe beginnt mit einem Test, der fehlschlägt. Erst dann kommt der Code.
- **Lokal und im CI.** Übersetzen und die Tests ohne Container laufen lokal mit Maven. Die Tests mit Containern laufen ab Task 1 bei jedem Push in GitHub Actions. Ein Stand geht erst auf `main`, wenn dieser Lauf grün ist.
- **Ein Thema pro Commit.** Jede Aufgabe endet mit genau einem Commit, die Message steht im Plan.
- **Plan und `git log` bleiben deckungsgleich.** Das Häkchen einer Aufgabe und jede beim Bauen entdeckte Falle kommen **im selben Commit** wie die Aufgabe in diesen Plan.

## Reihenfolge und warum

Von innen nach aussen: erst der Prüfstand, dann was ohne Broker testbar ist, dann der Weg mit echtem RabbitMQ, dann die Fehlerwege, zuletzt Betrieb und Abnahme.

| # | Aufgabe | Warum an dieser Stelle |
|---|---|---|
| 1 | CI: Build, Tests und Images bei jedem Push | Ohne Prüfstand wäre jeder weitere Schritt nur behauptet. Ab hier prüft jeder Push alle Tests mit echten Containern |
| 2 | Modul `batch-writer` anlegen | Alles Weitere braucht ein übersetzbares Modul. Die `COPY`-Zeile im `chat-service`-Dockerfile gehört dazu, sonst bricht dessen Image im selben Moment |
| 3 | Tabelle `message` als Init-Skript | Das Schema ist der innerste Teil des Vertrags mit der Abnahme. Es ist mit reinem JDBC testbar, ganz ohne Dienst |
| 4 | Nachricht aus dem JSON lesen | Der Vertrag mit dem `chat-service`. Er ist ohne Container als Unit-Test prüfbar und wird für S5 gebraucht |
| 5 | Stapel mit einem INSERT speichern | Braucht Schema (3) und `ChatMessage` (4). Hier entstehen Duplikat-Sicherheit und «ein Stapel = eine Transaktion» |
| 6 | Queues wie im `chat-service` anlegen | Bevor der Listener an der Queue hängt, muss sicher sein, dass seine Deklaration zu der des `chat-service` passt |
| 7 | Stapel lesen und nach dem COMMIT bestätigen | Setzt 4, 5 und 6 zusammen. Erst jetzt gibt es den Weg von der Queue in die Tabelle (S3, S4, S5) |
| 8 | Bei Datenbank-Ausfall zurück in die Queue | Ein Fehlerweg setzt den Normalweg voraus. S7 ist das Szenario mit dem grössten Risiko, deshalb zuerst |
| 9 | Abgelehnte Einzelnachricht in die DLQ | Der seltenere Fehlerweg. Er setzt die Fehlerbehandlung aus 8 voraus, sein `catch` kommt darüber |
| 10 | Postgres und batch-writer in docker-compose | Erst wenn der Dienst allein richtig arbeitet, lohnt der Betrieb im Stack (S2) |
| 11 | Abnahmeskript S2 bis S8 | Braucht den ganzen Stack. Prüft alles so, wie die Abnahme es tut |
| 12 | README | Beschreibt, was es jetzt wirklich gibt, deshalb zuletzt |

---

## Task 1: CI — Build, Tests und Images bei jedem Push

**Warum an dieser Stelle:** Ohne Prüfstand wäre jeder weitere Schritt nur behauptet. Ab hier laufen bei jedem Push alle Tests mit echten Containern, und alle Images werden gebaut.

**Dateien:**
- Anlegen: `.github/workflows/build.yml`

**Schnittstellen:**
- Verbraucht: nichts
- Stellt bereit: Workflow `build` mit den Jobs `maven` (Szenario S1: `mvn -B clean test`) und `images` (`docker compose build`). Task 10 und 11 ergänzen Jobs.

- [x] **Schritt 1: Workflow anlegen**

`.github/workflows/build.yml`

```yaml
# Baut und testet das Projekt bei jedem Push.
#
# Warum: «Läuft» sagen wir erst, wenn es wirklich gelaufen ist (CLAUDE.md).
# Die Maschinen von GitHub haben Java und Docker. Hier laufen dieselben Tests
# wie lokal mit "mvn clean test", mit echten Containern über Testcontainers.
name: build

on:
  push:
  workflow_dispatch:

jobs:

  # Szenario S1: alle Tests aller Module in einem Lauf.
  maven:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: mvn clean test
        run: mvn -B clean test

  # Alle Images bauen, genau wie "docker compose up --build".
  # Findet z.B. eine fehlende COPY-Zeile in einem Dockerfile.
  images:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - name: .env aus den Beispielwerten
        run: cp .env.example .env
      - name: docker compose build
        run: docker compose build
```

- [x] **Schritt 2: Pushen und den Lauf prüfen**

Ausführen: `git push`, dann `gh run watch`
Erwartet: beide Jobs grün. `maven` zeigt die Tests des `chat-service` (`Tests run: …, Failures: 0, Errors: 0`), `images` baut `chat-service`.

- [x] **Schritt 3: Committen**

```bash
git add .github/workflows/build.yml
git commit -m "ci: Build, Tests und Images bei jedem Push" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 2: Modul batch-writer anlegen

**Warum an dieser Stelle:** Alles Weitere braucht ein übersetzbares Modul. Die `COPY`-Zeile im `chat-service`-Dockerfile gehört in denselben Commit, denn sobald das Eltern-POM das neue Modul nennt, bricht der Image-Bau des `chat-service`.

**Dateien:**
- Ändern: `pom.xml` (Modulliste)
- Ändern: `chat-service/Dockerfile` (eine `COPY`-Zeile)
- Anlegen: `batch-writer/pom.xml`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/BatchWriterApplication.java`
- Anlegen: `batch-writer/src/main/resources/application.yml`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/BatchWriterApplicationTest.java`

**Schnittstellen:**
- Verbraucht: Eltern-POM des Lehrers
- Stellt bereit: Paketwurzel `ch.benedict.m321.batchwriter`, Artefakt `ch.benedict.m321:batch-writer:0.1.0-SNAPSHOT`, Spring-Kontext ohne Webserver, der ohne Broker und ohne Datenbank startet. Umgebungsvariablen `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (Spezifikation 4.4).

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/BatchWriterApplicationTest.java`

```java
package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Prüft, dass der Spring-Kontext hochfährt, OHNE dass RabbitMQ oder
 * PostgreSQL erreichbar sind.
 *
 * Genau darauf verlässt sich der Betrieb: startet der batch-writer, bevor
 * RabbitMQ Verbindungen annimmt, darf er nicht abstürzen, sondern soll es
 * wieder versuchen. Die Datenbank braucht er erst beim ersten Stapel.
 */
@SpringBootTest
@DirtiesContext
class BatchWriterApplicationTest {

    /**
     * Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring eine
     * Exception und der Test wird rot. @DirtiesContext schliesst ihn danach
     * wieder, damit der Listener nicht weiter nach einem Broker sucht.
     */
    @Test
    void contextLoadsWithoutBrokerAndDatabase() {
    }
}
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test`
Erwartet: Fehlschlag — `Could not find the selected project in the reactor: batch-writer`, das Modul gibt es noch nicht.

- [x] **Schritt 3: Modul im Eltern-POM eintragen**

`pom.xml`, die Modulliste:

```xml
    <!-- Weitere Dienste kommen hier dazu: web-gateway, load-generator. -->
    <modules>
        <module>chat-service</module>
        <module>batch-writer</module>
    </modules>
```

- [x] **Schritt 4: Modul-POM anlegen**

`batch-writer/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ch.benedict.m321</groupId>
        <artifactId>it3c-m321</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>batch-writer</artifactId>
    <name>batch-writer</name>
    <description>Einziger Schreiber in die Datenbank: holt Nachrichten stapelweise aus chat.persist</description>

    <dependencies>
        <!-- RabbitMQ: liest die Queue chat.persist -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <!-- Datenbank: JdbcTemplate und Transaktionen, bewusst kein JPA -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- JSON lesen: der Vertrag zwischen den Diensten ist das JSON -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-json</artifactId>
        </dependency>

        <!-- Lombok erzeugt Logger und Konstruktoren beim Uebersetzen.
             "optional" heisst: nur wir brauchen es, niemand der uns benutzt. -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- Startet im Test ein echtes RabbitMQ und ein echtes PostgreSQL -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <!-- Lombok wird nur zum Uebersetzen gebraucht und
                             gehoert nicht ins ausgelieferte Jar. -->
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [x] **Schritt 5: Hauptklasse anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/BatchWriterApplication.java`

```java
package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Dieser Dienst ist der EINZIGE, der Chat-Nachrichten in die Datenbank
 * schreibt. Er holt sie stapelweise aus der Queue chat.persist, damit aus
 * 1'667 Nachrichten pro Sekunde rund drei Transaktionen werden
 * (PLANUNG.md, Abschnitt 4.1).
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Startet Spring. Mehr passiert hier nicht: den Rest erledigen die
     * Konfiguration und der Listener, die Spring selbst findet.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
```

- [x] **Schritt 6: Konfiguration anlegen**

`batch-writer/src/main/resources/application.yml` — `socketTimeout` und der Verbindungspool kommen erst in Task 8 dazu, wo sie gebraucht werden.

```yaml
spring:
  application:
    name: batch-writer
  main:
    # Kein Webserver: niemand ruft den batch-writer auf. So belegt er
    # keinen Port und startet schneller.
    web-application-type: none
  rabbitmq:
    # Im Docker-Netz heisst der Broker "rabbitmq". Beim Start ausserhalb
    # von Docker greift der Vorgabewert "localhost".
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  datasource:
    # reWriteBatchedInserts=true: der Treiber fasst die INSERTs eines Stapels
    #   zu mehrzeiligen INSERTs zusammen (bis 128 Zeilen je Anweisung).
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:chat}?reWriteBatchedInserts=true
    username: ${POSTGRES_USER:chat}
    password: ${POSTGRES_PASSWORD:chat}

logging:
  level:
    # Im Unterricht wollen wir jeden Schritt sehen.
    ch.benedict.m321: DEBUG
```

- [x] **Schritt 7: `chat-service/Dockerfile` ergänzen**

Nach `COPY chat-service/pom.xml chat-service/pom.xml`:

```dockerfile
# Maven liest ALLE Module aus dem Eltern-POM, auch die, die hier nicht
# gebaut werden. Deshalb muss auch das POM des batch-writer da sein.
COPY batch-writer/pom.xml batch-writer/pom.xml
```

> **Falle:** Ohne diese Zeile scheitert `docker compose up --build` am `chat-service`
> mit «Child module batch-writer … does not exist», obwohl am `chat-service` selbst nichts
> geändert wurde. `mvn -pl chat-service -am` liest das Eltern-POM, und das nennt jetzt zwei
> Module. Der CI-Job `images` aus Task 1 findet genau diesen Fehler.

- [x] **Schritt 8: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test`, dann pushen und `gh run watch`
Erwartet: `BatchWriterApplicationTest` grün, im CI zusätzlich `images` grün (baut den `chat-service` mit dem neuen Eltern-POM).

- [x] **Schritt 9: Committen**

```bash
git add pom.xml chat-service/Dockerfile batch-writer/pom.xml batch-writer/src
git commit -m "chore: Modul batch-writer anlegen" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 3: Tabelle message als Init-Skript für Postgres

**Warum an dieser Stelle:** Das Schema ist der innerste Teil des Vertrags mit der Abnahme (Tabelle `message`, Spalten aus PLANUNG.md 3.7). Es lässt sich mit reinem JDBC gegen ein echtes PostgreSQL prüfen, noch bevor es irgendeinen Code dafür gibt.

**Dateien:**
- Anlegen: `postgres/init/01-schema.sql`
- Anlegen: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/TestDatabase.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/SchemaIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: Modul aus Task 2
- Stellt bereit: Tabelle `message(id uuid PK, room_id uuid, sender_id varchar, sender_name varchar, content text, sent_at timestamptz)`, alle `NOT NULL`, Index `message_room_id_sent_at_idx`; `TestDatabase.createContainer()` → `PostgreSQLContainer<?>` mit genau diesem Schema und `reWriteBatchedInserts=true` in der JDBC-URL

- [x] **Schritt 1: Die Hilfsklasse für den Test-Container anlegen**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/TestDatabase.java`

```java
package ch.benedict.m321.batchwriter;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Ein PostgreSQL für die Tests, eingerichtet mit dem ECHTEN Schema.
 *
 * Die Datei postgres/init/01-schema.sql wird in den Container kopiert, an
 * dieselbe Stelle, an der docker-compose sie einbindet. So prüft jeder Test
 * das Schema mit, und es gibt keine zweite Kopie davon.
 */
public final class TestDatabase {

    /** Dasselbe Image wie in docker-compose.yml. */
    private static final String IMAGE = "postgres:16";

    /** Die Tests laufen im Ordner batch-writer, das Schema liegt eine Ebene höher. */
    private static final String SCHEMA_FILE = "../postgres/init/01-schema.sql";

    /** Hier sucht das Postgres-Image beim ersten Start nach Skripten. */
    private static final String INIT_SCRIPT = "/docker-entrypoint-initdb.d/01-schema.sql";

    /** Diese Klasse sammelt nur eine Hilfsmethode und wird nie erzeugt. */
    private TestDatabase() {
    }

    /**
     * Baut den Container. reWriteBatchedInserts steht wie im Betrieb in der
     * JDBC-URL, damit die Tests denselben Weg durch den Treiber nehmen.
     */
    public static PostgreSQLContainer<?> createContainer() {
        MountableFile schema = MountableFile.forHostPath(SCHEMA_FILE);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE);
        container.withCopyFileToContainer(schema, INIT_SCRIPT);
        container.withUrlParam("reWriteBatchedInserts", "true");
        return container;
    }
}
```

- [x] **Schritt 2: Den fehlschlagenden Test schreiben**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/SchemaIntegrationTest.java`

```java
package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Schema aus postgres/init/01-schema.sql gegen ein ECHTES
 * PostgreSQL. Die Abnahme misst an der Tabelle message mit den Spalten aus
 * PLANUNG.md 3.7, deshalb halten wir genau das hier fest.
 *
 * Ohne Spring: für das Schema braucht es nur eine JDBC-Verbindung.
 */
@Testcontainers
class SchemaIntegrationTest {

    /** PostgreSQL mit dem echten Schema, einmal für alle Tests dieser Klasse. */
    @Container
    static PostgreSQLContainer<?> postgres = TestDatabase.createContainer();

    /** Die sechs Spalten in ihrer Reihenfolge, mit Typ und "darf nicht leer sein". */
    @Test
    void createsMessageTableWithColumnsFromPlanning() throws SQLException {
        List<String> columns = queryFirstColumn("""
                SELECT column_name || ' ' || data_type || ' ' || is_nullable
                FROM information_schema.columns
                WHERE table_name = 'message'
                ORDER BY ordinal_position
                """);

        List<String> expected = List.of(
                "id uuid NO",
                "room_id uuid NO",
                "sender_id character varying NO",
                "sender_name character varying NO",
                "content text NO",
                "sent_at timestamp with time zone NO");
        assertEquals(expected, columns);
    }

    /** id ist der Primärschlüssel: nur so kann ON CONFLICT (id) Duplikate erkennen. */
    @Test
    void usesIdAsPrimaryKey() throws SQLException {
        List<String> keyColumns = queryFirstColumn("""
                SELECT key_column.column_name
                FROM information_schema.table_constraints table_constraint
                JOIN information_schema.key_column_usage key_column
                  ON key_column.constraint_name = table_constraint.constraint_name
                WHERE table_constraint.table_name = 'message'
                  AND table_constraint.constraint_type = 'PRIMARY KEY'
                """);

        List<String> expected = List.of("id");
        assertEquals(expected, keyColumns);
    }

    /** Kein Fremdschlüssel: Räume sind nicht Teil dieser Aufgabe (Spezifikation 4.1). */
    @Test
    void hasNoForeignKey() throws SQLException {
        List<String> foreignKeys = queryFirstColumn("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_name = 'message'
                  AND constraint_type = 'FOREIGN KEY'
                """);

        assertTrue(foreignKeys.isEmpty(), "unerwarteter Fremdschlüssel: " + foreignKeys);
    }

    /** Der Index für die Abfrage "die letzten 50 Nachrichten eines Raums". */
    @Test
    void hasIndexOnRoomAndTime() throws SQLException {
        List<String> definitions = queryFirstColumn("""
                SELECT indexdef
                FROM pg_indexes
                WHERE tablename = 'message'
                  AND indexname = 'message_room_id_sent_at_idx'
                """);

        assertEquals(1, definitions.size());
        String definition = definitions.get(0);
        assertTrue(definition.contains("(room_id, sent_at DESC)"), definition);
    }

    /** Führt eine Abfrage aus und liefert die erste Spalte jeder Zeile als Text. */
    private List<String> queryFirstColumn(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String password = postgres.getPassword();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                values.add(value);
            }
        }
        return values;
    }
}
```

- [x] **Schritt 3: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=SchemaIntegrationTest`
Erwartet: Fehlschlag — 3 von 4 Tests rot mit `but was: <[]>` bzw. `but was: <0>`: der Container startet ohne Schema, die Tabelle fehlt. `hasNoForeignKey` ist schon grün, denn ohne Tabelle gibt es auch keinen Fremdschlüssel; er schützt davor, dass später einer dazukommt.

- [x] **Schritt 4: Schema anlegen**

`postgres/init/01-schema.sql`

```sql
-- Das Schema des Chat-Verlaufs (PLANUNG.md, Abschnitt 3.7).
--
-- Postgres führt diese Datei genau einmal aus: beim ersten Start mit leerem
-- Datenverzeichnis, in der Datenbank POSTGRES_DB als POSTGRES_USER.
-- Für eine Änderung am Schema braucht es danach "docker compose down -v".
-- Die Tests des batch-writer benutzen genau diese Datei.

-- Eine Chat-Nachricht. Geschrieben wird sie nur vom batch-writer.
--
-- id kommt vom chat-service und ist der Primärschlüssel. Daran erkennt
-- "ON CONFLICT (id) DO NOTHING" eine Nachricht, die RabbitMQ ein zweites
-- Mal liefert (At-least-once, PLANUNG.md 3.6).
--
-- room_id hat bewusst KEINEN Fremdschlüssel: Räume sind nicht Teil dieses
-- Schritts. Ein Fremdschlüssel würde jede Nachricht an einen Raum, den die
-- Datenbank nicht kennt, in die Dead-Letter-Queue schicken.
--
-- varchar ohne Länge: der Vertrag kennt keine Höchstlänge, also setzt auch
-- die Datenbank keine.
CREATE TABLE message (
    id           uuid         PRIMARY KEY,
    room_id      uuid         NOT NULL,
    sender_id    varchar      NOT NULL,
    sender_name  varchar      NOT NULL,
    content      text         NOT NULL,
    sent_at      timestamptz  NOT NULL
);

-- Für die einzige Abfrage des späteren Lesepfads:
-- "die letzten 50 Nachrichten eines Raums".
CREATE INDEX message_room_id_sent_at_idx ON message (room_id, sent_at DESC);
```

> **Warum `information_schema` im Test:** Die Abnahme prüft die Tabelle mit den Spalten aus
> PLANUNG.md 3.7. Der Test hält genau das fest: Name, Typ und «darf nicht leer sein» jeder
> Spalte, den Primärschlüssel, den Index und dass es **keinen** Fremdschlüssel gibt.

> **Falle (beim Bauen gefunden, 29.09.2026):** Fehlt die Schema-Datei, meldet Testcontainers das
> nicht: der Container startet einfach ohne Tabelle. Deshalb prüft der Test den Inhalt des Schemas
> (Spalten, Primärschlüssel, Index) und nicht nur, ob der Container startet.

- [x] **Schritt 5: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=SchemaIntegrationTest`
Erwartet: 4 Tests grün. Der erste Lauf dauert länger, weil das Image `postgres:16` geladen wird.

- [x] **Schritt 6: Committen**

```bash
git add postgres/init/01-schema.sql batch-writer/src/test/java/ch/benedict/m321/batchwriter
git commit -m "feat: Tabelle message als Init-Skript für Postgres" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 4: Nachricht aus dem JSON lesen, ohne __TypeId__

**Warum an dieser Stelle:** Das ist der Vertrag mit dem `chat-service` (Spezifikation 2). Er lässt sich ohne Container als schneller Unit-Test prüfen, und Szenario S5 hängt daran, dass allein der Body zählt.

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/dto/ChatMessage.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/ChatMessageReader.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/ChatMessageReaderTest.java`

**Schnittstellen:**
- Verbraucht: Paketwurzel aus Task 2
- Stellt bereit:
  - `ChatMessage(UUID id, UUID roomId, String senderId, String senderName, String content, OffsetDateTime sentAt)`
  - `ChatMessageReader(ObjectMapper objectMapper)`, Spring-Bean; `ChatMessage read(byte[] body)` → `null`, wenn der Body kein passendes JSON ist oder ein Feld fehlt

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/ChatMessageReaderTest.java`

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Lesen des JSON, ohne Spring und ohne Broker.
 *
 * Die Beispielnachricht ist genau die, die am 29.09.2026 aus chat.persist
 * mitgeschnitten wurde (Spezifikation 2.4). Der Reader bekommt nur Bytes,
 * keine Header: das ist der Fall aus Szenario S5.
 */
class ChatMessageReaderTest {

    /** Der Body aus dem Mitschnitt, Zeichen für Zeichen. */
    private static final String CAPTURED_BODY = """
            {"id":"9813b68e-d290-44b1-b217-e43fa7a544db","roomId":"3f2b1c4e-0000-0000-0000-000000000001",\
            "senderId":"anna","senderName":"Anna Muster","content":"Hallo Vertrag",\
            "sentAt":"2026-09-29T13:38:12.974043374Z"}""";

    /** Der Reader mit einem ObjectMapper, wie ihn auch Spring Boot baut. */
    private final ChatMessageReader reader = createReader();

    /** Die mitgeschnittene Nachricht wird vollständig gelesen, allein aus dem Body. */
    @Test
    void readsCapturedMessageFromBodyAlone() {
        byte[] body = bytes(CAPTURED_BODY);

        ChatMessage message = reader.read(body);

        assertNotNull(message);
        UUID expectedId = UUID.fromString("9813b68e-d290-44b1-b217-e43fa7a544db");
        UUID expectedRoomId = UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001");
        OffsetDateTime expectedSentAt = OffsetDateTime.parse("2026-09-29T13:38:12.974043374Z");
        assertEquals(expectedId, message.id());
        assertEquals(expectedRoomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo Vertrag", message.content());
        OffsetDateTime sentAt = message.sentAt();
        assertTrue(expectedSentAt.isEqual(sentAt), "gelesen: " + sentAt);
    }

    /** Ein Zeitpunkt mit anderer Zeitzone ist derselbe Zeitpunkt. */
    @Test
    void readsTimestampWithOffset() {
        String json = CAPTURED_BODY.replace("2026-09-29T13:38:12.974043374Z", "2026-09-29T15:38:12.974043374+02:00");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNotNull(message);
        OffsetDateTime expectedSentAt = OffsetDateTime.parse("2026-09-29T13:38:12.974043374Z");
        OffsetDateTime sentAt = message.sentAt();
        assertTrue(expectedSentAt.isEqual(sentAt), "gelesen: " + sentAt);
    }

    /** Ein Feld, das der chat-service später vielleicht ergänzt, stört nicht. */
    @Test
    void ignoresUnknownField() {
        String json = CAPTURED_BODY.replace("{\"id\"", "{\"priority\":\"hoch\",\"id\"");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNotNull(message);
        assertEquals("Hallo Vertrag", message.content());
    }

    /** Fehlt ein Pflichtfeld, ist die Nachricht nicht speicherbar. */
    @Test
    void rejectsMissingField() {
        String json = CAPTURED_BODY.replace("\"content\":\"Hallo Vertrag\",", "");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Ein Feld mit dem Wert null zählt wie ein fehlendes Feld. */
    @Test
    void rejectsFieldWithNullValue() {
        String json = CAPTURED_BODY.replace("\"Hallo Vertrag\"", "null");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Eine id, die keine UUID ist, kann nie Primärschlüssel werden. */
    @Test
    void rejectsInvalidUuid() {
        String json = CAPTURED_BODY.replace("9813b68e-d290-44b1-b217-e43fa7a544db", "keine-uuid");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Text, der gar kein JSON ist. */
    @Test
    void rejectsBodyThatIsNoJson() {
        byte[] body = bytes("das ist kein JSON");

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Baut den Reader wie Spring Boot seinen ObjectMapper: mit Zeit-Typen, unbekannte Felder erlaubt. */
    private static ChatMessageReader createReader() {
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        return new ChatMessageReader(objectMapper);
    }

    /** Macht aus Text die Bytes, die im Body einer Nachricht stehen. */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=ChatMessageReaderTest`
Erwartet: Übersetzungsfehler — `ChatMessage` und `ChatMessageReader` gibt es noch nicht.

- [x] **Schritt 3: `ChatMessage` anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/dto/ChatMessage.java`

```java
package ch.benedict.m321.batchwriter.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie aus chat.persist kommt.
 *
 * Eigene Kopie des batch-writer: der Vertrag zwischen den Diensten ist das
 * JSON, nicht die Klasse des chat-service. Die Felder heissen wie im JSON und
 * entsprechen eins zu eins den Spalten der Tabelle message.
 *
 * sentAt ist ein OffsetDateTime und kein Instant wie im chat-service: der
 * PostgreSQL-Treiber kann OffsetDateTime an timestamptz binden, Instant nicht.
 *
 * @param id         vom chat-service vergeben, Primärschlüssel der Tabelle
 * @param roomId     der Raum der Nachricht
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename des Absenders
 * @param content    der Text der Nachricht
 * @param sentAt     die Server-Zeit des chat-service
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        OffsetDateTime sentAt) {
}
```

> **Falle:** Der PostgreSQL-Treiber kann `Instant` nicht binden (`setObject` kennt nur
> `OffsetDateTime` und `LocalDateTime`). Mit `Instant` scheiterte jeder INSERT mit SQLState
> 07006, und Spring meldete das als `BadSqlGrammarException`, nicht als Datenfehler. Deshalb
> ist `sentAt` hier ein `OffsetDateTime`.

- [x] **Schritt 4: `ChatMessageReader` anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/ChatMessageReader.java`

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Macht aus dem Body einer Nachricht eine ChatMessage.
 *
 * Gelesen wird NUR der Body. Header wie __TypeId__ oder content_type spielen
 * keine Rolle: __TypeId__ nennt eine Klasse des chat-service, die es hier
 * nicht gibt, und von Hand eingelegte Nachrichten haben ihn gar nicht
 * (Spezifikation 2.3).
 */
@Component
@RequiredArgsConstructor
public class ChatMessageReader {

    /** Der ObjectMapper von Spring Boot: kennt OffsetDateTime und ignoriert unbekannte Felder. */
    private final ObjectMapper objectMapper;

    /**
     * Liest eine Nachricht. Gibt null zurück, wenn der Body kein passendes
     * JSON ist oder ein Feld fehlt: eine solche Nachricht kann der
     * batch-writer nie speichern, egal wie oft er es versucht.
     */
    public ChatMessage read(byte[] body) {
        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            return null;
        }

        if (isComplete(chatMessage)) {
            return chatMessage;
        }
        return null;
    }

    /** Alle Spalten der Tabelle message sind NOT NULL, also muss jedes Feld da sein. */
    private boolean isComplete(ChatMessage chatMessage) {
        if (chatMessage == null) {
            return false;
        }
        return chatMessage.id() != null
                && chatMessage.roomId() != null
                && chatMessage.senderId() != null
                && chatMessage.senderName() != null
                && chatMessage.content() != null
                && chatMessage.sentAt() != null;
    }
}
```

- [x] **Schritt 5: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=ChatMessageReaderTest`
Erwartet: 7 Tests grün, ohne Container.

- [x] **Schritt 6: Committen**

```bash
git add batch-writer/src/main/java/ch/benedict/m321/batchwriter/dto \
        batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/ChatMessageReader.java \
        batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/ChatMessageReaderTest.java
git commit -m "feat: Nachricht aus dem JSON lesen, ohne __TypeId__" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 5: Stapel mit einem INSERT und ON CONFLICT DO NOTHING speichern

**Warum an dieser Stelle:** Braucht das Schema (Task 3) und `ChatMessage` (Task 4). Hier entstehen die zwei Eigenschaften, auf denen S4, S5 und S6 ruhen: Duplikate sind harmlos, und ein Stapel ist genau eine Transaktion.

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/repository/MessageRepository.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/repository/MessageRepositoryIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: Tabelle `message` (Task 3), `TestDatabase.createContainer()` (Task 3), `ChatMessage` (Task 4)
- Stellt bereit: `MessageRepository.insertBatch(List<ChatMessage> messages)` → `void`, eine Transaktion pro Aufruf; wirft `DataIntegrityViolationException`, wenn die Datenbank eine Zeile ablehnt, und eine andere `RuntimeException`, wenn sie nicht erreichbar ist

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/repository/MessageRepositoryIntegrationTest.java`

```java
package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.TestDatabase;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Bulk-INSERT gegen ein ECHTES PostgreSQL mit dem echten Schema.
 *
 * RabbitMQ läuft hier nicht. Der Listener sucht im Hintergrund vergeblich
 * nach einem Broker; das stört diese Tests nicht.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class MessageRepositoryIntegrationTest {

    /** Die Server-Zeit aus dem Mitschnitt vom 29.09.2026, mit Nanosekunden. */
    private static final String CAPTURED_SENT_AT = "2026-09-29T13:38:12.974043374Z";

    /** Das Zeichen NUL (Code 0): JSON erlaubt es, PostgreSQL kann es in text nicht speichern. */
    private static final String NUL = String.valueOf((char) 0);

    /** PostgreSQL mit dem echten Schema; @ServiceConnection setzt die Datenbank-Einstellungen. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.createContainer();

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test beginnt mit einer leeren Tabelle. */
    @BeforeEach
    void emptyMessageTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Drei Nachrichten, ein Aufruf, drei Zeilen. */
    @Test
    void storesWholeBatch() {
        ChatMessage first = messageWithContent("eins");
        ChatMessage second = messageWithContent("zwei");
        ChatMessage third = messageWithContent("drei");
        List<ChatMessage> batch = List.of(first, second, third);

        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(3, rows);
    }

    /**
     * Jedes Feld kommt unverändert an, auch Umlaute und Emoji. sent_at wird
     * auf Mikrosekunden gerundet, genauer speichert PostgreSQL nicht.
     */
    @Test
    void storesAllFieldsUnchanged() {
        ChatMessage message = messageWithContent("Grüezi mitenand 👋");
        List<ChatMessage> batch = List.of(message);

        messageRepository.insertBatch(batch);

        String row = jdbcTemplate.queryForObject(
                "SELECT room_id || '|' || sender_id || '|' || sender_name || '|' || content FROM message WHERE id = ?",
                String.class, message.id());
        OffsetDateTime storedSentAt = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", OffsetDateTime.class, message.id());
        String expectedRow = message.roomId() + "|anna|Anna Muster|Grüezi mitenand 👋";
        OffsetDateTime expectedSentAt = OffsetDateTime.parse("2026-09-29T13:38:12.974043Z");
        assertEquals(expectedRow, row);
        assertNotNull(storedSentAt);
        assertTrue(expectedSentAt.isEqual(storedSentAt), "gespeichert: " + storedSentAt);
    }

    /** RabbitMQ liefert denselben Stapel ein zweites Mal (At-least-once): eine Zeile. */
    @Test
    void ignoresDuplicateInLaterBatch() {
        ChatMessage message = messageWithContent("kommt zweimal");
        List<ChatMessage> batch = List.of(message);

        messageRepository.insertBatch(batch);
        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(1, rows);
    }

    /** Dieselbe Nachricht zweimal im selben Stapel: ebenfalls eine Zeile, kein Fehler. */
    @Test
    void ignoresDuplicateWithinSameBatch() {
        ChatMessage message = messageWithContent("zweimal im Stapel");
        List<ChatMessage> batch = List.of(message, message);

        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(1, rows);
    }

    /**
     * 500 Nachrichten in einem Aufruf ergeben EINE Transaktion. Jede Zeile
     * merkt sich in der Systemspalte xmin, welche Transaktion sie geschrieben
     * hat; hier tragen alle 500 dieselbe Nummer.
     */
    @Test
    void writesWholeBatchInOneTransaction() {
        List<ChatMessage> batch = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            ChatMessage message = messageWithContent("Nachricht " + i);
            batch.add(message);
        }

        messageRepository.insertBatch(batch);

        int rows = countRows("SELECT count(*) FROM message");
        int transactions = countRows("SELECT count(DISTINCT xmin::text) FROM message");
        assertEquals(500, rows);
        assertEquals(1, transactions);
    }

    /**
     * Eine Zeile, die PostgreSQL nicht speichern kann, meldet Spring als
     * DataIntegrityViolationException: ein Datenfehler, kein Ausfall. Genau
     * daran unterscheidet der Listener später die beiden Fehlerklassen.
     */
    @Test
    void reportsNulCharacterAsDataIntegrityViolation() {
        ChatMessage message = messageWithContent("vor" + NUL + "nach");
        List<ChatMessage> batch = List.of(message);

        DataIntegrityViolationException thrown = null;
        try {
            messageRepository.insertBatch(batch);
        } catch (DataIntegrityViolationException exception) {
            thrown = exception;
        }

        assertNotNull(thrown, "NUL muss als Datenfehler gemeldet werden");
        int rows = countRows("SELECT count(*) FROM message");
        assertEquals(0, rows);
    }

    /** Baut eine Nachricht, wie sie der chat-service in die Queue legt. */
    private ChatMessage messageWithContent(String content) {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        OffsetDateTime sentAt = OffsetDateTime.parse(CAPTURED_SENT_AT);
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", content, sentAt);
    }

    /** Führt eine count-Abfrage aus. */
    private int countRows(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count;
    }
}
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=MessageRepositoryIntegrationTest`
Erwartet: Übersetzungsfehler — `MessageRepository` gibt es noch nicht.

- [x] **Schritt 3: `MessageRepository` anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/repository/MessageRepository.java`

```java
package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Der einzige Weg in die Tabelle message.
 *
 * Bewusst JdbcTemplate und kein JPA: ein ganzer Stapel soll als EIN
 * Bulk-INSERT in die Datenbank gehen, und batchUpdate ist genau das
 * (PLANUNG.md, Abschnitt 2.1).
 */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * "ON CONFLICT (id) DO NOTHING": kommt dieselbe Nachricht ein zweites Mal
     * (At-least-once, PLANUNG.md 3.6), verwirft die Datenbank sie still.
     */
    private static final String INSERT_SQL = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt einen ganzen Stapel in EINER Transaktion: entweder steht danach
     * der ganze Stapel in der Datenbank oder gar nichts.
     *
     * Das @Transactional ist Pflicht und nicht Zierde. Ohne Transaktion
     * entschiede der Treiber, wann er ein COMMIT schickt, und ein Stapel
     * könnte halb geschrieben sein. Der Treiber fasst die Zeilen zu
     * mehrzeiligen INSERTs zusammen (reWriteBatchedInserts in der JDBC-URL).
     */
    @Transactional
    public void insertBatch(List<ChatMessage> messages) {
        List<Object[]> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            Object[] row = toRow(message);
            rows.add(row);
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, rows);
    }

    /** Die Werte einer Nachricht in der Reihenfolge der Fragezeichen im INSERT. */
    private Object[] toRow(ChatMessage message) {
        return new Object[]{
                message.id(),
                message.roomId(),
                message.senderId(),
                message.senderName(),
                message.content(),
                message.sentAt()
        };
    }
}
```

> **Zwei Dinge, die man leicht übersieht:**
> 1. **`@Transactional` ist Pflicht.** Ohne Transaktion entscheidet der Treiber selbst, wann er
>    ein COMMIT schickt; ein Stapel könnte halb geschrieben sein. Mit ihr zeigt der Test
>    `writesWholeBatchInOneTransaction` genau eine Transaktionsnummer (`xmin`) für 500 Zeilen.
> 2. **`batchUpdate(String, List<Object[]>)`** statt eines `BatchPreparedStatementSetter`: keine
>    anonyme Klasse, und `OffsetDateTime` und `UUID` gibt Spring unverändert an den Treiber.

- [x] **Schritt 4: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=MessageRepositoryIntegrationTest`
Erwartet: 6 Tests grün.

- [x] **Schritt 5: Committen**

```bash
git add batch-writer/src/main/java/ch/benedict/m321/batchwriter/repository \
        batch-writer/src/test/java/ch/benedict/m321/batchwriter/repository
git commit -m "feat: Stapel mit einem INSERT und ON CONFLICT DO NOTHING speichern" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 6: Queues wie im chat-service anlegen

**Warum an dieser Stelle:** Bevor ein Listener an `chat.persist` hängt, muss feststehen, dass seine Deklaration zu der des `chat-service` passt. Sonst verweigert RabbitMQ die zweite Anmeldung, und der Verbraucher startet nie.

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/QueueNames.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/RabbitConfig.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/config/RabbitConfigIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: Modul aus Task 2
- Stellt bereit: `QueueNames.PERSIST_QUEUE` = `"chat.persist"`, `QueueNames.DEAD_LETTER_QUEUE` = `"chat.dlq"`; Beans `Queue persistQueue` (durable, `x-dead-letter-exchange` = `""`, `x-dead-letter-routing-key` = `chat.dlq`) und `Queue deadLetterQueue` (durable)

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/config/RabbitConfigIntegrationTest.java`

```java
package ch.benedict.m321.batchwriter.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft gegen ein ECHTES RabbitMQ, dass der batch-writer die Queues genau so
 * anlegt wie der chat-service. Ein Mock bewiese hier nichts: ob zwei
 * Deklarationen zusammenpassen, entscheidet allein der Broker.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class RabbitConfigIntegrationTest {

    /** RabbitMQ wie in docker-compose.yml; @ServiceConnection setzt Host und Port. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /** Beide Queues entstehen beim Start, auch wenn der chat-service noch nie gesendet hat. */
    @Test
    void declaresBothQueues() {
        Properties persistQueue = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);
        Properties deadLetterQueue = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(persistQueue);
        assertNotNull(deadLetterQueue);
    }

    /**
     * Meldet chat.persist so an, wie es der chat-service tut (seine
     * RabbitConfig im Stand f8ea557e). Passten die Argumente nicht zusammen,
     * würfe RabbitMQ hier 406 PRECONDITION_FAILED.
     */
    @Test
    void acceptsDeclarationOfChatService() {
        rabbitAdmin.initialize();
        Queue queueOfChatService = QueueBuilder.durable("chat.persist")
                .deadLetterExchange("")
                .deadLetterRoutingKey("chat.dlq")
                .build();

        String declaredName = rabbitAdmin.declareQueue(queueOfChatService);

        assertEquals("chat.persist", declaredName);
    }

    /** Gegenprobe: eine Anmeldung ohne die Dead-Letter-Argumente lehnt RabbitMQ wirklich ab. */
    @Test
    void refusesDeclarationWithOtherArguments() {
        rabbitAdmin.initialize();
        Queue queueWithoutDeadLetter = QueueBuilder.durable("chat.persist").build();

        AmqpException thrown = null;
        try {
            rabbitAdmin.declareQueue(queueWithoutDeadLetter);
        } catch (AmqpException exception) {
            thrown = exception;
        }

        assertNotNull(thrown, "RabbitMQ hätte die abweichende Anmeldung ablehnen müssen");
    }
}
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=RabbitConfigIntegrationTest`
Erwartet: Übersetzungsfehler — `QueueNames` gibt es noch nicht.

- [x] **Schritt 3: `QueueNames` anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/QueueNames.java`

```java
package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der Queues, die der batch-writer braucht, an genau EINER Stelle.
 *
 * Eine eigene Kopie, keine gemeinsame Klasse mit dem chat-service: der
 * Vertrag zwischen den Diensten sind die Namen, nicht eine Java-Datei.
 */
public final class QueueNames {

    /** Schreibweg: hier legt der chat-service jede Nachricht ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Dead Letter: was der batch-writer endgültig nicht speichern kann. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
```

- [x] **Schritt 4: `RabbitConfig` mit den beiden Queues anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/RabbitConfig.java` — die Einstellungen für das Lesen in Stapeln kommen in Task 7 dazu.

```java
package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet beim Start ein, was der batch-writer bei RabbitMQ braucht: die
 * beiden Queues, genau so, wie der chat-service sie anlegt.
 */
@Configuration
public class RabbitConfig {

    /**
     * Der Schreibweg, mit GENAU denselben Eigenschaften wie im chat-service.
     *
     * Wer zuerst startet, legt die Queue an. Weicht ein Argument ab, lehnt
     * RabbitMQ die zweite Anmeldung mit PRECONDITION_FAILED ab, und der
     * Verbraucher startet nicht.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /**
     * Das Abstellgleis. Auch der batch-writer legt es an: gäbe es die Queue
     * noch nicht, würde RabbitMQ abgelehnte Nachrichten still verwerfen.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }
}
```

- [x] **Schritt 5: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=RabbitConfigIntegrationTest`
Erwartet: 3 Tests grün. Die Gegenprobe zeigt im Protokoll `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'`, also genau den Fehler, den eine abweichende Deklaration im Betrieb auslösen würde.

- [x] **Schritt 6: Committen**

```bash
git add batch-writer/src/main/java/ch/benedict/m321/batchwriter/config \
        batch-writer/src/test/java/ch/benedict/m321/batchwriter/config
git commit -m "feat: Queues wie im chat-service anlegen" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 7: Stapel aus chat.persist lesen und nach dem COMMIT bestätigen

**Warum an dieser Stelle:** Setzt Lesen (Task 4), Schreiben (Task 5) und die Queues (Task 6) zusammen. Erst jetzt gibt es den Weg von der Queue in die Tabelle und damit die Szenarien S3, S4 und S5. Die Fehlerwege kommen bewusst erst in Task 8 und 9.

**Dateien:**
- Ändern: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/RabbitConfig.java` (Stapel-Listener)
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/ReceivedMessage.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/MessageBatchListenerIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: `ChatMessageReader.read(byte[])` (Task 4), `MessageRepository.insertBatch(List<ChatMessage>)` (Task 5), `QueueNames` und die Queue-Beans (Task 6), `TestDatabase` (Task 3)
- Stellt bereit:
  - `RabbitConfig.BATCH_LISTENER_FACTORY` = `"batchListenerFactory"`: Stapel bis 500, `batchReceiveTimeout` und `receiveTimeout` 200 ms, `prefetch` 500, ein Verbraucher, `MANUAL`
  - `ReceivedMessage(long deliveryTag, ChatMessage chatMessage)`
  - `MessageBatchListener.LISTENER_ID` = `"messageBatchListener"`; `onBatch(List<Message> batch, Channel channel)`

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/MessageBatchListenerIntegrationTest.java`

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.TestDatabase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Schreibweg einmal ganz durch: Nachricht in chat.persist, Zeile in der
 * Tabelle message. Gegen ein ECHTES RabbitMQ und ein ECHTES PostgreSQL.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class MessageBatchListenerIntegrationTest {

    /** So lange warten wir höchstens darauf, dass der batch-writer etwas tut. */
    private static final int WAIT_MILLISECONDS = 30000;

    /** Ein beliebiger Raum: der batch-writer prüft Räume bewusst nicht. */
    private static final String ROOM_ID = "3f2b1c4e-0000-0000-0000-000000000001";

    /** Der Header, den der chat-service mitschickt und den wir ignorieren. */
    private static final String TYPE_ID_OF_CHAT_SERVICE = "ch.benedict.m321.chatservice.dto.ChatMessage";

    /** RabbitMQ wie in docker-compose.yml; @ServiceConnection setzt Host und Port. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** PostgreSQL mit dem echten Schema. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.createContainer();

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * Jeder Test beginnt mit laufendem Verbraucher, leeren Queues und leerer
     * Tabelle. Queues und Tabelle überleben die einzelne Testmethode.
     */
    @BeforeEach
    void startEmpty() {
        MessageListenerContainer container = listenerContainer();
        container.start();
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE, false);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE, false);
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Eine Nachricht, genau so geschickt wie vom chat-service (mit __TypeId__), steht danach in der Tabelle. */
    @Test
    void storesMessageSentLikeChatService() throws InterruptedException {
        UUID id = UUID.randomUUID();
        String body = json(id, "Hallo Datenbank");

        publishLikeChatService(body);

        int stored = waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", id);
        assertEquals(1, stored);
    }

    /** Eine unlesbare Nachricht landet in chat.dlq, die gute daneben wird trotzdem gespeichert (F8). */
    @Test
    void rejectsUnreadableMessageAndStoresTheRest() throws InterruptedException {
        UUID goodId = UUID.randomUUID();
        String goodBody = json(goodId, "gute Nachricht");

        publish("das ist kein JSON");
        publish(goodBody);

        int stored = waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", goodId);
        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, WAIT_MILLISECONDS);
        assertEquals(1, stored);
        assertNotNull(deadLetter, "die unlesbare Nachricht ist nicht in chat.dlq");
        byte[] deadBytes = deadLetter.getBody();
        String deadBody = new String(deadBytes, StandardCharsets.UTF_8);
        assertEquals("das ist kein JSON", deadBody);
    }

    /**
     * Szenario S5: dieselbe Nachricht zweimal direkt in chat.persist, nur mit
     * content_type. Danach eine Markierung: steht sie in der Tabelle, sind die
     * beiden davor sicher verarbeitet, denn ein Verbraucher arbeitet der Reihe nach.
     */
    @Test
    void storesDuplicateOnlyOnce() throws InterruptedException {
        UUID duplicateId = UUID.randomUUID();
        String duplicateBody = json(duplicateId, "S5 Duplikat");
        UUID markerId = UUID.randomUUID();
        String markerBody = json(markerId, "Markierung");

        publish(duplicateBody);
        publish(duplicateBody);
        publish(markerBody);

        waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", markerId);
        int duplicates = countRows("SELECT count(*) FROM message WHERE id = ?", duplicateId);
        int deadLetters = messageCount(QueueNames.DEAD_LETTER_QUEUE);
        assertEquals(1, duplicates);
        assertEquals(0, deadLetters);
    }

    /**
     * Szenario S4 im Kleinen: der Verbraucher ist aus, 1000 Nachrichten warten.
     * Nach dem Start stehen alle in der Tabelle, geschrieben in wenigen
     * Transaktionen. Jede Zeile merkt sich in der Systemspalte xmin die
     * Transaktion, die sie geschrieben hat: erwartet sind 2 (2 × 500), erlaubt
     * bis 20, damit ein langsamer Rechner den Test nicht rot macht.
     */
    @Test
    void writesThousandWaitingMessagesInFewTransactions() throws InterruptedException {
        MessageListenerContainer container = listenerContainer();
        container.stop();
        for (int i = 1; i <= 1000; i++) {
            UUID id = UUID.randomUUID();
            String body = json(id, "S4 " + i);
            publish(body);
        }

        container.start();

        int stored = waitForCount(1000, "SELECT count(*) FROM message");
        int transactions = countRows("SELECT count(DISTINCT xmin::text) FROM message");
        assertEquals(1000, stored);
        assertTrue(transactions <= 20, "zu viele Transaktionen: " + transactions);
    }

    /** Baut den Body einer Nachricht im Format des chat-service (Spezifikation 2.2). */
    private String json(UUID id, String content) {
        String template = """
                {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster",\
                "content":"%s","sentAt":"2026-09-29T13:38:12.974043374Z"}""";
        return template.formatted(id, ROOM_ID, content);
    }

    /** Legt einen Body in chat.persist, NUR mit content_type – wie im Szenario S5. */
    private void publish(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /** Legt einen Body so in chat.persist, wie es der chat-service tut: mit Encoding und __TypeId__. */
    private void publishLikeChatService(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setHeader("__TypeId__", TYPE_ID_OF_CHAT_SERVICE)
                .build();
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /**
     * Fragt die Datenbank alle 100 ms, bis die Zählung den erwarteten Wert hat.
     * Der batch-writer arbeitet in einem eigenen Thread, deshalb müssen wir
     * warten. Gibt den zuletzt gezählten Wert zurück.
     */
    private int waitForCount(int expected, String sql, Object... arguments) throws InterruptedException {
        int count = 0;
        int attempts = WAIT_MILLISECONDS / 100;
        for (int i = 0; i < attempts; i++) {
            count = countRows(sql, arguments);
            if (count == expected) {
                return count;
            }
            Thread.sleep(100);
        }
        return count;
    }

    /** Führt eine count-Abfrage mit Parametern aus. */
    private int countRows(String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count;
    }

    /** Wie viele Nachrichten gerade in einer Queue warten. */
    private int messageCount(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }

    /** Der Verbraucher des batch-writer, so wie Spring ihn unter seiner id führt. */
    private MessageListenerContainer listenerContainer() {
        return listenerRegistry.getListenerContainer(MessageBatchListener.LISTENER_ID);
    }
}
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=MessageBatchListenerIntegrationTest`
Erwartet: Übersetzungsfehler — `MessageBatchListener` gibt es noch nicht.

- [x] **Schritt 3: `RabbitConfig` um den Stapel-Listener ergänzen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/RabbitConfig.java`, ganze Datei:

```java
package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet beim Start ein, was der batch-writer bei RabbitMQ braucht: die
 * beiden Queues und die Einstellungen für das Lesen in Stapeln.
 */
@Configuration
public class RabbitConfig {

    /** Unter diesem Namen hängt sich der Listener an die Einstellungen unten. */
    public static final String BATCH_LISTENER_FACTORY = "batchListenerFactory";

    /** So viele Nachrichten höchstens in einem Stapel (PLANUNG.md 3.6). */
    private static final int BATCH_SIZE = 500;

    /** So lange sammelt der Listener höchstens an einem Stapel (PLANUNG.md 3.6). */
    private static final long BATCH_TIMEOUT_MILLISECONDS = 200;

    /**
     * Der Schreibweg, mit GENAU denselben Eigenschaften wie im chat-service.
     *
     * Wer zuerst startet, legt die Queue an. Weicht ein Argument ab, lehnt
     * RabbitMQ die zweite Anmeldung mit PRECONDITION_FAILED ab, und der
     * Verbraucher startet nicht.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /**
     * Das Abstellgleis. Auch der batch-writer legt es an: gäbe es die Queue
     * noch nicht, würde RabbitMQ abgelehnte Nachrichten still verwerfen.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Die Einstellungen des Stapel-Listeners. Sie stehen alle hier im Code,
     * weil eine selbst gebaute Factory die Listener-Einstellungen aus der
     * application.yml nicht liest.
     *
     * - consumerBatchEnabled: der Listener bekommt eine Liste statt einer Nachricht.
     * - batchSize und batchReceiveTimeout: ein Stapel ist fertig bei 500 Stück
     *   oder 200 ms nach Beginn des Sammelns.
     * - receiveTimeout: kommt 200 ms lang nichts, geht der Stapel sofort los.
     * - prefetchCount: RabbitMQ schickt genau einen Stapel auf Vorrat.
     * - concurrentConsumers: ein Verbraucher pro Instanz, skaliert wird mit --scale.
     * - MANUAL: wir bestätigen selbst, und zwar erst nach dem COMMIT.
     */
    @Bean(name = BATCH_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory batchListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchListener(true);
        factory.setBatchSize(BATCH_SIZE);
        factory.setBatchReceiveTimeout(BATCH_TIMEOUT_MILLISECONDS);
        factory.setReceiveTimeout(BATCH_TIMEOUT_MILLISECONDS);
        factory.setPrefetchCount(BATCH_SIZE);
        factory.setConcurrentConsumers(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
```

> **Falle:** Eine selbst gebaute `SimpleRabbitListenerContainerFactory` liest die
> `spring.rabbitmq.listener.simple.*`-Einstellungen aus der `application.yml` nicht. Deshalb
> steht jede Einstellung hier im Code und nirgends sonst.
>
> **«200 ms» genau genommen:** `batchReceiveTimeout` (seit Spring AMQP 3.1.2) begrenzt, wie
> lange an einem Stapel gesammelt wird. `receiveTimeout` allein hiesse nur «200 ms lang nichts
> Neues»; bei gleichmässigem Verkehr würde ein Stapel dann erst fertig, wenn er voll ist.

- [x] **Schritt 4: `ReceivedMessage` anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/ReceivedMessage.java`

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;

/**
 * Eine gelesene Nachricht zusammen mit ihrem Lieferschein.
 *
 * Der deliveryTag ist die Nummer, unter der RabbitMQ die Nachricht auf diesem
 * Channel ausgeliefert hat. Nur mit dieser Nummer lässt sie sich bestätigen
 * (ACK), ablehnen (Reject) oder zurückgeben (NACK).
 *
 * @param deliveryTag die Liefernummer von RabbitMQ
 * @param chatMessage der Inhalt, schon aus dem JSON gelesen
 */
public record ReceivedMessage(long deliveryTag, ChatMessage chatMessage) {
}
```

- [x] **Schritt 5: `MessageBatchListener` anlegen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java`

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holt Stapel aus chat.persist, schreibt sie in die Datenbank und bestätigt
 * sie erst DANACH (PLANUNG.md 3.6, Spezifikation 3.1).
 *
 * Stürzt der batch-writer vor dem ACK ab, liefert RabbitMQ den Stapel erneut:
 * At-least-once. Das zweite Mal verwirft die Datenbank (ON CONFLICT DO NOTHING).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchListener {

    /** Unter diesem Namen findet man den Listener, z. B. im Test, um ihn anzuhalten. */
    public static final String LISTENER_ID = "messageBatchListener";

    private final ChatMessageReader chatMessageReader;
    private final MessageRepository messageRepository;

    /**
     * Wird von Spring für jeden Stapel aufgerufen: bis zu 500 Nachrichten oder
     * was innerhalb von 200 ms kam (siehe RabbitConfig).
     *
     * Wir bekommen die ROHEN Nachrichten und keine fertigen Objekte: so lesen
     * wir nur den Body und können eine einzelne unlesbare Nachricht
     * aussortieren, ohne den ganzen Stapel zu verlieren.
     */
    @RabbitListener(id = LISTENER_ID, queues = QueueNames.PERSIST_QUEUE,
            containerFactory = RabbitConfig.BATCH_LISTENER_FACTORY)
    public void onBatch(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = readAll(batch, channel);
        if (readableMessages.isEmpty()) {
            return;
        }
        store(readableMessages, channel);
    }

    /**
     * Liest jede Nachricht des Stapels. Was sich nicht lesen lässt, lehnen wir
     * sofort ab. RabbitMQ legt es über die Argumente der Queue nach chat.dlq.
     */
    private List<ReceivedMessage> readAll(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message message : batch) {
            MessageProperties properties = message.getMessageProperties();
            long deliveryTag = properties.getDeliveryTag();
            byte[] body = message.getBody();
            ChatMessage chatMessage = chatMessageReader.read(body);

            if (chatMessage == null) {
                log.warn("Message with delivery tag {} is not a readable chat message, rejecting it to {}",
                        deliveryTag, QueueNames.DEAD_LETTER_QUEUE);
                channel.basicReject(deliveryTag, false);
            } else {
                ReceivedMessage receivedMessage = new ReceivedMessage(deliveryTag, chatMessage);
                readableMessages.add(receivedMessage);
            }
        }
        return readableMessages;
    }

    /**
     * Schreibt den Stapel in einer Transaktion und bestätigt ihn danach mit
     * EINEM ACK. "multiple = true" heisst: alle noch offenen Nachrichten bis
     * einschliesslich dieses Tags sind erledigt.
     */
    private void store(List<ReceivedMessage> messages, Channel channel) throws IOException {
        List<ChatMessage> chatMessages = toChatMessages(messages);
        long lastDeliveryTag = lastDeliveryTag(messages);
        int batchSize = chatMessages.size();

        messageRepository.insertBatch(chatMessages);

        // Erst hier, nach dem COMMIT, bestätigen wir den ganzen Stapel.
        channel.basicAck(lastDeliveryTag, true);
        log.info("Stored batch of {} messages", batchSize);
    }

    /** Nimmt aus den gelesenen Nachrichten den Inhalt, der in die Datenbank geht. */
    private List<ChatMessage> toChatMessages(List<ReceivedMessage> messages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ReceivedMessage message : messages) {
            ChatMessage chatMessage = message.chatMessage();
            chatMessages.add(chatMessage);
        }
        return chatMessages;
    }

    /**
     * Der Tag der LETZTEN lesbaren Nachricht, nie der einer abgelehnten:
     * RabbitMQ würde ein ACK dafür mit "unknown delivery tag" beantworten und
     * den Channel schliessen (Spezifikation 3.3, F12).
     */
    private long lastDeliveryTag(List<ReceivedMessage> messages) {
        int lastIndex = messages.size() - 1;
        ReceivedMessage lastMessage = messages.get(lastIndex);
        return lastMessage.deliveryTag();
    }
}
```

> **Warum `List<Message>` und nicht `List<ChatMessage>`:** Mit fertigen Objekten müsste ein
> Message-Converter dem Header `__TypeId__` folgen, der auf eine Klasse des `chat-service` zeigt,
> und eine einzige unlesbare Nachricht liesse den ganzen Stapel scheitern. Mit den rohen
> Nachrichten liest `ChatMessageReader` nur den Body, und jede Nachricht behält ihren eigenen
> `deliveryTag` für ACK oder Reject.

- [x] **Schritt 6: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=MessageBatchListenerIntegrationTest`
Erwartet: 4 Tests grün; im Protokoll des S4-Tests Zeilen wie `Stored batch of 500 messages`.

- [x] **Schritt 7: Committen**

```bash
git add batch-writer/src/main/java/ch/benedict/m321/batchwriter \
        batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/MessageBatchListenerIntegrationTest.java
git commit -m "feat: Stapel aus chat.persist lesen und nach dem COMMIT bestätigen" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 8: Stapel bei Datenbankausfall zurück in die Queue

**Warum an dieser Stelle:** Ein Fehlerweg setzt den Normalweg voraus (Task 7). Von den Fehlerwegen kommt dieser zuerst, weil Szenario S7 das grösste Risiko trägt: Ohne ihn bleibt ein Stapel bei einem Ausfall unbestätigt hängen, und der batch-writer arbeitet bis zu einem Neustart nicht weiter.

**Dateien:**
- Ändern: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java`
- Ändern: `batch-writer/src/main/resources/application.yml` (`socketTimeout`, Verbindungspool)
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/DatabaseOutageIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: `MessageBatchListener` (Task 7), `TestDatabase` (Task 3)
- Stellt bereit: Jede Exception beim Schreiben führt zu 2 s Pause und `basicNack(letzterTag, multiple = true, requeue = true)`. Nichts geht in die DLQ, der Verbraucher läuft weiter. Verbindungspool: höchstens 2 Verbindungen, 5 s Wartezeit auf eine Verbindung.

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

`batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/DatabaseOutageIntegrationTest.java`

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.TestDatabase;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S7 im Kleinen: die Datenbank fällt aus, während Nachrichten
 * ankommen. Danach muss jede Nachricht in der Tabelle stehen, keine in der
 * Dead-Letter-Queue, und der Verbraucher muss noch laufen – ohne Neustart.
 *
 * So entsteht der Ausfall: Wir sperren die Datenbank für neue Verbindungen
 * und trennen alle bestehenden. Für den batch-writer sieht das aus wie ein
 * gestoppter Server (Spezifikation 3.3, F4). Den Container selbst anzuhalten
 * ginge nicht: er käme mit einem anderen Port zurück, und der batch-writer
 * fände ihn nicht mehr.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class DatabaseOutageIntegrationTest {

    /** So viele Nachrichten kommen während des Ausfalls an. */
    private static final int MESSAGE_COUNT = 50;

    /**
     * So lange ist die Datenbank weg: länger als die 30 s, die der
     * Verbindungspool ohne unsere Einstellung höchstens auf eine Verbindung
     * wartet. So scheitert das Schreiben sicher mindestens einmal.
     */
    private static final int OUTAGE_MILLISECONDS = 35000;

    /** So lange warten wir höchstens auf Zeilen in der Tabelle. */
    private static final int WAIT_MILLISECONDS = 30000;

    /** Ein beliebiger Raum: der batch-writer prüft Räume bewusst nicht. */
    private static final String ROOM_ID = "3f2b1c4e-0000-0000-0000-000000000001";

    /** RabbitMQ wie in docker-compose.yml. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** PostgreSQL mit dem echten Schema. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.createContainer();

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * Nachrichten während des Ausfalls: alle kommen an, keine in der DLQ, der
     * Verbraucher lebt. Wie in der Abnahme läuft vorher schon etwas durch,
     * damit der Verbindungspool Verbindungen hält, die der Ausfall dann trennt.
     */
    @Test
    void storesEverythingOnceTheDatabaseIsBack() throws Exception {
        UUID warmUpId = UUID.randomUUID();
        String warmUpBody = json(warmUpId, "vorher");
        publish(warmUpBody);
        int warmedUp = waitForCount(1, "SELECT count(*) FROM message WHERE id = ?", warmUpId);
        assertEquals(1, warmedUp);

        blockDatabase();
        try {
            for (int i = 1; i <= MESSAGE_COUNT; i++) {
                UUID id = UUID.randomUUID();
                String body = json(id, "S7 " + i);
                publish(body);
            }
            Thread.sleep(OUTAGE_MILLISECONDS);
        } finally {
            unblockDatabase();
        }

        int stored = waitForCount(MESSAGE_COUNT, "SELECT count(*) FROM message WHERE content LIKE 'S7 %'");
        int deadLetters = messageCount(QueueNames.DEAD_LETTER_QUEUE);
        MessageListenerContainer container = listenerRegistry.getListenerContainer(MessageBatchListener.LISTENER_ID);
        assertEquals(MESSAGE_COUNT, stored);
        assertEquals(0, deadLetters);
        assertTrue(container.isRunning(), "der Verbraucher läuft nicht mehr");
    }

    /**
     * Sperrt die Test-Datenbank für neue Verbindungen und trennt alle
     * bestehenden. Das geht nur von einer ANDEREN Datenbank aus, deshalb
     * arbeiten wir in der Verwaltungsdatenbank "postgres".
     */
    private void blockDatabase() throws SQLException {
        String databaseName = postgres.getDatabaseName();
        executeInMaintenanceDatabase("ALTER DATABASE " + databaseName + " WITH ALLOW_CONNECTIONS false");
        executeInMaintenanceDatabase(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + databaseName + "'");
    }

    /** Lässt wieder Verbindungen zu: die Datenbank ist "zurück". */
    private void unblockDatabase() throws SQLException {
        String databaseName = postgres.getDatabaseName();
        executeInMaintenanceDatabase("ALTER DATABASE " + databaseName + " WITH ALLOW_CONNECTIONS true");
    }

    /** Führt einen Befehl als Superuser in der Verwaltungsdatenbank "postgres" aus. */
    private void executeInMaintenanceDatabase(String sql) throws SQLException {
        String host = postgres.getHost();
        Integer port = postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        String url = "jdbc:postgresql://" + host + ":" + port + "/postgres";
        String user = postgres.getUsername();
        String password = postgres.getPassword();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Fragt alle 500 ms, bis die Zählung den erwarteten Wert hat. Der
     * batch-writer arbeitet in einem eigenen Thread, deshalb müssen wir warten.
     * Gibt den zuletzt gezählten Wert zurück.
     */
    private int waitForCount(int expected, String sql, Object... arguments) throws InterruptedException {
        int count = 0;
        int attempts = WAIT_MILLISECONDS / 500;
        for (int i = 0; i < attempts; i++) {
            count = countRows(sql, arguments);
            if (count == expected) {
                return count;
            }
            Thread.sleep(500);
        }
        return count;
    }

    /**
     * Führt eine count-Abfrage aus. Kurz nach dem Ausfall kann auch diese
     * Abfrage noch scheitern; dann zählen wir 0 und versuchen es beim nächsten
     * Mal wieder.
     */
    private int countRows(String sql, Object... arguments) {
        Integer count;
        try {
            count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        } catch (RuntimeException exception) {
            return 0;
        }
        return count;
    }

    /** Baut den Body einer Nachricht im Format des chat-service (Spezifikation 2.2). */
    private String json(UUID id, String content) {
        String template = """
                {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster",\
                "content":"%s","sentAt":"2026-09-29T13:38:12.974043374Z"}""";
        return template.formatted(id, ROOM_ID, content);
    }

    /** Legt einen Body in chat.persist, nur mit content_type. */
    private void publish(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /** Wie viele Nachrichten gerade in einer Queue warten. */
    private int messageCount(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }
}
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=DatabaseOutageIntegrationTest`
Erwartet: Fehlschlag mit `expected: <50> but was: <0>`.
- Vor dem Ausfall hält der Pool Verbindungen, der Ausfall trennt sie.
- Der Listener schreibt auf einer dieser getrennten Verbindungen. Spring meldet `DataAccessResourceFailureException: … This connection has been closed` (so im roten Probelauf vom 29.09.2026 beobachtet). Bekommt der Pool gar keine Verbindung, wartet er ohne unsere Einstellung bis zu 30 s und wirft dann eine `CannotCreateTransactionException`.
- Die Exception verlässt den Listener, und im Modus `MANUAL` schickt Spring AMQP dann **kein** NACK.
- Der Stapel bleibt unbestätigt beim batch-writer liegen, auch nachdem die Datenbank zurück ist.

- [x] **Schritt 3: `application.yml` ergänzen**

`batch-writer/src/main/resources/application.yml`, ganze Datei:

```yaml
spring:
  application:
    name: batch-writer
  main:
    # Kein Webserver: niemand ruft den batch-writer auf. So belegt er
    # keinen Port und startet schneller.
    web-application-type: none
  rabbitmq:
    # Im Docker-Netz heisst der Broker "rabbitmq". Beim Start ausserhalb
    # von Docker greift der Vorgabewert "localhost".
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  datasource:
    # reWriteBatchedInserts=true: der Treiber fasst die INSERTs eines Stapels
    #   zu mehrzeiligen INSERTs zusammen (bis 128 Zeilen je Anweisung).
    # socketTimeout=30: antwortet die Datenbank 30 s lang nicht, bricht die
    #   Anfrage ab, statt den Stapel für immer festzuhalten.
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:chat}?reWriteBatchedInserts=true&socketTimeout=30
    username: ${POSTGRES_USER:chat}
    password: ${POSTGRES_PASSWORD:chat}
    hikari:
      # Ein Verbraucher-Thread braucht genau eine Verbindung. Die Vorgabe
      # wären 10 pro Instanz, und jede neue Verbindung kostet Transaktionen.
      maximum-pool-size: 2
      minimum-idle: 1
      # Ist die Datenbank weg, merken wir es nach 5 s statt nach 30 s.
      connection-timeout: 5000

logging:
  level:
    # Im Unterricht wollen wir jeden Schritt sehen.
    ch.benedict.m321: DEBUG
```

- [x] **Schritt 4: Den Listener um die Rückgabe an die Queue ergänzen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java`, ganze Datei. Neu sind der zweite Absatz im Klassenkommentar, die Konstante `PAUSE_BEFORE_RETRY_MILLISECONDS`, das `try`/`catch` in `store` sowie `returnToQueue` und `pauseBeforeRetry`:

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holt Stapel aus chat.persist, schreibt sie in die Datenbank und bestätigt
 * sie erst DANACH (PLANUNG.md 3.6, Spezifikation 3.1).
 *
 * Stürzt der batch-writer vor dem ACK ab, liefert RabbitMQ den Stapel erneut:
 * At-least-once. Das zweite Mal verwirft die Datenbank (ON CONFLICT DO NOTHING).
 *
 * Fehler gehören in eine von zwei Klassen (Spezifikation 3.3):
 * - Die Nachricht ist schuld: sie geht sofort nach chat.dlq, ein zweiter
 *   Versuch würde genauso scheitern.
 * - Die Umgebung ist schuld, z. B. die Datenbank ist weg: der Stapel geht
 *   nach einer Pause zurück in die Queue und kommt später wieder.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchListener {

    /** Unter diesem Namen findet man den Listener, z. B. im Test, um ihn anzuhalten. */
    public static final String LISTENER_ID = "messageBatchListener";

    /** Pause, bevor ein Stapel zurückgeht. Ohne sie kreiste er ohne Halt zwischen Queue und Listener. */
    private static final long PAUSE_BEFORE_RETRY_MILLISECONDS = 2000;

    private final ChatMessageReader chatMessageReader;
    private final MessageRepository messageRepository;

    /**
     * Wird von Spring für jeden Stapel aufgerufen: bis zu 500 Nachrichten oder
     * was innerhalb von 200 ms kam (siehe RabbitConfig).
     *
     * Wir bekommen die ROHEN Nachrichten und keine fertigen Objekte: so lesen
     * wir nur den Body und können eine einzelne unlesbare Nachricht
     * aussortieren, ohne den ganzen Stapel zu verlieren.
     */
    @RabbitListener(id = LISTENER_ID, queues = QueueNames.PERSIST_QUEUE,
            containerFactory = RabbitConfig.BATCH_LISTENER_FACTORY)
    public void onBatch(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = readAll(batch, channel);
        if (readableMessages.isEmpty()) {
            return;
        }
        store(readableMessages, channel);
    }

    /**
     * Liest jede Nachricht des Stapels. Was sich nicht lesen lässt, lehnen wir
     * sofort ab. RabbitMQ legt es über die Argumente der Queue nach chat.dlq.
     */
    private List<ReceivedMessage> readAll(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message message : batch) {
            MessageProperties properties = message.getMessageProperties();
            long deliveryTag = properties.getDeliveryTag();
            byte[] body = message.getBody();
            ChatMessage chatMessage = chatMessageReader.read(body);

            if (chatMessage == null) {
                log.warn("Message with delivery tag {} is not a readable chat message, rejecting it to {}",
                        deliveryTag, QueueNames.DEAD_LETTER_QUEUE);
                channel.basicReject(deliveryTag, false);
            } else {
                ReceivedMessage receivedMessage = new ReceivedMessage(deliveryTag, chatMessage);
                readableMessages.add(receivedMessage);
            }
        }
        return readableMessages;
    }

    /**
     * Schreibt den Stapel in einer Transaktion und bestätigt ihn danach mit
     * EINEM ACK. "multiple = true" heisst: alle noch offenen Nachrichten bis
     * einschliesslich dieses Tags sind erledigt.
     */
    private void store(List<ReceivedMessage> messages, Channel channel) throws IOException {
        List<ChatMessage> chatMessages = toChatMessages(messages);
        long lastDeliveryTag = lastDeliveryTag(messages);
        int batchSize = chatMessages.size();

        try {
            messageRepository.insertBatch(chatMessages);
        } catch (RuntimeException exception) {
            returnToQueue(lastDeliveryTag, channel, exception);
            return;
        }

        // Erst hier, nach dem COMMIT, bestätigen wir den ganzen Stapel.
        channel.basicAck(lastDeliveryTag, true);
        log.info("Stored batch of {} messages", batchSize);
    }

    /**
     * Die Datenbank ist nicht erreichbar, oder etwas anderes Unerwartetes ist
     * passiert. Die Nachrichten sind nicht schuld und gehören nicht in die DLQ.
     * Wir warten kurz und geben den Stapel an RabbitMQ zurück, das ihn erneut
     * liefert (PLANUNG.md 3.6: NACK mit requeue).
     */
    private void returnToQueue(long lastDeliveryTag, Channel channel, RuntimeException exception) throws IOException {
        String reason = exception.getMessage();
        log.warn("Could not store batch, returning it to {} after {} ms: {}",
                QueueNames.PERSIST_QUEUE, PAUSE_BEFORE_RETRY_MILLISECONDS, reason);
        pauseBeforeRetry();
        channel.basicNack(lastDeliveryTag, true, true);
    }

    /**
     * Wartet vor dem NACK. Wird der Thread dabei unterbrochen, weil der
     * batch-writer herunterfährt, hören wir sofort auf zu warten und merken
     * uns die Unterbrechung für Spring.
     */
    private void pauseBeforeRetry() {
        try {
            Thread.sleep(PAUSE_BEFORE_RETRY_MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Nimmt aus den gelesenen Nachrichten den Inhalt, der in die Datenbank geht. */
    private List<ChatMessage> toChatMessages(List<ReceivedMessage> messages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ReceivedMessage message : messages) {
            ChatMessage chatMessage = message.chatMessage();
            chatMessages.add(chatMessage);
        }
        return chatMessages;
    }

    /**
     * Der Tag der LETZTEN lesbaren Nachricht, nie der einer abgelehnten:
     * RabbitMQ würde ein ACK dafür mit "unknown delivery tag" beantworten und
     * den Channel schliessen (Spezifikation 3.3, F12).
     */
    private long lastDeliveryTag(List<ReceivedMessage> messages) {
        int lastIndex = messages.size() - 1;
        ReceivedMessage lastMessage = messages.get(lastIndex);
        return lastMessage.deliveryTag();
    }
}
```

> **Falle 1 – die falsche Exception:** `@Transactional` holt die Verbindung, **bevor** die Methode
> beginnt. Gibt es keine, wirft Spring eine `CannotCreateTransactionException`, und die ist
> **keine** `DataAccessException`. Ein `catch (DataAccessException …)` fängt sie also nicht.
> Deshalb fängt `store` jede `RuntimeException`. Ausnahme ist nur der Datenfehler, den Task 9
> **darüber** abfängt.
>
> **Falle 2 – MANUAL heisst wirklich manuell:** Verlässt eine Exception den Listener, schickt
> Spring AMQP im Modus `MANUAL` weder ACK noch NACK
> (`BlockingQueueConsumer.rollbackOnExceptionIfNecessary`). Die Nachrichten blieben unbestätigt,
> `prefetch` wäre ausgeschöpft, und der Verbraucher bekäme nichts Neues mehr. Das NACK muss also
> im Listener selbst stehen.
>
> **Warum Pause und NACK statt einer Warteschleife im Listener:** Spring AMQP unterbricht die
> Verbraucher-Threads 5 s nach dem Stopp-Signal. Eine Schleife, die bis zur Rückkehr der
> Datenbank wartet, würde das Herunterfahren blockieren. So kehrt der Listener nach höchstens rund
> 7 s zurück: 5 s Warten auf eine Verbindung plus 2 s Pause.

- [x] **Schritt 5: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=DatabaseOutageIntegrationTest`
Erwartet: 1 Test grün, nach rund 40 s. Im Protokoll stehen während des Ausfalls etwa alle 7 s Warnungen `Could not store batch, returning it to chat.persist after 2000 ms`, danach `Stored batch of 50 messages`.

- [x] **Schritt 6: Alle Tests des Moduls laufen lassen**

Ausführen: `mvn -q -pl batch-writer test`
Erwartet: alle Tests grün. Der Pool mit 2 Verbindungen darf keinen anderen Test verlangsamen.

- [x] **Schritt 7: Committen**

```bash
git add batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java \
        batch-writer/src/main/resources/application.yml \
        batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/DatabaseOutageIntegrationTest.java
git commit -m "feat: Stapel bei Datenbankausfall zurück in die Queue" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 9: Nicht speicherbare Nachricht einzeln in die DLQ

**Warum an dieser Stelle:** Der seltenere Fehlerweg (Spezifikation 3.3, F9). Er setzt die Fehlerbehandlung aus Task 8 voraus: Sein `catch (DataIntegrityViolationException …)` muss **über** dem `catch (RuntimeException …)` stehen, sonst schickte ein Datenfehler den Stapel endlos zurück in die Queue.

**Dateien:**
- Ändern: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/MessageBatchListenerIntegrationTest.java` (ein Testfall dazu)

**Schnittstellen:**
- Verbraucht: `MessageRepository.insertBatch` (Task 5), die im Test aus Task 5 nachgewiesene `DataIntegrityViolationException` bei NUL, Listener aus Task 8
- Stellt bereit: Lehnt die Datenbank eine Zeile ab, wird der Stapel Nachricht für Nachricht geschrieben. Gute Nachrichten bekommen ein ACK, die abgelehnte ein Reject und landet in `chat.dlq`. Fällt dabei die Datenbank aus, geht der Rest mit einem NACK zurück.

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

In `MessageBatchListenerIntegrationTest` nach `writesThousandWaitingMessagesInFewTransactions` einfügen, dazu die Hilfsmethode `jsonEscapedNul` vor `publish`:

```java
    /**
     * Eine Nachricht, die die Datenbank ablehnt (Zeichen NUL im Text), reisst
     * die anderen im selben Stapel nicht mit: sie werden gespeichert, nur die
     * abgelehnte landet in chat.dlq (F9). Der Verbraucher ist beim Senden aus,
     * damit alle drei sicher im selben Stapel ankommen.
     */
    @Test
    void rejectsOnlyTheMessageTheDatabaseRefuses() throws InterruptedException {
        UUID firstId = UUID.randomUUID();
        UUID refusedId = UUID.randomUUID();
        UUID lastId = UUID.randomUUID();
        String nul = jsonEscapedNul();
        String refusedContent = "vor " + nul + " nach";
        String firstBody = json(firstId, "davor");
        String refusedBody = json(refusedId, refusedContent);
        String lastBody = json(lastId, "danach");
        MessageListenerContainer container = listenerContainer();
        container.stop();

        publish(firstBody);
        publish(refusedBody);
        publish(lastBody);
        container.start();

        int stored = waitForCount(2, "SELECT count(*) FROM message WHERE id IN (?, ?)", firstId, lastId);
        Message deadLetter = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, WAIT_MILLISECONDS);
        int refusedRows = countRows("SELECT count(*) FROM message WHERE id = ?", refusedId);
        assertEquals(2, stored);
        assertEquals(0, refusedRows);
        assertNotNull(deadLetter, "die abgelehnte Nachricht ist nicht in chat.dlq");
        byte[] deadBytes = deadLetter.getBody();
        String deadBody = new String(deadBytes, StandardCharsets.UTF_8);
        String refusedIdText = refusedId.toString();
        assertTrue(deadBody.contains(refusedIdText), deadBody);
    }

    /**
     * Die Escape-Folge für das Zeichen NUL, wie sie im JSON-Text steht:
     * Backslash, u und viermal 0. Jackson macht daraus beim Lesen das Zeichen.
     */
    private String jsonEscapedNul() {
        return "\\" + "u0000";
    }
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl batch-writer test -Dtest=MessageBatchListenerIntegrationTest#rejectsOnlyTheMessageTheDatabaseRefuses`
Erwartet: Fehlschlag mit `expected: <2> but was: <0>`. Die `DataIntegrityViolationException` landet im `catch (RuntimeException …)` aus Task 8: Der ganze Stapel geht alle 2 s zurück in die Queue und wird nie geschrieben, auch die zwei guten Nachrichten nicht. Im roten Probelauf vom 29.09.2026 scheiterte deshalb auch der Test, der danach lief (`storesDuplicateOnlyOnce`): der vergiftete Stapel lag gerade unbestätigt beim Listener, überstand so das Leeren der Queue und kreiste weiter.

- [x] **Schritt 3: Den Listener um das Einzelschreiben ergänzen**

`batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java`, ganze Datei. Neu sind die Importe `DataIntegrityViolationException` und `UUID`, der erste `catch` in `store` sowie `storeOneByOne` und `storeOne`:

```java
package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Holt Stapel aus chat.persist, schreibt sie in die Datenbank und bestätigt
 * sie erst DANACH (PLANUNG.md 3.6, Spezifikation 3.1).
 *
 * Stürzt der batch-writer vor dem ACK ab, liefert RabbitMQ den Stapel erneut:
 * At-least-once. Das zweite Mal verwirft die Datenbank (ON CONFLICT DO NOTHING).
 *
 * Fehler gehören in eine von zwei Klassen (Spezifikation 3.3):
 * - Die Nachricht ist schuld: sie geht sofort nach chat.dlq, ein zweiter
 *   Versuch würde genauso scheitern.
 * - Die Umgebung ist schuld, z. B. die Datenbank ist weg: der Stapel geht
 *   nach einer Pause zurück in die Queue und kommt später wieder.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageBatchListener {

    /** Unter diesem Namen findet man den Listener, z. B. im Test, um ihn anzuhalten. */
    public static final String LISTENER_ID = "messageBatchListener";

    /** Pause, bevor ein Stapel zurückgeht. Ohne sie kreiste er ohne Halt zwischen Queue und Listener. */
    private static final long PAUSE_BEFORE_RETRY_MILLISECONDS = 2000;

    private final ChatMessageReader chatMessageReader;
    private final MessageRepository messageRepository;

    /**
     * Wird von Spring für jeden Stapel aufgerufen: bis zu 500 Nachrichten oder
     * was innerhalb von 200 ms kam (siehe RabbitConfig).
     *
     * Wir bekommen die ROHEN Nachrichten und keine fertigen Objekte: so lesen
     * wir nur den Body und können eine einzelne unlesbare Nachricht
     * aussortieren, ohne den ganzen Stapel zu verlieren.
     */
    @RabbitListener(id = LISTENER_ID, queues = QueueNames.PERSIST_QUEUE,
            containerFactory = RabbitConfig.BATCH_LISTENER_FACTORY)
    public void onBatch(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = readAll(batch, channel);
        if (readableMessages.isEmpty()) {
            return;
        }
        store(readableMessages, channel);
    }

    /**
     * Liest jede Nachricht des Stapels. Was sich nicht lesen lässt, lehnen wir
     * sofort ab. RabbitMQ legt es über die Argumente der Queue nach chat.dlq.
     */
    private List<ReceivedMessage> readAll(List<Message> batch, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message message : batch) {
            MessageProperties properties = message.getMessageProperties();
            long deliveryTag = properties.getDeliveryTag();
            byte[] body = message.getBody();
            ChatMessage chatMessage = chatMessageReader.read(body);

            if (chatMessage == null) {
                log.warn("Message with delivery tag {} is not a readable chat message, rejecting it to {}",
                        deliveryTag, QueueNames.DEAD_LETTER_QUEUE);
                channel.basicReject(deliveryTag, false);
            } else {
                ReceivedMessage receivedMessage = new ReceivedMessage(deliveryTag, chatMessage);
                readableMessages.add(receivedMessage);
            }
        }
        return readableMessages;
    }

    /**
     * Schreibt den Stapel in einer Transaktion und bestätigt ihn danach mit
     * EINEM ACK. "multiple = true" heisst: alle noch offenen Nachrichten bis
     * einschliesslich dieses Tags sind erledigt.
     */
    private void store(List<ReceivedMessage> messages, Channel channel) throws IOException {
        List<ChatMessage> chatMessages = toChatMessages(messages);
        long lastDeliveryTag = lastDeliveryTag(messages);
        int batchSize = chatMessages.size();

        try {
            messageRepository.insertBatch(chatMessages);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Database refused a message in a batch of {}, storing the batch one by one", batchSize);
            storeOneByOne(messages, channel);
            return;
        } catch (RuntimeException exception) {
            returnToQueue(lastDeliveryTag, channel, exception);
            return;
        }

        // Erst hier, nach dem COMMIT, bestätigen wir den ganzen Stapel.
        channel.basicAck(lastDeliveryTag, true);
        log.info("Stored batch of {} messages", batchSize);
    }

    /**
     * Die Datenbank hat eine Zeile des Stapels abgelehnt, z. B. wegen des
     * Zeichens NUL. Jetzt schreiben wir jede Nachricht einzeln: gute werden
     * bestätigt, nur die abgelehnte geht nach chat.dlq (Spezifikation 3.3, F9).
     * Fällt dabei die Datenbank aus, geht der Rest mit einem NACK zurück.
     */
    private void storeOneByOne(List<ReceivedMessage> messages, Channel channel) throws IOException {
        long lastDeliveryTag = lastDeliveryTag(messages);
        try {
            for (ReceivedMessage message : messages) {
                storeOne(message, channel);
            }
        } catch (RuntimeException exception) {
            returnToQueue(lastDeliveryTag, channel, exception);
        }
    }

    /**
     * Schreibt eine einzelne Nachricht in ihrer eigenen Transaktion. Lehnt die
     * Datenbank sie ab, ist die Nachricht schuld: Reject, sie geht nach
     * chat.dlq. Jeder andere Fehler geht weiter an storeOneByOne.
     */
    private void storeOne(ReceivedMessage message, Channel channel) throws IOException {
        ChatMessage chatMessage = message.chatMessage();
        long deliveryTag = message.deliveryTag();
        List<ChatMessage> single = List.of(chatMessage);

        try {
            messageRepository.insertBatch(single);
        } catch (DataIntegrityViolationException exception) {
            UUID messageId = chatMessage.id();
            log.warn("Database refused message {}, rejecting it to {}", messageId, QueueNames.DEAD_LETTER_QUEUE);
            channel.basicReject(deliveryTag, false);
            return;
        }
        channel.basicAck(deliveryTag, false);
    }

    /**
     * Die Datenbank ist nicht erreichbar, oder etwas anderes Unerwartetes ist
     * passiert. Die Nachrichten sind nicht schuld und gehören nicht in die DLQ.
     * Wir warten kurz und geben den Stapel an RabbitMQ zurück, das ihn erneut
     * liefert (PLANUNG.md 3.6: NACK mit requeue).
     */
    private void returnToQueue(long lastDeliveryTag, Channel channel, RuntimeException exception) throws IOException {
        String reason = exception.getMessage();
        log.warn("Could not store batch, returning it to {} after {} ms: {}",
                QueueNames.PERSIST_QUEUE, PAUSE_BEFORE_RETRY_MILLISECONDS, reason);
        pauseBeforeRetry();
        channel.basicNack(lastDeliveryTag, true, true);
    }

    /**
     * Wartet vor dem NACK. Wird der Thread dabei unterbrochen, weil der
     * batch-writer herunterfährt, hören wir sofort auf zu warten und merken
     * uns die Unterbrechung für Spring.
     */
    private void pauseBeforeRetry() {
        try {
            Thread.sleep(PAUSE_BEFORE_RETRY_MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Nimmt aus den gelesenen Nachrichten den Inhalt, der in die Datenbank geht. */
    private List<ChatMessage> toChatMessages(List<ReceivedMessage> messages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ReceivedMessage message : messages) {
            ChatMessage chatMessage = message.chatMessage();
            chatMessages.add(chatMessage);
        }
        return chatMessages;
    }

    /**
     * Der Tag der LETZTEN lesbaren Nachricht, nie der einer abgelehnten:
     * RabbitMQ würde ein ACK dafür mit "unknown delivery tag" beantworten und
     * den Channel schliessen (Spezifikation 3.3, F12).
     */
    private long lastDeliveryTag(List<ReceivedMessage> messages) {
        int lastIndex = messages.size() - 1;
        ReceivedMessage lastMessage = messages.get(lastIndex);
        return lastMessage.deliveryTag();
    }
}
```

> **Reihenfolge der `catch`-Blöcke:** `DataIntegrityViolationException` ist eine Unterklasse von
> `RuntimeException`. Java prüft die `catch`-Blöcke von oben nach unten, deshalb steht der
> speziellere zuerst. Umgekehrt meldet der Compiler einen Fehler.
>
> **Warum beim Einzelschreiben ein NACK für den Rest reicht:** `basicNack(letzterTag, multiple =
> true, …)` betrifft nur Nachrichten, die noch offen sind. Die schon bestätigten oder
> abgelehnten des Stapels überspringt RabbitMQ.

- [x] **Schritt 4: Tests laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl batch-writer test`
Erwartet: alle Tests grün, `MessageBatchListenerIntegrationTest` jetzt mit 5 Tests. Im Protokoll steht `Database refused message …, rejecting it to chat.dlq`.

- [x] **Schritt 5: Committen**

```bash
git add batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchListener.java \
        batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/MessageBatchListenerIntegrationTest.java
git commit -m "feat: nicht speicherbare Nachricht einzeln in die DLQ" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 10: Postgres und batch-writer in docker-compose

**Warum an dieser Stelle:** Erst wenn der Dienst allein richtig arbeitet (Task 2 bis 9), lohnt der Betrieb im Stack. Hier entscheidet sich Szenario S2: alle Dienste laufen, keiner veröffentlicht einen Port.

**Dateien:**
- Anlegen: `batch-writer/Dockerfile`
- Ändern: `docker-compose.yml` (Dienste `postgres` und `batch-writer`, Volume `chat-history`)
- Ändern: `.env.example` (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`)
- Test: `.github/workflows/build.yml` (Job `stack`)

**Schnittstellen:**
- Verbraucht: das Modul aus Task 2 bis 9, `postgres/init/01-schema.sql` (Task 3)
- Stellt bereit: Compose-Dienste `postgres` und `batch-writer` im Netz `chat-net`, ohne Ports; Datenbank `chat` mit Benutzer `chat` (aus `.env`); Volume `chat-history`

- [x] **Schritt 1: Den fehlschlagenden Test schreiben**

In `.github/workflows/build.yml` nach dem Job `images` einfügen:

```yaml
  # Den Stack wirklich starten: kein Dienst veröffentlicht einen Port, und
  # eine Nachricht geht vom chat-service bis in die Tabelle message.
  stack:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - name: .env aus den Beispielwerten
        run: cp .env.example .env
      - name: docker compose up
        run: docker compose up -d --build --wait
      - name: Kein Dienst veröffentlicht einen Port
        run: |
          docker compose ps --format '{{.Service}} {{.Ports}}'
          if docker compose ps --format '{{.Ports}}' | grep -- '->'; then exit 1; fi
      - name: Eine Nachricht landet in der Tabelle
        run: |
          for i in $(seq 1 30); do
            code=$(docker run --rm --network chat-net curlimages/curl -s -o /dev/null -w '%{http_code}' \
              -X POST http://chat-service:8080/messages -H 'Content-Type: application/json' \
              -d '{"roomId":"3f2b1c4e-0000-0000-0000-000000000001","senderId":"ci","senderName":"CI","content":"Rauchtest"}' || true)
            if [ "$code" = "202" ]; then break; fi
            sleep 2
          done
          for i in $(seq 1 30); do
            count=$(docker compose exec -T postgres psql -U chat -d chat -tAc "SELECT count(*) FROM message WHERE content = 'Rauchtest'" || true)
            if [ "$count" = "1" ]; then exit 0; fi
            sleep 2
          done
          exit 1
      - name: Protokolle bei Fehler
        if: failure()
        run: docker compose logs --no-color --tail 200
```

- [x] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: pushen, dann `gh run watch`
Erwartet: Job `stack` rot. Es gibt keinen Dienst `postgres`, `psql` findet ihn nicht, und die Nachricht bleibt in `chat.persist` liegen.

- [x] **Schritt 3: `batch-writer/Dockerfile` anlegen**

`batch-writer/Dockerfile`

```dockerfile
# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Maven liest ALLE Module aus dem Eltern-POM, auch die, die hier nicht
# gebaut werden. Deshalb muss auch das POM des chat-service da sein.
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl batch-writer -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
# Kein EXPOSE: der batch-writer hat keinen Webserver und keinen Port. Er
# verbindet sich selbst mit RabbitMQ und PostgreSQL.
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [x] **Schritt 4: `docker-compose.yml` ergänzen**

`docker-compose.yml`, ganze Datei. Neu sind der Kopfkommentar, `postgres`, `batch-writer` und `volumes`. `rabbitmq` und `chat-service` bleiben unverändert:

```yaml
# Ausbaustufe 2: Broker, chat-service, Datenbank und batch-writer.
# Keycloak und web-gateway kommen in späteren Schritten dazu.
services:

  rabbitmq:
    image: rabbitmq:3.13-management
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD}
    networks:
      - chat-net
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 5s
      timeout: 5s
      retries: 12

  chat-service:
    build:
      context: .
      dockerfile: chat-service/Dockerfile
    environment:
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: ${RABBITMQ_USER}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
    depends_on:
      rabbitmq:
        condition: service_healthy
    networks:
      - chat-net

  # Die Datenbank für den Chat-Verlauf. Das Schema kommt beim ersten Start
  # aus postgres/init, aber nur bei leerem Volume (Spezifikation 4.2).
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      # Die Daten überleben "docker compose down", erst "down -v" löscht sie.
      - chat-history:/var/lib/postgresql/data
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
    networks:
      - chat-net
    healthcheck:
      # -h localhost: erst der richtige Server zählt. Beim ersten Start läuft
      # kurz ein Hilfsserver, der nur über den Unix-Socket erreichbar ist.
      test: ["CMD-SHELL", "pg_isready -h localhost -U \"$${POSTGRES_USER}\" -d \"$${POSTGRES_DB}\""]
      interval: 5s
      timeout: 5s
      retries: 12

  # Der EINZIGE Schreiber in die Datenbank. Holt Nachrichten stapelweise aus
  # chat.persist. Skalierbar mit "--scale batch-writer=N": alle Instanzen
  # teilen sich dieselbe Queue (Competing Consumers, PLANUNG.md 4.2).
  # Deshalb auch kein container_name: --scale braucht vergebene Namen.
  batch-writer:
    build:
      context: .
      dockerfile: batch-writer/Dockerfile
    environment:
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: ${RABBITMQ_USER}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
      POSTGRES_HOST: postgres
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    depends_on:
      rabbitmq:
        condition: service_healthy
      postgres:
        condition: service_healthy
    # Nur ein Sicherheitsnetz: bei Datenbank- oder Broker-Ausfall stürzt der
    # batch-writer nicht ab, sondern versucht es wieder (Spezifikation 3.3).
    restart: unless-stopped
    networks:
      - chat-net

# KEIN ports:-Eintrag in dieser Datei. Der einzige offene Port des
# Gesamtsystems gehört später dem web-gateway.
networks:
  chat-net:
    name: chat-net

volumes:
  # Eigener Name statt des üblichen "postgres-data": so wird kein gleichnamiges
  # Volume eines anderen Projekts im gleichnamigen Ordner übernommen.
  chat-history:
```

> **Fallen in dieser Datei:**
> 1. **`pg_isready` ohne `-h localhost`** meldet schon «bereit», während beim ersten Start noch
>    ein Hilfsserver das Init-Skript ausführt. Dieser Hilfsserver hört nur auf dem Unix-Socket.
> 2. **`$$` statt `$`:** Compose ersetzt `${…}` selbst. `$${POSTGRES_USER}` kommt als
>    `${POSTGRES_USER}` in der Shell des Containers an und wird erst dort ersetzt.
> 3. **Kein `depends_on: postgres` beim `chat-service`:** Er muss Nachrichten auch annehmen,
>    wenn die Datenbank weg ist (S7).
> 4. **Kein `container_name`:** `--scale batch-writer=2` braucht Namen, die Compose selbst
>    vergibt (S6).
> 5. **Eigener Volume-Name `chat-history`:** Ein Volume mit dem üblichen Namen `postgres-data`
>    aus einem anderen Projekt im gleichnamigen Ordner würde sonst übernommen. Dann liefe das
>    Init-Skript nicht (Spezifikation 4.3).

- [x] **Schritt 5: `.env.example` ergänzen**

`.env.example`, ganze Datei:

```bash
# Beispielwerte für den Unterricht. Die echte .env steht in .gitignore.
RABBITMQ_USER=chat
RABBITMQ_PASSWORD=bitte-lokal-aendern

# PostgreSQL: Das Image legt beim ersten Start die Datenbank POSTGRES_DB und
# den Benutzer POSTGRES_USER an. Der batch-writer meldet sich genau so an.
POSTGRES_DB=chat
POSTGRES_USER=chat
POSTGRES_PASSWORD=bitte-lokal-aendern
```

Danach die lokale `.env` neu erzeugen: `cp .env.example .env`. `git check-ignore -v .env` bestätigt, dass sie nicht ins Repository kommt.

- [x] **Schritt 6: Test laufen lassen und grün bestätigen**

Ausführen: pushen, dann `gh run watch`
Erwartet: `maven`, `images` und `stack` grün. `stack` zeigt vier Dienste ohne `->` in den Ports und findet die Nachricht `Rauchtest` in der Tabelle.

- [x] **Schritt 7: Committen**

```bash
git add batch-writer/Dockerfile docker-compose.yml .env.example .github/workflows/build.yml
git commit -m "chore: Postgres und batch-writer in docker-compose" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 11: Abnahmeskript für die Szenarien S2 bis S8

**Warum an dieser Stelle:** Das Skript braucht den ganzen Stack (Task 10). Es prüft alles so, wie die Abnahme es tut: in derselben Reihenfolge, auf demselben Stack, ohne Aufräumen dazwischen, gemessen von innen mit `psql` und `rabbitmqctl`. Der Auftrag verlangt ausdrücklich, die Szenarien so nachzustellen, dass man sie selbst ausführen kann.

**Dateien:**
- Anlegen: `scripts/abnahme.sh`
- Anlegen: `.gitattributes`
- Ändern: `.github/workflows/build.yml` (Job `stack` wird zu Job `abnahme`)
- Ändern: `docs/spec-batch-writer.md` (Abschnitt 6, S8: Befehl lässt den Build-Ordner `target/` aus)

**Schnittstellen:**
- Verbraucht: den ganzen Stack aus Task 10, `.env.example`
- Stellt bereit: `bash scripts/abnahme.sh` mit einer Tabelle «gemessen / erwartet» je Szenario und Exit-Code 0 nur, wenn S2 bis S8 alle bestehen

- [x] **Schritt 1: Das Skript schreiben**

Das Skript ist hier selbst der Test. Jede Szenario-Funktion misst und meldet `PASS` oder `FAIL` mit dem gemessenen Wert.

`scripts/abnahme.sh`

```bash
#!/usr/bin/env bash
# Abnahme des batch-writer: stellt die Szenarien S2 bis S8 aus «Bewertung 1»
# nach, in derselben Reihenfolge und auf demselben Stack, ohne Aufräumen
# dazwischen. S1 ist "mvn clean test" und läuft getrennt davon.
#
# Gemessen wird wie in der Abnahme von innen: psql im Postgres-Container,
# rabbitmqctl im RabbitMQ-Container. Gesendet wird über einen curl-Container
# im Netz chat-net, wie im Plan des chat-service.
#
# Aufruf im Wurzelverzeichnis:   bash scripts/abnahme.sh
#
# ACHTUNG: Das Skript beginnt mit "docker compose down -v" und löscht damit
# alle Daten der Datenbank.
#
# Ergebnis: eine Tabelle "gemessen / erwartet" und Exit-Code 0, wenn alle
# Szenarien bestanden sind, sonst 1.

set -u
# Git Bash unter Windows würde Argumente, die mit "/" beginnen, in
# Windows-Pfade umschreiben. Das schalten wir ab.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.." || exit 1

if [ ! -f .env ]; then
  cp .env.example .env
fi
# Die .env Zeile für Zeile übernehmen. Ein Windows-Zeilenende (CR) wird
# abgeschnitten, sonst hinge es an jedem Wert: psql -U "chat\r" fände den
# Benutzer nicht. Leere Zeilen und Kommentare werden übersprungen.
while IFS= read -r line; do
  line=${line%$'\r'}
  case "$line" in
    '' | '#'*) continue ;;
  esac
  export "$line"
done < .env

ROOM_ID="3f2b1c4e-0000-0000-0000-000000000001"
SUMMARY=""
FAILURES=0

# ------------------------------------------------------------------ Hilfen

# Führt eine SQL-Abfrage in der Chat-Datenbank aus und gibt nur den Wert aus.
sql() {
  docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1" 2>/dev/null
}

# Gibt eine Spalte (messages oder consumers) einer Queue aus.
queue_value() {
  docker compose exec -T rabbitmq rabbitmqctl list_queues -q name "$2" 2>/dev/null \
    | awk -v queue="$1" '$1 == queue { print $2 }'
}

# Zählt Zeilen einer Eingabe, ohne die Leerzeichen, die macOS davor setzt.
count_lines() {
  wc -l | tr -d ' '
}

# Zählt die Zeilen, deren Text mit der Markierung eines Szenarios beginnt.
count_marked() {
  sql "SELECT count(*) FROM message WHERE content LIKE '$1 %'"
}

# Schickt $1 Nachrichten mit dem Text "$2 1", "$2 2", ... an POST /messages
# und gibt aus, wie viele davon mit 202 angenommen wurden.
send_messages() {
  docker run --rm --network chat-net curlimages/curl sh -c "
    i=1
    while [ \$i -le $1 ]; do
      curl -s -o /dev/null -w '%{http_code}\n' -X POST http://chat-service:8080/messages \
        -H 'Content-Type: application/json' \
        -d \"{\\\"roomId\\\":\\\"$ROOM_ID\\\",\\\"senderId\\\":\\\"abnahme\\\",\\\"senderName\\\":\\\"Abnahme\\\",\\\"content\\\":\\\"$2 \$i\\\"}\"
      i=\$((i + 1))
    done" | grep -c '^202$'
}

# Legt einen Body direkt in chat.persist, nur mit content_type – wie in S5.
publish_raw() {
  docker compose exec -T rabbitmq rabbitmqadmin -u "$RABBITMQ_USER" -p "$RABBITMQ_PASSWORD" \
    publish exchange=amq.default routing_key=chat.persist \
    properties='{"content_type":"application/json"}' payload="$1"
}

# Wartet höchstens $1 Sekunden, bis der Befehl dahinter gelingt. Prüft alle 2 s.
wait_until() {
  local deadline=$((SECONDS + $1))
  shift
  while [ "$SECONDS" -lt "$deadline" ]; do
    if "$@"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# Hält ein Ergebnis fest: Szenario, gemessen, erwartet, 0 = bestanden.
report() {
  local verdict="PASS"
  if [ "$4" -ne 0 ]; then
    verdict="FAIL"
    FAILURES=$((FAILURES + 1))
  fi
  local line
  line=$(printf '%-3s %-4s gemessen: %s | erwartet: %s' "$1" "$verdict" "$2" "$3")
  echo "$line"
  SUMMARY="$SUMMARY$line"$'\n'
}

# --------------------------------------------- Bedingungen für wait_until

services_are_running() {
  local running
  running=$(docker compose ps --status running --services | sort | tr '\n' ' ')
  [ "$running" = "batch-writer chat-service postgres rabbitmq " ]
}

chat_service_answers() {
  local code
  code=$(docker run --rm --network chat-net curlimages/curl -s -o /dev/null -w '%{http_code}' \
    http://chat-service:8080/messages)
  [ "$code" != "000" ]
}

persist_queue_is_empty() {
  [ "$(queue_value chat.persist messages)" = "0" ]
}

marked_rows_are() {
  [ "$(count_marked "$1")" = "$2" ]
}

consumers_are() {
  [ "$(queue_value chat.persist consumers)" = "$1" ]
}

# Findet Klassen und Methoden in batch-writer/src ohne Kommentar direkt
# darüber. Annotationen und ihre Fortsetzungszeilen dürfen dazwischen stehen.
check_comments() {
  find batch-writer/src -name '*.java' | sort | while read -r file; do
    awk -v file="$file" '
      function is_declaration(text,    trimmed) {
        trimmed = text
        sub(/^[ \t]+/, "", trimmed)
        if (trimmed ~ /^(public |protected |private )?(static )?(final )?(abstract )?(class|record|interface|enum) /) return 1
        if (text !~ /^    [^ ]/) return 0
        if (trimmed ~ /;[ \t]*$/) return 0
        if (trimmed ~ /^(return|if|for|while|switch|catch|try|else|throw|new|do)[ (]/) return 0
        if (trimmed ~ /^(public |protected |private )?(static )?(final )?(synchronized )?[A-Za-z0-9_<>?,. \[\]]+ [a-zA-Z0-9_]+\(/) return 1
        if (trimmed ~ /^(public |protected |private )?[A-Z][A-Za-z0-9_]*\(/) return 1
        return 0
      }
      function is_comment_end(trimmed) {
        return trimmed ~ /\*\/[ \t]*$/ || trimmed ~ /^\/\//
      }
      { sub(/\r$/, ""); lines[NR] = $0 }
      END {
        for (n = 1; n <= NR; n++) {
          if (!is_declaration(lines[n])) continue
          indent = match(lines[n], /[^ ]/) - 1
          k = n - 1
          above = ""
          while (k > 0) {
            above = lines[k]
            sub(/^[ \t]+/, "", above)
            above_indent = match(lines[k], /[^ ]/) - 1
            if (is_comment_end(above)) break
            if (above == "" || substr(above, 1, 1) == "@" || above_indent > indent) { k--; continue }
            break
          }
          if (k == 0 || !is_comment_end(above)) {
            print file ":" n ": " lines[n]
          }
        }
      }
    ' "$file"
  done
}

# ---------------------------------------------------------------- Szenarien

scenario_s2() {
  echo "== S2: frischer Start, .env aus .env.example, docker compose up -d --build"
  docker compose down -v --remove-orphans >/dev/null 2>&1
  docker compose up -d --build
  wait_until 180 services_are_running
  local ok=$?
  wait_until 120 chat_service_answers
  local running
  running=$(docker compose ps --status running --services | sort | tr '\n' ' ')
  local published
  published=$(docker compose ps --format '{{.Service}} {{.Ports}}' | grep -- '->' | count_lines)
  if [ "$published" != "0" ]; then
    ok=1
  fi
  report S2 "laufend: ${running}| veröffentlichte Ports: $published" "4 Dienste laufen, 0 veröffentlichte Ports" "$ok"
}

scenario_s3() {
  echo "== S3: 1000 Nachrichten über POST /messages"
  local accepted
  accepted=$(send_messages 1000 S3)
  local start=$SECONDS
  wait_until 60 s3_done
  local ok=$?
  local seconds=$((SECONDS - start))
  local rows
  rows=$(count_marked S3)
  local waiting
  waiting=$(queue_value chat.persist messages)
  report S3 "202: $accepted, Zeilen: $rows, chat.persist: $waiting, nach $seconds s" "1000 Zeilen, Queue leer, höchstens 60 s" "$ok"
}

s3_done() {
  marked_rows_are S3 1000 && persist_queue_is_empty
}

scenario_s4() {
  echo "== S4: batch-writer gestoppt, 1000 Nachrichten, dann gestartet"
  docker compose stop batch-writer
  # Erst NACH dem Stopp lesen: beim Beenden der Verbindungen schreibt
  # PostgreSQL deren Statistik fest.
  sleep 2
  local before
  before=$(sql "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()")
  local accepted
  accepted=$(send_messages 1000 S4)
  local waiting
  waiting=$(queue_value chat.persist messages)
  docker compose start batch-writer
  # Hier nur rabbitmqctl und kein psql: jede Abfrage wäre selbst eine Transaktion.
  wait_until 90 persist_queue_is_empty
  # PostgreSQL führt seine Statistik verzögert nach.
  sleep 12
  local after
  after=$(sql "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()")
  local transactions=$((after - before))
  local rows
  rows=$(count_marked S4)
  local writing
  writing=$(sql "SELECT count(DISTINCT xmin::text) FROM message WHERE content LIKE 'S4 %'")
  local ok=1
  if [ "$rows" = "1000" ] && [ "$transactions" -le 100 ]; then
    ok=0
  fi
  report S4 "202: $accepted, wartend: $waiting, Zeilen: $rows, Transaktionen: $transactions (schreibend: $writing)" "1000 Zeilen, höchstens 100 Transaktionen" "$ok"
}

scenario_s5() {
  echo "== S5: dieselbe Nachricht zweimal direkt in chat.persist, nur content_type"
  local id
  id=$(sql "SELECT gen_random_uuid()")
  local body="{\"id\":\"$id\",\"roomId\":\"$ROOM_ID\",\"senderId\":\"abnahme\",\"senderName\":\"Abnahme\",\"content\":\"S5 Duplikat\",\"sentAt\":\"2026-09-29T13:38:12.974043374Z\"}"
  publish_raw "$body"
  publish_raw "$body"
  wait_until 30 persist_queue_is_empty
  sleep 3
  local rows
  rows=$(sql "SELECT count(*) FROM message WHERE id = '$id'")
  local dead
  dead=$(queue_value chat.dlq messages)
  local ok=1
  if [ "$rows" = "1" ] && [ "$dead" = "0" ]; then
    ok=0
  fi
  report S5 "Zeilen mit dieser id: $rows, chat.dlq: $dead" "1 Zeile, chat.dlq 0" "$ok"
}

scenario_s6() {
  echo "== S6: zwei Instanzen, 1000 Nachrichten"
  docker compose up -d --scale batch-writer=2
  wait_until 90 consumers_are 2
  # Das erste "up" ohne --build erstellt die gebauten Dienste neu, auch den
  # chat-service. Gesendet wird erst, wenn er wieder antwortet.
  wait_until 120 chat_service_answers
  local consumers
  consumers=$(queue_value chat.persist consumers)
  local since
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  local accepted
  accepted=$(send_messages 1000 S6)
  wait_until 60 s6_done
  local rows
  rows=$(count_marked S6)
  local distinct
  distinct=$(sql "SELECT count(DISTINCT id) FROM message WHERE content LIKE 'S6 %'")
  # Nur Protokollzeilen seit dem Senden: so zählt, wer S6 wirklich geschrieben hat.
  local instances
  instances=$(docker compose logs --no-color --since "$since" batch-writer 2>/dev/null | grep 'Stored batch' | awk '{ print $1 }' | sort -u | count_lines)
  local ok=1
  if [ "$consumers" = "2" ] && [ "$instances" = "2" ] && [ "$rows" = "1000" ] && [ "$distinct" = "1000" ]; then
    ok=0
  fi
  report S6 "202: $accepted, Verbraucher: $consumers, Instanzen mit Stapeln: $instances, Zeilen: $rows, verschiedene ids: $distinct" "2 Verbraucher, beide schreiben Stapel, 1000 Zeilen, keine doppelt" "$ok"
}

s6_done() {
  marked_rows_are S6 1000 && persist_queue_is_empty
}

scenario_s7() {
  echo "== S7: Postgres gestoppt, 300 Nachrichten, nach 15 s wieder gestartet"
  docker compose stop postgres
  local accepted
  accepted=$(send_messages 300 S7)
  sleep 15
  docker compose start postgres
  local start=$SECONDS
  wait_until 90 marked_rows_are S7 300
  local ok=$?
  local seconds=$((SECONDS - start))
  local rows
  rows=$(count_marked S7)
  local dead
  dead=$(queue_value chat.dlq messages)
  local containers
  containers=$(docker compose ps -q batch-writer)
  local restarts
  restarts=$(docker inspect -f '{{.RestartCount}}' $containers | sort -u | tr '\n' ' ')
  local states
  states=$(docker inspect -f '{{.State.Status}}' $containers | sort -u | tr '\n' ' ')
  if [ "$dead" != "0" ] || [ "$restarts" != "0 " ] || [ "$states" != "running " ]; then
    ok=1
  fi
  report S7 "202: $accepted, Zeilen: $rows nach $seconds s, chat.dlq: $dead, Neustarts: ${restarts}, Zustand: $states" "300 Zeilen in höchstens 90 s, chat.dlq 0, kein Neustart" "$ok"
}

scenario_s8() {
  echo "== S8: Quelltext von batch-writer/"
  local stream_hits
  stream_hits=$(grep -rin --exclude-dir=target 'stream' batch-writer/ | count_lines)
  local missing
  missing=$(check_comments | count_lines)
  check_comments
  local env_tracked
  env_tracked=$(git ls-files .env | count_lines)
  local env_history
  env_history=$(git log --all --format=%h -- .env | count_lines)
  # Ein flacher Klon kennt nur den letzten Commit; dann wäre die Suche im
  # Verlauf wertlos. Deshalb zählt auch, wie viele Commits geprüft wurden.
  local commits
  commits=$(git rev-list --all --count)
  local ok=1
  if [ "$stream_hits" = "0" ] && [ "$missing" = "0" ] && [ "$env_tracked" = "0" ] && [ "$env_history" = "0" ] && [ "$commits" -gt 1 ]; then
    ok=0
  fi
  report S8 "Treffer 'stream': $stream_hits, ohne Kommentar: $missing, .env im Repo: $env_tracked, .env im Verlauf: $env_history (geprüft: $commits Commits)" "alles 0, ganzer Verlauf" "$ok"
}

# ------------------------------------------------------------------- Ablauf

scenario_s2
scenario_s3
scenario_s4
scenario_s5
scenario_s6
scenario_s7
scenario_s8

echo
echo "================================ Ergebnis ================================"
printf '%s' "$SUMMARY"
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES Szenario(s) nicht bestanden."
  exit 1
fi
echo "Alle Szenarien bestanden."
```

> **Fallen im Skript:**
> 1. **Der `chat-service` braucht nach `up` einige Sekunden.** `docker compose up --wait` wartet
>    bei einem Dienst ohne Healthcheck nur, bis der Container läuft, nicht bis Spring bereit ist.
>    `curl` bekäme «Verbindung abgelehnt» (Exit 7). Deshalb wartet `chat_service_answers`, bis
>    HTTP antwortet (`GET /messages` → 405).
> 2. **S4 misst keine eigene Last mit.** `xact_commit` wird erst **nach** dem Stopp gelesen, denn
>    beim Beenden der Verbindungen schreibt PostgreSQL deren Statistik fest. Während der Messung
>    wird nur mit `rabbitmqctl` gewartet, denn jede `psql`-Abfrage wäre selbst eine Transaktion.
>    Danach wartet das Skript 12 s, weil PostgreSQL die Statistik verzögert nachführt.
> 3. **`curlimages/curl` ist ein BusyBox-Image:** `sh` statt `bash`, die Schleife ist entsprechend
>    geschrieben.
> 4. **Kommentarprüfung:** Die Schlusszeile eines Javadoc (` */`) ist eine Stelle tiefer
>    eingerückt als die Methode. Das darf nicht mit der Fortsetzungszeile einer Annotation
>    verwechselt werden. Die Prüfung erkennt Kommentarzeilen deshalb zuerst.
> 5. **Windows:** Git Bash schriebe Argumente mit `/` in Pfade um (`MSYS_NO_PATHCONV=1`), und
>    ohne `.gitattributes` bekäme das Skript CRLF-Zeilenenden.
> 6. **Das erste `up` ohne `--build` erstellt die gebauten Dienste neu** (beobachtet mit Docker
>    Compose 2.38 am 29.09.2026). In S6 ist `up -d --scale` genau dieses erste `up` nach S2. Auch
>    der `chat-service` startet dabei neu und nimmt einige Sekunden keine Nachrichten an. S6
>    wartet deshalb vor dem Senden, bis er wieder antwortet, und zählt nur Protokollzeilen seit
>    dem Senden.

- [x] **Schritt 2: Zeilenenden festlegen**

`.gitattributes`

```
# Skripte, die unter Linux laufen, brauchen LF-Zeilenenden. Diese Regel gilt
# auch dann, wenn jemand unter Windows mit core.autocrlf=true auscheckt.
*.sh  text eol=lf
*.sql text eol=lf
# Die .env entsteht als Kopie dieser Datei und wird von Bash eingelesen. Mit
# CRLF hinge an jedem Wert ein unsichtbares CR, z.B. "chat\r" als Benutzer.
.env.example text eol=lf
```

- [x] **Schritt 3: Den CI-Job `stack` durch die Abnahme ersetzen**

`.github/workflows/build.yml`, ganze Datei. Der Job `abnahme` prüft alles, was `stack` geprüft hat, und mehr:

```yaml
# Baut, testet und nimmt das Projekt bei jedem Push ab.
#
# Warum: «Läuft» sagen wir erst, wenn es wirklich gelaufen ist (CLAUDE.md).
# Die Maschinen von GitHub haben Java und Docker. Hier laufen dieselben Tests
# wie lokal mit "mvn clean test", mit echten Containern über Testcontainers.
name: build

on:
  push:
  workflow_dispatch:

jobs:

  # Szenario S1: alle Tests aller Module in einem Lauf.
  maven:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: mvn clean test
        run: mvn -B clean test

  # Alle Images bauen, genau wie "docker compose up --build".
  # Findet z.B. eine fehlende COPY-Zeile in einem Dockerfile.
  images:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - name: .env aus den Beispielwerten
        run: cp .env.example .env
      - name: docker compose build
        run: docker compose build

  # Szenarien S2 bis S8 wie in der Abnahme: frischer Checkout, .env aus
  # .env.example, alles auf demselben Stack (scripts/abnahme.sh).
  abnahme:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
        with:
          # S8 sucht .env im ganzen Verlauf, dafür braucht es alle Commits.
          fetch-depth: 0
      - name: .env aus den Beispielwerten
        run: cp .env.example .env
      - name: Abnahme S2 bis S8
        run: bash scripts/abnahme.sh
      - name: Protokolle bei Fehler
        if: failure()
        run: docker compose logs --no-color --tail 300
```

- [x] **Schritt 4: Spezifikation, Abschnitt 6, Befehl für S8 anpassen**

In der Zeile S8 `grep -rin stream batch-writer/` ersetzen durch `grep -rin --exclude-dir=target stream batch-writer/`. Nach `mvn clean test` liegt unter `batch-writer/target/` der Build-Ordner. Er ist kein Quelltext, und seine Testberichte enthalten Wörter aus Protokollen.

- [x] **Schritt 5: Skript ausführbar machen, laufen lassen und alle Szenarien grün bestätigen**

Ausführen:
```bash
git update-index --add --chmod=+x scripts/abnahme.sh
bash scripts/abnahme.sh
```
Erwartet: sieben Zeilen `PASS` (S2 bis S8), am Ende `Alle Szenarien bestanden.`, Exit-Code 0. Im CI ist der Job `abnahme` grün. Die gemessenen Werte (Transaktionen in S4, Sekunden in S7) kommen in Schritt 6 in die Abschluss-Prüfung dieses Plans.

- [x] **Schritt 6: Committen**

```bash
git add scripts/abnahme.sh .gitattributes .github/workflows/build.yml docs/spec-batch-writer.md
git commit -m "test: Abnahmeskript für die Szenarien S2 bis S8" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 12: README für batch-writer und Abnahme

**Warum an dieser Stelle:** Die README beschreibt, was es jetzt wirklich gibt, und wie man es startet und prüft. Das steht erst nach Task 11 fest.

**Dateien:**
- Ändern: `README.md` (Abschnitte «Bauen, testen, starten», «Was gebaut wird», «Dokumente»)

**Schnittstellen:**
- Verbraucht: alles aus Task 1 bis 11
- Stellt bereit: Befehle zum Bauen, Starten, Messen und zur Abnahme; Tabelle «Stand» mit `batch-writer` und `postgres` = vorhanden

- [x] **Schritt 1: «Bauen, testen, starten» ersetzen**

````markdown
```bash
mvn clean test                   # alle Tests, RabbitMQ und PostgreSQL kommen per Testcontainers
docker compose up -d --build     # RabbitMQ, chat-service, PostgreSQL und batch-writer im Netz chat-net
docker compose down -v           # alles stoppen UND die Datenbank löschen (frischer Start)
bash scripts/abnahme.sh          # Szenarien S2 bis S8 nachstellen (beginnt mit "down -v"!)
```

Kein Dienst veröffentlicht einen Port auf den Host. Der einzige offene Port des Gesamtsystems
gehört später dem Gateway. Gesendet und gemessen wird deshalb von innen, zum Beispiel:

```bash
docker run --rm --network chat-net curlimages/curl -s -X POST http://chat-service:8080/messages \
  -H 'Content-Type: application/json' \
  -d '{"roomId":"3f2b1c4e-0000-0000-0000-000000000001","senderId":"anna","senderName":"Anna Muster","content":"Hallo"}'
docker compose exec postgres psql -U chat -P pager=off -c "SELECT sender_name, content, sent_at FROM message ORDER BY sent_at DESC LIMIT 5"
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

Das Schema der Datenbank (`postgres/init/01-schema.sql`) läuft nur beim ersten Start mit leerem
Volume. Nach einer Änderung daran: `docker compose down -v`.

Jeder Push läuft in GitHub Actions durch `mvn clean test`, den Bau aller Images und die Abnahme
(`.github/workflows/build.yml`).
````

> **Falle (beim Prüfen gefunden, 29.09.2026):** Am Terminal öffnet `psql` für seine Ausgabe den
> Pager `less` und wartet mit «(END)» auf ein `q`. `-P pager=off` schreibt die Tabelle einfach
> hin; das ist für Mitlernende weniger verwirrend.

- [x] **Schritt 2: Tabelle «Was gebaut wird» nachführen**

```markdown
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank: liest `chat.persist` in Stapeln bis 500, ein INSERT pro Stapel, ACK nach dem COMMIT | vorhanden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf in der Tabelle `message` | vorhanden |
```

- [x] **Schritt 3: «Dokumente» um Spezifikation und Plan ergänzen**

```markdown
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation des `batch-writer`:
  Vertrag, Verhalten in jedem Fehlerfall, Datenmodell, Abnahmekriterien.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzungsplan des `batch-writer`,
  Schritt für Schritt mit Test.
```

- [x] **Schritt 4: Befehle prüfen**

Die Befehle aus Schritt 1 einmal am laufenden Stack ausführen.
Erwartet: `psql -U chat` zeigt die Nachricht, `rabbitmqctl` zeigt `chat.persist 0 1`.

- [x] **Schritt 5: Committen**

```bash
git add README.md
git commit -m "docs: README für batch-writer und Abnahme" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Korrekturen aus dem Abschluss-Review

Ein unabhängiges Code-Review am 29.09.2026 fand keine kritischen Fehler, aber fünf Stellen, die vor
der Abgabe korrigiert werden. Jede Korrektur ist ein eigener Commit mit einem Nachweis, der vorher
rot war. Das Häkchen kommt im selben Commit wie die Korrektur.

| # | Befund | Folge ohne Korrektur | Nachweis: vorher → nachher | Commit |
|---|---|---|---|---|
| K1 | Unter Windows mit `core.autocrlf=true` wird `.env.example` mit CRLF ausgecheckt. Eine Bash, die die `.env` einliest (Linux, WSL, macOS), hängt jedem Wert ein `\r` an | `psql -U "chat\r"` findet den Benutzer nicht; das Abnahmeskript und jedes andere Prüfskript, das die `.env` einliest, melden falsche Fehler | `git check-attr eol -- .env.example`: `unspecified` → `lf` | `fix: .env.example immer mit LF-Zeilenenden` |
| K2 | Das erste `docker compose up` ohne `--build` erstellt die gebauten Dienste neu, auch den `chat-service`. In S6 ist das genau das `up --scale`. Das Skript sendete direkt danach und verlangte nicht, dass beide Instanzen gearbeitet haben | falsches FAIL auf einem langsamen Rechner; S6 bestünde auch, wenn nur eine Instanz arbeitete | S6 wartet auf den `chat-service`, `Instanzen mit Stapeln: 2` ist Bedingung | `fix: Abnahme wartet in S6 auf den neu erstellten chat-service` |
| K3 | S8 sucht `.env` im Verlauf, im CI aber auf einem flachen Checkout mit einem einzigen Commit | die Prüfung sah den Verlauf gar nicht | Checkout mit `--depth=1` → ganzer Verlauf; S8 meldet die Zahl der geprüften Commits | `ci: Abnahme prüft .env im ganzen Verlauf` |
| K4 | Die Testberichte unter `batch-writer/target/` enthalten die Protokolle bestandener Tests | ein `grep -ri stream batch-writer/` nach `mvn clean test` könnte Wörter aus Protokollen finden (S8) | `grep -c '<system-out>'` im Bericht eines bestandenen Tests: `1` → `0` | `chore: Testberichte des batch-writer ohne Protokolle` |
| K5 | README und Kommentar in `MessageRepository` sprachen von «einem INSERT pro Stapel». Richtig ist, was die Spezifikation in Abschnitt 5 sagt: eine Transaktion, mehrzeilige INSERTs zu höchstens 128 Zeilen | Widerspruch zwischen den Dokumenten | Text stimmt mit Spezifikation 5 überein | `docs: ein Stapel ist eine Transaktion, nicht ein INSERT` |

- [x] **K1** `.env.example` mit LF
- [x] **K2** S6 wartet auf den neu erstellten `chat-service` und prüft beide Instanzen
- [x] **K3** S8 prüft den ganzen Verlauf
- [ ] **K4** Testberichte ohne Protokolle bestandener Tests
- [ ] **K5** «eine Transaktion pro Stapel» überall gleich

---

## Abschluss-Prüfung

- [ ] `mvn -q clean test` — alle Tests grün, in einem Lauf
- [ ] `bash scripts/abnahme.sh` — S2 bis S8 alle `PASS`; gemessene Werte hier eintragen
- [ ] `grep -n "ports:" docker-compose.yml` — keine Treffer
- [ ] `git ls-files .env` — keine Ausgabe
- [ ] `git log --oneline` — Spezifikation, dieser Plan und die Tasks 1 bis 12 in dieser Reihenfolge, ein Thema pro Commit
- [ ] Jede Aufgabe oben ist abgehakt, und jede beim Bauen entdeckte Falle steht bei ihrer Aufgabe

**Damit ist Schritt 4 der Umsetzungsreihenfolge für den Schreibweg erreicht:** Jede Nachricht,
die der `chat-service` annimmt, landet dauerhaft in der Datenbank, auch bei Duplikaten,
Rückstau, mehreren Instanzen und einem Datenbank-Ausfall. Der Lesepfad (Historie) ist der
nächste Schritt und nicht Teil dieses Plans.
