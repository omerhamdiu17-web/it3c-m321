#!/usr/bin/env bash
#
# Messreihe für Schritt 6: Wie schnell leert sich chat.persist mit 1, 2, 3
# batch-writer-Instanzen, und verteilt sich die Last auf mehrere
# chat-service-Instanzen?
#
# Voraussetzung: das System läuft ("docker compose up -d --build").
# Aufruf aus dem Projektordner:
#
#   scripts/messreihe.sh
#
# Stellschrauben (Umgebungsvariablen, alle optional):
#   LOAD_MESSAGES_PER_MINUTE  Zielrate                    (Vorgabe 100000)
#   LOAD_DURATION_SECONDS     Dauer der Last pro Lauf     (Vorgabe 60)
#   WRITER_COUNTS             Anzahl batch-writer je Lauf (Vorgabe "1 2 3")
#   CHAT_SERVICE_COUNT        Anzahl chat-service         (Vorgabe 1)
#   RESULT_DIR                wohin die Ergebnisse gehen  (Vorgabe messreihe)
#
# Pro Lauf entstehen eine CSV-Datei mit dem Verlauf (alle 5 Sekunden
# Queue-Tiefe und Anzahl gespeicherter Nachrichten) und das Protokoll des
# load-generator. Am Ende steht eine Tabelle in RESULT_DIR/ergebnis.md.

set -euo pipefail

export LOAD_MESSAGES_PER_MINUTE="${LOAD_MESSAGES_PER_MINUTE:-100000}"
export LOAD_DURATION_SECONDS="${LOAD_DURATION_SECONDS:-60}"
WRITER_COUNTS="${WRITER_COUNTS:-1 2 3}"
CHAT_SERVICE_COUNT="${CHAT_SERVICE_COUNT:-1}"
RESULT_DIR="${RESULT_DIR:-messreihe}"

# Der Raum "Lasttest" aus postgres/init/03-rooms.sql.
LOAD_ROOM_ID="00000000-0000-0000-0000-000000000003"
SAMPLE_SECONDS=5
# So lange warten wir nach dem Ende der Last höchstens, bis die Queue leer ist.
DRAIN_TIMEOUT_SECONDS=600

mkdir -p "$RESULT_DIR"

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

# Wartet, bis genau N batch-writer an der Queue hängen.
wait_for_consumers() {
  local expected="$1"
  for attempt in $(seq 1 60); do
    if [ "$(queue_consumers)" = "$expected" ]; then
      return 0
    fi
    sleep 2
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

echo "## Messreihe" > "$RESULT_DIR/ergebnis.md"
{
  echo
  echo "- Datum: $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "- Rechner: $(nproc) CPU-Kerne, $(free -g | awk '/Mem:/ { print $2 }') GB Arbeitsspeicher, $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)"
  echo "- Last: $LOAD_MESSAGES_PER_MINUTE Nachrichten/Minute für $LOAD_DURATION_SECONDS s"
  echo "- chat-service-Instanzen: $CHAT_SERVICE_COUNT"
  echo
  echo "| batch-writer | angenommen | Ist-Rate (pro Minute) | Fehler | grösste Queue-Tiefe | Queue leer nach Lastende | gespeichert | Schreibrate (pro Minute) |"
  echo "|---:|---:|---:|---:|---:|---:|---:|---:|"
} >> "$RESULT_DIR/ergebnis.md"

docker compose up -d --scale chat-service="$CHAT_SERVICE_COUNT" chat-service

for writers in $WRITER_COUNTS; do
  echo "=== Lauf mit $writers batch-writer ==="
  docker compose up -d --scale batch-writer="$writers" batch-writer
  wait_for_consumers "$writers"
  wait_for_empty_queue

  csv_file="$RESULT_DIR/verlauf-$writers-writer.csv"
  log_file="$RESULT_DIR/load-generator-$writers-writer.log"
  echo "sekunde,queue_tiefe,gespeichert" > "$csv_file"

  stored_before=$(stored_messages)
  start_time=$(date +%s)

  # Die Last läuft im Hintergrund, wir messen währenddessen.
  docker compose --profile load run --rm load-generator > "$log_file" 2>&1 &
  load_pid=$!

  max_depth=0
  load_end_time=""
  stats_taken=""
  while true; do
    now=$(date +%s)
    elapsed=$((now - start_time))

    # Mitten in der Last einmal festhalten, welcher Container wie viel
    # CPU braucht. Daran sieht man, wo der Engpass sitzt.
    if [ -z "$stats_taken" ] && [ "$elapsed" -ge $((LOAD_DURATION_SECONDS / 2)) ]; then
      docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" \
        > "$RESULT_DIR/docker-stats-$writers-writer.txt"
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

  end_time=$(date +%s)
  total_seconds=$((end_time - start_time))
  drain_seconds=$((end_time - load_end_time))
  stored_total=$(( $(stored_messages) - stored_before ))
  write_rate=$((stored_total * 60 / total_seconds))

  # Die Zusammenfassung des load-generator, z.B.
  # "Load finished after 60.4 s: accepted 99960, failed 0, that is 99300 messages per minute"
  summary=$(grep "Load finished" "$log_file" | tail -1)
  accepted=$(echo "$summary" | sed -E 's/.*accepted ([0-9]+).*/\1/')
  failed=$(echo "$summary" | sed -E 's/.*failed ([0-9]+).*/\1/')
  actual_rate=$(echo "$summary" | sed -E 's/.*that is ([0-9]+) messages per minute.*/\1/')

  echo "| $writers | $accepted | $actual_rate | $failed | $max_depth | ${drain_seconds} s | $stored_total | $write_rate |" >> "$RESULT_DIR/ergebnis.md"

  echo "  Verteilung auf die chat-service-Instanzen:"
  grep "chat-service instance" "$log_file" | sed -E 's/.*(chat-service instance .*)/    \1/' || true
done

{
  echo
  echo "Verteilung der Anfragen auf die chat-service-Instanzen (letzter Lauf):"
  echo
  echo '```'
  grep "chat-service instance" "$log_file" | sed -E 's/.*(chat-service instance .*)/\1/' || true
  echo '```'
} >> "$RESULT_DIR/ergebnis.md"

echo
cat "$RESULT_DIR/ergebnis.md"
