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
set -a
. ./.env
set +a

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
  local consumers
  consumers=$(queue_value chat.persist consumers)
  local accepted
  accepted=$(send_messages 1000 S6)
  wait_until 60 s6_done
  local rows
  rows=$(count_marked S6)
  local distinct
  distinct=$(sql "SELECT count(DISTINCT id) FROM message WHERE content LIKE 'S6 %'")
  local instances
  instances=$(docker compose logs --no-color batch-writer 2>/dev/null | grep 'Stored batch' | awk '{ print $1 }' | sort -u | count_lines)
  local ok=1
  if [ "$consumers" = "2" ] && [ "$rows" = "1000" ] && [ "$distinct" = "1000" ]; then
    ok=0
  fi
  report S6 "202: $accepted, Verbraucher: $consumers, Zeilen: $rows, verschiedene ids: $distinct, Instanzen mit Stapeln: $instances" "2 Verbraucher, 1000 Zeilen, keine doppelt" "$ok"
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
  local ok=1
  if [ "$stream_hits" = "0" ] && [ "$missing" = "0" ] && [ "$env_tracked" = "0" ] && [ "$env_history" = "0" ]; then
    ok=0
  fi
  report S8 "Treffer 'stream': $stream_hits, ohne Kommentar: $missing, .env im Repo: $env_tracked, .env im Verlauf: $env_history" "alles 0" "$ok"
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
