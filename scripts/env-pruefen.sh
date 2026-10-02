#!/usr/bin/env bash
# Prüft, ob die lokale .env jeden Schlüssel aus .env.example enthält.
#
# Warum: docker-compose.yml verlangt manche Werte mit ${NAME:?…}. Fehlt einer
# in einer älteren .env, scheitert JEDER docker-compose-Befehl, auch "down"
# (spec-web-gateway.md 4.1). Dieses Skript sagt vorher, was fehlt. Es gibt
# nur Namen aus, nie Werte: Die .env enthält Geheimnisse.
#
# Aufruf im Wurzelverzeichnis:   bash scripts/env-pruefen.sh [ENV-DATEI] [BEISPIEL-DATEI]
#   Ohne Angaben: .env und .env.example.
# Exit-Code: 0, wenn nichts fehlt; 1, wenn mindestens ein Schlüssel fehlt.

env_file=${1:-.env}
example_file=${2:-.env.example}

missing=""
while IFS= read -r line; do
  # Ein Windows-Zeilenende (CR) gehört nicht zum Namen.
  line=${line%$'\r'}
  case "$line" in
    '' | '#'*) continue ;;
  esac
  key=${line%%=*}
  if ! grep -q "^$key=" "$env_file"; then
    missing="$missing $key"
  fi
done < "$example_file"

if [ -n "$missing" ]; then
  echo "In $env_file fehlen Schlüssel aus $example_file:$missing" >&2
  echo "Die Zeilen aus $example_file übernehmen und einen Wert setzen." >&2
  exit 1
fi
exit 0
