#!/usr/bin/env bash
# Abnahme der Live-Zustellung: stellt die Szenarien S2 bis S8 aus «Bewertung 2»
# nach, in derselben Reihenfolge und auf demselben Stack, ohne Aufräumen
# dazwischen. S1 ist "mvn clean test" und läuft getrennt davon.
#
# Gemessen wird wie in der Abnahme von innen, im Netz chat-net:
# - Clients sind "curl -N" in eigenen Containern, je Instanz über ihre IP.
#   Was ein Client bekommen hat, steht in "docker logs <Client>".
# - Gesendet wird mit curl in einem Container, viele Nachrichten über EINE
#   Verbindung (Konfigurationsdatei mit "next"), damit 5000 nicht ewig dauern.
# - Queues zählt rabbitmqctl im RabbitMQ-Container, die Tabelle zählt psql.
#
# Aufruf im Wurzelverzeichnis:   bash scripts/abnahme-live.sh
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

# Die .env Zeile für Zeile übernehmen, ohne Windows-Zeilenende (wie abnahme.sh).
while IFS= read -r line; do
  line=${line%$'\r'}
  case "$line" in
    '' | '#'*) continue ;;
  esac
  export "$line"
done < .env

ROOM_A="3f2b1c4e-0000-0000-0000-00000000000a"
ROOM_B="3f2b1c4e-0000-0000-0000-00000000000b"
CURL_IMAGE="curlimages/curl"
WORK_DIR=$(mktemp -d)
SUMMARY=""
FAILURES=0

# ------------------------------------------------------------------ Hilfen

# Führt eine SQL-Abfrage in der Chat-Datenbank aus und gibt nur den Wert aus.
sql() {
  docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1" 2>/dev/null
}

# Zählt Zeilen einer Eingabe, ohne die Leerzeichen, die macOS davor setzt.
count_lines() {
  wc -l | tr -d ' '
}

# Die IP-Adressen aller laufenden chat-service-Instanzen im Netz chat-net, sortiert.
instance_ips() {
  local container
  for container in $(docker compose ps -q chat-service); do
    docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container"
  done | sort
}

# Die Container-ID der Instanz mit der IP $1.
container_of_ip() {
  local container
  for container in $(docker compose ps -q chat-service); do
    local address
    address=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container")
    if [ "$address" = "$1" ]; then
      echo "$container"
    fi
  done
}

# Antwortet die Instanz mit IP $1? Ohne roomId kommt 400, das heisst: sie läuft.
instance_answers() {
  local status
  status=$(docker run --rm --network chat-net "$CURL_IMAGE" -s -o /dev/null -w '%{http_code}' -m 2 "http://$1:8080/stream")
  [ "$status" = "400" ]
}

# Antworten alle Instanzen, und sind es genau $1 Stück?
instances_ready() {
  local ips
  ips=$(instance_ips)
  local count
  count=$(echo "$ips" | grep -c .)
  if [ "$count" != "$1" ]; then
    return 1
  fi
  local ip
  for ip in $ips; do
    instance_answers "$ip" || return 1
  done
  return 0
}

# Startet einen Client namens $1 an der Instanz $2 für den Raum $3, optional
# mit Last-Event-ID $4, und wartet, bis die Kommentarzeile ":verbunden" da ist.
start_client() {
  docker rm -f "$1" >/dev/null 2>&1
  if [ -n "${4:-}" ]; then
    docker run -d --name "$1" --network chat-net "$CURL_IMAGE" -sN \
      -H "Last-Event-ID: $4" "http://$2:8080/stream?roomId=$3" >/dev/null
  else
    docker run -d --name "$1" --network chat-net "$CURL_IMAGE" -sN \
      "http://$2:8080/stream?roomId=$3" >/dev/null
  fi
  wait_until 10 client_connected "$1"
}

# Hat der Client $1 die Kommentarzeile ":verbunden" bekommen?
client_connected() {
  docker logs "$1" 2>/dev/null | grep -q '^:verbunden'
}

# Alle IDs, die der Client $1 bekommen hat, eine pro Zeile.
client_ids() {
  docker logs "$1" 2>/dev/null | grep '^id:' | cut -c4-
}

# Wie viele Ereignisse (id-Zeilen) der Client $1 bekommen hat.
client_count() {
  client_ids "$1" | count_lines
}

# Wie viele verschiedene IDs der Client $1 bekommen hat.
client_distinct_count() {
  client_ids "$1" | sort -u | count_lines
}

# Die Texte (content) aller Nachrichten des Clients $1 vom Absender $2, in Empfangsreihenfolge.
client_contents_from() {
  docker logs "$1" 2>/dev/null | grep '^data:' | grep "\"senderId\":\"$2\"" \
    | sed 's/.*"content":"\([^"]*\)".*/\1/'
}

# Hat der Client $1 mindestens $2 Ereignisse?
client_has_at_least() {
  [ "$(client_count "$1")" -ge "$2" ]
}

# Kommen die Texte "$3 1" bis "$3 $4" vom Absender $2 beim Client $1 genau so,
# in dieser Reihenfolge, ohne Lücke und ohne Doppelte? 0 = ja.
contents_in_order() {
  local expected
  expected=$(i=1; while [ "$i" -le "$4" ]; do echo "$3 $i"; i=$((i + 1)); done)
  local received
  received=$(client_contents_from "$1" "$2" | grep "^$3 ")
  [ "$expected" = "$received" ]
}

# Schickt $4 Nachrichten an POST /messages der Instanz $1, Raum $2, Absender $3,
# mit den Texten "$5 1", "$5 2", ... Alle über eine Verbindung. Gibt aus, wie
# viele mit 202 angenommen wurden.
send_via() {
  local config="$WORK_DIR/send-$3-$5.cfg"
  : > "$config"
  local i=1
  while [ "$i" -le "$4" ]; do
    # "next" trennt zwei Anfragen. Vor der ersten steht keines, sonst
    # meldet curl eine Anfrage ohne URL.
    if [ "$i" -gt 1 ]; then
      echo "next" >> "$config"
    fi
    {
      echo "url = \"http://$1:8080/messages\""
      echo "header = \"Content-Type: application/json\""
      echo "data = \"{\\\"roomId\\\":\\\"$2\\\",\\\"senderId\\\":\\\"$3\\\",\\\"senderName\\\":\\\"$3\\\",\\\"content\\\":\\\"$5 $i\\\"}\""
      echo "output = \"/dev/null\""
      echo "write-out = \"%{http_code}\\n\""
    } >> "$config"
    i=$((i + 1))
  done
  docker run --rm --network chat-net -v "$config:/send.cfg:ro" "$CURL_IMAGE" -s --config /send.cfg \
    | grep -c '^202$'
}

# Wie viele Queues hängen am Fanout chat.delivery?
delivery_queue_count() {
  docker compose exec -T rabbitmq rabbitmqctl list_bindings -q source_name destination_name 2>/dev/null \
    | awk '$1 == "chat.delivery"' | count_lines
}

# Die Queues am Zustellweg mit ihrer Zahl an Consumern, z. B. "chat.delivery.1a2b… 1".
delivery_queues_with_consumers() {
  docker compose exec -T rabbitmq rabbitmqctl list_queues -q name consumers 2>/dev/null \
    | grep '^chat\.delivery\.'
}

# Gibt es die Queue $1?
queue_exists() {
  docker compose exec -T rabbitmq rabbitmqctl list_queues -q name 2>/dev/null | grep -qx "$1"
}

# Wartet höchstens $1 Sekunden, bis der Befehl dahinter gelingt. Prüft jede Sekunde.
wait_until() {
  local deadline=$((SECONDS + $1))
  shift
  while [ "$SECONDS" -lt "$deadline" ]; do
    if "$@"; then
      return 0
    fi
    sleep 1
  done
  "$@"
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

# Entfernt alle Client-Container dieses Skripts.
remove_clients() {
  docker ps -aq --filter "name=^live-" | xargs docker rm -f >/dev/null 2>&1
}

# --------------------------------------------- Bedingungen für wait_until

services_are_running() {
  local running
  running=$(docker compose ps --status running --services | sort | tr '\n' ' ')
  [ "$running" = "batch-writer chat-service postgres rabbitmq " ]
}

delivery_queue_count_is() {
  [ "$(delivery_queue_count)" = "$1" ]
}

stored_count_is() {
  [ "$(sql "SELECT count(*) FROM message WHERE content LIKE '$1 %'")" = "$2" ]
}

# ---------------------------------------------------------------- Szenarien

scenario_s2() {
  echo "== S2: frischer Stack aus .env.example"
  docker compose down -v --remove-orphans >/dev/null 2>&1
  docker compose up -d --build >/dev/null 2>&1
  wait_until 120 services_are_running
  wait_until 60 instances_ready 1
  local running
  running=$(docker compose ps --status running --services | sort | tr '\n' ' ')
  local published
  published=$(docker compose ps --format '{{.Ports}}' | grep -c -- '->')
  local ok=1
  if [ "$running" = "batch-writer chat-service postgres rabbitmq " ] && [ "$published" = "0" ]; then
    ok=0
  fi
  report S2 "laufend: $running| Dienste mit Port nach aussen: $published" "alle 4 laufen, 0 Ports" "$ok"
}

scenario_s3() {
  echo "== S3: zwei Instanzen, Raum A und Raum B"
  docker compose up -d --scale chat-service=2 chat-service >/dev/null 2>&1
  wait_until 60 instances_ready 2
  local ips
  ips=$(instance_ips)
  local first_ip
  first_ip=$(echo "$ips" | sed -n 1p)
  local second_ip
  second_ip=$(echo "$ips" | sed -n 2p)
  start_client live-s3-a1 "$first_ip" "$ROOM_A"
  start_client live-s3-a2 "$second_ip" "$ROOM_A"
  start_client live-s3-b "$first_ip" "$ROOM_B"

  # Beide Absender gleichzeitig, je über eine andere Instanz.
  send_via "$first_ip" "$ROOM_A" s3-sender-1 200 "S3-1" > "$WORK_DIR/s3-1.count" &
  send_via "$second_ip" "$ROOM_A" s3-sender-2 200 "S3-2" > "$WORK_DIR/s3-2.count" &
  wait
  wait_until 30 client_has_at_least live-s3-a1 400
  wait_until 30 client_has_at_least live-s3-a2 400
  sleep 2

  local a1 a1_distinct a2 a2_distinct b
  a1=$(client_count live-s3-a1)
  a1_distinct=$(client_distinct_count live-s3-a1)
  a2=$(client_count live-s3-a2)
  a2_distinct=$(client_distinct_count live-s3-a2)
  b=$(client_count live-s3-b)
  local order=0
  contents_in_order live-s3-a1 s3-sender-1 S3-1 200 || order=1
  contents_in_order live-s3-a1 s3-sender-2 S3-2 200 || order=1
  contents_in_order live-s3-a2 s3-sender-1 S3-1 200 || order=1
  contents_in_order live-s3-a2 s3-sender-2 S3-2 200 || order=1
  local order_text="ja"
  if [ "$order" -ne 0 ]; then
    order_text="nein"
  fi
  local ok=1
  if [ "$a1" = "400" ] && [ "$a1_distinct" = "400" ] && [ "$a2" = "400" ] && [ "$a2_distinct" = "400" ] \
    && [ "$b" = "0" ] && [ "$order" -eq 0 ]; then
    ok=0
  fi
  report S3 "angenommen $(cat "$WORK_DIR/s3-1.count")+$(cat "$WORK_DIR/s3-2.count"); Client A1 $a1 ($a1_distinct verschieden), A2 $a2 ($a2_distinct verschieden), B $b; Reihenfolge je Absender: $order_text" \
    "A1 und A2 je 400 verschieden, B 0, Reihenfolge ja" "$ok"
}

scenario_s4() {
  echo "== S4: von zwei Instanzen zurück auf eine"
  local before
  before=$(delivery_queue_count)
  local consumers
  consumers=$(delivery_queues_with_consumers | awk '{ print $2 }' | tr '\n' ' ')
  local start=$SECONDS
  docker compose up -d --scale chat-service=1 chat-service >/dev/null 2>&1
  wait_until 30 delivery_queue_count_is 1
  local seconds=$((SECONDS - start))
  local after
  after=$(delivery_queue_count)
  local persist_ok=1
  if queue_exists chat.persist && queue_exists chat.dlq; then
    persist_ok=0
  fi
  local persist_text="ja"
  if [ "$persist_ok" -ne 0 ]; then
    persist_text="nein"
  fi
  local ok=1
  if [ "$before" = "2" ] && [ "$consumers" = "1 1 " ] && [ "$after" = "1" ] && [ "$seconds" -le 30 ] && [ "$persist_ok" -eq 0 ]; then
    ok=0
  fi
  report S4 "vorher $before Queues am Fanout (Consumer: $consumers), nachher $after nach $seconds s; chat.persist und chat.dlq da: $persist_text" \
    "vorher 2 mit je 1, nachher 1 nach <= 30 s, beide da" "$ok"
}

scenario_s5() {
  echo "== S5: batch-writer und postgres gestoppt"
  remove_clients
  local ip
  ip=$(instance_ips | sed -n 1p)
  start_client live-s5 "$ip" "$ROOM_A"
  docker compose stop batch-writer postgres >/dev/null 2>&1
  local accepted
  accepted=$(send_via "$ip" "$ROOM_A" s5-sender 100 "S5")
  local start=$SECONDS
  wait_until 5 client_has_at_least live-s5 100
  local seconds=$((SECONDS - start))
  local received
  received=$(client_count live-s5)
  docker compose start postgres batch-writer >/dev/null 2>&1
  wait_until 60 stored_count_is S5 100
  local stored
  stored=$(sql "SELECT count(*) FROM message WHERE content LIKE 'S5 %'")
  local ok=1
  if [ "$accepted" = "100" ] && [ "$received" = "100" ] && [ "$seconds" -le 5 ] && [ "$stored" = "100" ]; then
    ok=0
  fi
  report S5 "angenommen $accepted, beim Client $received nach $seconds s, nach dem Neustart in der Tabelle $stored" \
    "100 beim Client nach <= 5 s, 100 in der Tabelle" "$ok"
}

scenario_s6() {
  echo "== S6: eine von zwei Instanzen angehalten, 5000 Nachrichten"
  remove_clients
  docker compose up -d --scale chat-service=2 chat-service >/dev/null 2>&1
  wait_until 60 instances_ready 2
  wait_until 30 delivery_queue_count_is 2
  local ips
  ips=$(instance_ips)
  local running_ip
  running_ip=$(echo "$ips" | sed -n 1p)
  local paused_ip
  paused_ip=$(echo "$ips" | sed -n 2p)
  local paused_container
  paused_container=$(container_of_ip "$paused_ip")
  start_client live-s6-running "$running_ip" "$ROOM_A"
  start_client live-s6-paused "$paused_ip" "$ROOM_A"

  # Füllstand der Queues am Zustellweg laufend mitschreiben, im RabbitMQ-Container.
  docker compose exec -T rabbitmq sh -c 'rm -f /tmp/s6-stop /tmp/s6-queues.txt'
  docker compose exec -T -d rabbitmq sh -c \
    'while [ ! -f /tmp/s6-stop ]; do rabbitmqctl list_queues -q name messages | grep "^chat\.delivery\." >> /tmp/s6-queues.txt; done'

  docker pause "$paused_container" >/dev/null
  local accepted
  accepted=$(send_via "$running_ip" "$ROOM_A" s6-sender 5000 "S6")
  wait_until 30 client_has_at_least live-s6-running 5000
  sleep 2
  docker unpause "$paused_container" >/dev/null

  docker compose exec -T rabbitmq touch /tmp/s6-stop
  sleep 1
  local samples
  samples=$(docker compose exec -T rabbitmq sh -c 'wc -l < /tmp/s6-queues.txt' | tr -d ' \r')
  local maximum
  maximum=$(docker compose exec -T rabbitmq sh -c 'cat /tmp/s6-queues.txt' | awk 'BEGIN { max = 0 } $2 > max { max = $2 } END { print max }')
  local running_received
  running_received=$(client_contents_from live-s6-running s6-sender | grep -c '^S6 ')

  # Nach dem Fortsetzen: Was lag noch in der Queue der angehaltenen Instanz und
  # kam jetzt an? Und kommt eine NEUE Nachricht bei ihrem Client an?
  local delivered_after_resume
  delivered_after_resume=$(client_count live-s6-paused)
  wait_until 30 instances_ready 2
  wait_until 30 delivery_queue_count_is 2
  send_via "$running_ip" "$ROOM_A" s6-after 1 "S6-nachher" >/dev/null
  local resumed=1
  if wait_until 30 paused_client_got_after_message; then
    resumed=0
  fi
  local resumed_text="ja"
  if [ "$resumed" -ne 0 ]; then
    resumed_text="nein"
  fi
  local ok=1
  if [ "$accepted" = "5000" ] && [ "$maximum" -le 1000 ] && [ "$running_received" = "5000" ] && [ "$resumed" -eq 0 ]; then
    ok=0
  fi
  report S6 "angenommen $accepted; grösster Füllstand $maximum ($samples Messungen); andere Instanz: $running_received; beim Client der angehaltenen Instanz nach dem Fortsetzen $delivered_after_resume (Rest aus ihrer Queue); danach neue Nachricht zugestellt: $resumed_text" \
    "Queue nie über 1000, 5000 an der anderen Instanz, danach wieder zugestellt" "$ok"
}

# Hat der Client der angehaltenen Instanz die Nachricht "S6-nachher 1" bekommen?
paused_client_got_after_message() {
  client_contents_from live-s6-paused s6-after | grep -q '^S6-nachher 1$'
}

scenario_s7() {
  echo "== S7: Verbindungsabbruch und Neuverbindung mit Last-Event-ID"
  remove_clients
  local ip
  ip=$(instance_ips | sed -n 1p)
  start_client live-s7 "$ip" "$ROOM_B"
  send_via "$ip" "$ROOM_B" s7-first 1 "S7-vorher" >/dev/null
  wait_until 10 client_has_at_least live-s7 1
  local last_id
  last_id=$(client_ids live-s7 | tail -1)
  docker rm -f live-s7 >/dev/null

  local accepted
  accepted=$(send_via "$ip" "$ROOM_B" s7-sender 50 "S7")
  sleep 10
  start_client live-s7 "$ip" "$ROOM_B" "$last_id"
  wait_until 10 client_has_at_least live-s7 50
  send_via "$ip" "$ROOM_B" s7-live 1 "S7-live" >/dev/null
  wait_until 10 client_has_at_least live-s7 51
  sleep 1

  local received distinct
  received=$(client_count live-s7)
  distinct=$(client_distinct_count live-s7)
  local order=1
  if contents_in_order live-s7 s7-sender S7 50; then
    order=0
  fi
  local last_content
  last_content=$(docker logs live-s7 2>/dev/null | grep '^data:' | tail -1 | sed 's/.*"content":"\([^"]*\)".*/\1/')
  local order_text="ja"
  if [ "$order" -ne 0 ]; then
    order_text="nein"
  fi
  local ok=1
  if [ "$accepted" = "50" ] && [ "$received" = "51" ] && [ "$distinct" = "51" ] && [ "$order" -eq 0 ] && [ "$last_content" = "S7-live 1" ]; then
    ok=0
  fi
  report S7 "angenommen $accepted; nach Neuverbindung $received Ereignisse ($distinct verschieden), die 50 in Reihenfolge: $order_text, zuletzt live: '$last_content'" \
    "50 nachgeholt in Reihenfolge, keine doppelt, danach 1 live" "$ok"
}

scenario_s8() {
  echo "== S8: Quelltext von chat-service/"
  local stream_hits
  stream_hits=$(grep -rnE --exclude-dir=target 'java\.util\.stream|\.stream\(|parallelStream|Stream\.of|Collectors\.' chat-service/ | count_lines)
  local missing
  missing=$(bash scripts/kommentare.sh chat-service/src | count_lines)
  bash scripts/kommentare.sh chat-service/src || true
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
  report S8 "Java-Streams: $stream_hits, ohne Kommentar: $missing, .env im Repo: $env_tracked, .env im Verlauf: $env_history (geprüft: $commits Commits)" "alles 0, ganzer Verlauf" "$ok"
}

# ------------------------------------------------------------------- Ablauf

scenario_s2
scenario_s3
scenario_s4
scenario_s5
scenario_s6
scenario_s7
scenario_s8

remove_clients
rm -rf "$WORK_DIR"

echo
echo "================================ Ergebnis ================================"
printf '%s' "$SUMMARY"
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES Szenario(s) nicht bestanden."
  exit 1
fi
echo "Alle Szenarien bestanden."
