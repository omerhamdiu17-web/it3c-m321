#!/usr/bin/env bash
#
# Messreihe für Schritt 6. Zwei Versuche:
#
#   durchsatz  Der load-generator schickt 100'000 Nachrichten pro Minute.
#              Hält das System mit? Wie tief wird die Queue chat.persist?
#              Und auf welche chat-service-Instanzen verteilt sich die Last?
#
#   stau       Zuerst läuft die Last OHNE batch-writer, die Queue füllt sich.
#              Dann werden N batch-writer gestartet und wir messen, wie
#              schnell sie den Stau abbauen. Das ist der eigentliche
#              Durchsatz des Schreibwegs und der Effekt von
#              "--scale batch-writer=N" (Competing Consumers).
#
# Voraussetzung: das System läuft ("docker compose up -d --build").
# Aufruf aus dem Projektordner:
#
#   scripts/messreihe.sh
#
# Stellschrauben (Umgebungsvariablen, alle optional):
#   PHASES                    welche Versuche             (Vorgabe "durchsatz stau")
#   LOAD_MESSAGES_PER_MINUTE  Zielrate                    (Vorgabe 100000)
#   LOAD_DURATION_SECONDS     Dauer der Last "durchsatz"  (Vorgabe 60)
#   FILL_SECONDS              Dauer der Last "stau"       (Vorgabe 120)
#   WRITER_COUNTS             Anzahl batch-writer je Lauf (Vorgabe "1 2 3")
#   CHAT_SERVICE_COUNT        Anzahl chat-service         (Vorgabe 1)
#   LOAD_JAVA_OPTIONS         Java-Optionen für den load-generator (Vorgabe leer)
#   RESULT_DIR                wohin die Ergebnisse gehen  (Vorgabe messreihe)
#
# Ergebnisse: RESULT_DIR/ergebnis.md (Tabellen), dazu pro Lauf eine CSV mit
# dem Verlauf, das Protokoll des load-generator und "docker stats".

set -euo pipefail

PHASES="${PHASES:-durchsatz stau}"
export LOAD_MESSAGES_PER_MINUTE="${LOAD_MESSAGES_PER_MINUTE:-100000}"
LOAD_DURATION_SECONDS="${LOAD_DURATION_SECONDS:-60}"
FILL_SECONDS="${FILL_SECONDS:-120}"
WRITER_COUNTS="${WRITER_COUNTS:-1 2 3}"
CHAT_SERVICE_COUNT="${CHAT_SERVICE_COUNT:-1}"
export LOAD_JAVA_OPTIONS="${LOAD_JAVA_OPTIONS:-}"
RESULT_DIR="${RESULT_DIR:-messreihe}"
RESULT_FILE="$RESULT_DIR/ergebnis.md"

# Der Raum "Lasttest" aus postgres/init/03-rooms.sql.
LOAD_ROOM_ID="00000000-0000-0000-0000-000000000003"
SAMPLE_SECONDS=5
# So lange warten wir höchstens, bis die Queue leer ist.
DRAIN_TIMEOUT_SECONDS=600

mkdir -p "$RESULT_DIR"

# ---------------------------------------------------------------------------
# Kleine Messfühler
# ---------------------------------------------------------------------------

# Wie viele Nachrichten warten in chat.persist?
queue_depth() {
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages \
    | awk '$1 == "chat.persist" { print $2 }'
}

# Wie viele Verbraucher (batch-writer) hängen an chat.persist?
queue_consumers() {
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name consumers \
    | awk '$1 == "chat.persist" { print $2 }'
}

# Wie viele Nachrichten des Raums Lasttest stehen in der Datenbank?
stored_messages() {
  docker compose exec -T postgres psql -U postgres -d chat -tAc \
    "SELECT count(*) FROM message WHERE room_id = '$LOAD_ROOM_ID'"
}

# Wie viele chat-service-Container laufen gerade?
running_chat_services() {
  docker compose ps -q chat-service | wc -l | xargs
}

# Jetzt in Millisekunden. GNU date (Linux, Git Bash) kann %N, macOS nicht;
# dort rechnen wir mit ganzen Sekunden.
now_millis() {
  local value
  value=$(date +%s%3N)
  if [[ "$value" == *N ]]; then
    value=$(( $(date +%s) * 1000 ))
  fi
  echo "$value"
}

# Beschreibt den Rechner. nproc, free und /proc/cpuinfo gibt es nur unter
# Linux; auf einem Mac oder unter Git Bash steht dann "unbekannt".
describe_machine() {
  local cores="unbekannt"
  local memory="unbekannt"
  local cpu="unbekannt"
  if command -v nproc > /dev/null; then
    cores=$(nproc)
  fi
  if command -v free > /dev/null; then
    memory=$(free -g | awk '/Mem:/ { print $2 }')
  fi
  if [ -r /proc/cpuinfo ]; then
    cpu=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)
  fi
  echo "$cores CPU-Kerne, $memory GB Arbeitsspeicher, $cpu"
}

# ---------------------------------------------------------------------------
# Warten und Skalieren
# ---------------------------------------------------------------------------

# Stellt die Anzahl der Instanzen ein. chat-service und batch-writer stehen
# im SELBEN Befehl, sonst setzt der eine Befehl den anderen Dienst zurück.
scale_services() {
  local writers="$1"
  docker compose up -d --scale chat-service="$CHAT_SERVICE_COUNT" --scale batch-writer="$writers" \
    chat-service batch-writer
}

# Wartet, bis genau N batch-writer an der Queue hängen.
wait_for_consumers() {
  local expected="$1"
  for attempt in $(seq 1 120); do
    if [ "$(queue_consumers)" = "$expected" ]; then
      return 0
    fi
    sleep 1
  done
  echo "Es hängen nicht $expected batch-writer an chat.persist" >&2
  exit 1
}

# Wartet, bis die Queue leer ist (Reste eines vorigen Laufs).
wait_for_empty_queue() {
  for attempt in $(seq 1 "$DRAIN_TIMEOUT_SECONDS"); do
    if [ "$(queue_depth)" = "0" ]; then
      return 0
    fi
    sleep 1
  done
  echo "chat.persist wird nicht leer" >&2
  exit 1
}

# Startet den load-generator. --no-deps ist wichtig: ohne würde "run" die
# Dienste, von denen er abhängt, auf EINE Instanz zurücksetzen.
run_load_generator() {
  local duration="$1"
  local log_file="$2"
  LOAD_DURATION_SECONDS="$duration" docker compose --profile load run --no-deps --rm load-generator \
    > "$log_file" 2>&1
}

# Zieht einen Wert aus der Zusammenfassung des load-generator, z.B.
# "Load finished after 60.4 s: accepted 99960, failed 0, that is 99300 messages per minute"
summary_value() {
  local log_file="$1"
  local pattern="$2"
  # "|| true": fehlt die Zeile (load-generator abgestürzt), bricht das
  # Skript nicht ab, die Zelle in der Tabelle bleibt leer.
  { grep "Load finished" "$log_file" || true; } | tail -1 | sed -E "s/.*$pattern.*/\1/"
}

# Die Verteilung auf die chat-service-Instanzen als eine Zeile.
instance_distribution() {
  local log_file="$1"
  { grep "chat-service instance" "$log_file" || true; } \
    | sed -E 's/.*chat-service instance ([^ ]+) accepted ([0-9]+).*/\1: \2/' \
    | paste -sd ',' - | sed 's/,/, /g'
}

# ---------------------------------------------------------------------------
# Versuch "durchsatz"
# ---------------------------------------------------------------------------

measure_throughput() {
  {
    echo
    echo "### Durchsatz: $LOAD_MESSAGES_PER_MINUTE Nachrichten/Minute für $LOAD_DURATION_SECONDS s"
    echo
    echo "| batch-writer | chat-service | angenommen | Ist-Rate (pro Minute) | Fehler | grösste Queue-Tiefe | Queue leer nach Lastende | gespeichert | Verteilung auf chat-service-Instanzen |"
    echo "|---:|---:|---:|---:|---:|---:|---:|---:|---|"
  } >> "$RESULT_FILE"

  for writers in $WRITER_COUNTS; do
    echo "=== durchsatz: $writers batch-writer, $CHAT_SERVICE_COUNT chat-service ==="
    scale_services "$writers"
    wait_for_consumers "$writers"
    wait_for_empty_queue
    local chat_services
    chat_services=$(running_chat_services)

    local csv_file="$RESULT_DIR/durchsatz-$writers-writer.csv"
    local log_file="$RESULT_DIR/durchsatz-$writers-writer.log"
    echo "sekunde,queue_tiefe,gespeichert" > "$csv_file"

    local stored_before
    stored_before=$(stored_messages)
    local start_time
    start_time=$(date +%s)

    run_load_generator "$LOAD_DURATION_SECONDS" "$log_file" &
    local load_pid=$!

    local max_depth=0
    local load_end_time=""
    local stats_taken=""
    while true; do
      local now elapsed depth stored
      now=$(date +%s)
      elapsed=$((now - start_time))

      # Mitten in der Last einmal festhalten, welcher Container wie viel
      # CPU braucht. Daran sieht man, wo der Engpass sitzt.
      if [ -z "$stats_taken" ] && [ "$elapsed" -ge $((LOAD_DURATION_SECONDS / 2)) ]; then
        docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" \
          > "$RESULT_DIR/docker-stats-durchsatz-$writers-writer.txt"
        stats_taken="ja"
      fi

      depth=$(queue_depth)
      stored=$(( $(stored_messages) - stored_before ))
      echo "$elapsed,$depth,$stored" >> "$csv_file"
      echo "  t=${elapsed}s  Queue=$depth  gespeichert=$stored"
      if [ "$depth" -gt "$max_depth" ]; then
        max_depth=$depth
      fi

      # Merken, wann der load-generator fertig war.
      if [ -z "$load_end_time" ] && ! kill -0 "$load_pid" 2>/dev/null; then
        load_end_time=$now
      fi
      # Fertig, wenn die Last vorbei und die Queue leer ist.
      if [ -n "$load_end_time" ] && [ "$depth" = "0" ]; then
        break
      fi
      if [ "$elapsed" -gt $((LOAD_DURATION_SECONDS + DRAIN_TIMEOUT_SECONDS)) ]; then
        echo "Abbruch: Queue nach $DRAIN_TIMEOUT_SECONDS s immer noch nicht leer" >&2
        break
      fi
      sleep "$SAMPLE_SECONDS"
    done
    wait "$load_pid" || true

    local drain_seconds stored_total accepted failed actual_rate distribution
    drain_seconds=$(( $(date +%s) - load_end_time ))
    stored_total=$(( $(stored_messages) - stored_before ))
    accepted=$(summary_value "$log_file" 'accepted ([0-9]+)')
    failed=$(summary_value "$log_file" 'failed ([0-9]+)')
    actual_rate=$(summary_value "$log_file" 'that is ([0-9]+) messages per minute')
    distribution=$(instance_distribution "$log_file")

    echo "| $writers | $chat_services | $accepted | $actual_rate | $failed | $max_depth | höchstens ${drain_seconds} s | $stored_total | $distribution |" >> "$RESULT_FILE"
  done
}

# ---------------------------------------------------------------------------
# Versuch "stau"
# ---------------------------------------------------------------------------

measure_backlog() {
  {
    echo
    echo "### Stau abbauen: $FILL_SECONDS s Last ohne batch-writer, danach N batch-writer"
    echo
    echo "| batch-writer | Stau (Nachrichten) | Abbau-Dauer | Abbau-Rate (pro Sekunde) | Abbau-Rate (pro Minute) | gespeichert |"
    echo "|---:|---:|---:|---:|---:|---:|"
  } >> "$RESULT_FILE"

  for writers in $WRITER_COUNTS; do
    echo "=== stau: Queue füllen, dann $writers batch-writer ==="
    scale_services 0
    wait_for_consumers 0

    local stored_before
    stored_before=$(stored_messages)
    run_load_generator "$FILL_SECONDS" "$RESULT_DIR/stau-$writers-writer.log"
    local backlog
    backlog=$(queue_depth)
    echo "  Stau: $backlog Nachrichten"

    # Jetzt die batch-writer starten. Die Uhr läuft erst, wenn alle an der
    # Queue hängen — das Hochfahren der JVM gehört nicht zum Schreiben.
    scale_services "$writers"
    wait_for_consumers "$writers"
    local start_millis
    start_millis=$(now_millis)

    local csv_file="$RESULT_DIR/stau-$writers-writer.csv"
    echo "millisekunden,queue_tiefe" > "$csv_file"
    local stats_taken=""
    while true; do
      local depth elapsed_millis
      depth=$(queue_depth)
      elapsed_millis=$(( $(now_millis) - start_millis ))
      echo "$elapsed_millis,$depth" >> "$csv_file"
      if [ -z "$stats_taken" ]; then
        docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" \
          > "$RESULT_DIR/docker-stats-stau-$writers-writer.txt"
        stats_taken="ja"
      fi
      if [ "$depth" = "0" ]; then
        break
      fi
      if [ "$elapsed_millis" -gt $((DRAIN_TIMEOUT_SECONDS * 1000)) ]; then
        echo "Abbruch: Stau nach $DRAIN_TIMEOUT_SECONDS s nicht abgebaut" >&2
        break
      fi
    done

    local drain_millis per_second per_minute stored_total
    drain_millis=$(( $(now_millis) - start_millis ))
    per_second=$(( backlog * 1000 / drain_millis ))
    per_minute=$(( per_second * 60 ))
    stored_total=$(( $(stored_messages) - stored_before ))
    echo "  abgebaut in $drain_millis ms: $per_second pro Sekunde"

    echo "| $writers | $backlog | $(( drain_millis / 1000 )).$(( (drain_millis % 1000) / 100 )) s | $per_second | $per_minute | $stored_total |" >> "$RESULT_FILE"
  done
}

# ---------------------------------------------------------------------------
# Ablauf
# ---------------------------------------------------------------------------

{
  echo "## Messreihe"
  echo
  echo "- Datum: $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "- Rechner: $(describe_machine)"
  echo "- chat-service-Instanzen: $CHAT_SERVICE_COUNT"
  echo "- Java-Optionen des load-generator: ${LOAD_JAVA_OPTIONS:-keine}"
} > "$RESULT_FILE"

for phase in $PHASES; do
  case "$phase" in
    durchsatz) measure_throughput ;;
    stau) measure_backlog ;;
    *) echo "Unbekannter Versuch: $phase" >&2; exit 1 ;;
  esac
done

echo
cat "$RESULT_FILE"
