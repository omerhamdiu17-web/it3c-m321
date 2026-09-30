#!/usr/bin/env bash
# Kommentarprüfung für Szenario S8 (CLAUDE.md): Über jeder Klasse und jeder
# Methode steht ein Kommentar. Annotationen und ihre Fortsetzungszeilen dürfen
# zwischen Kommentar und Deklaration stehen.
#
# Warum ein eigenes Skript: So lässt sich dieses Kriterium mit einem einzigen
# Befehl messen, ohne die ganze Abnahme zu starten. scripts/abnahme.sh ruft
# es in S8 ebenfalls auf.
#
# Aufruf im Wurzelverzeichnis:   bash scripts/kommentare.sh [ORDNER]
#   ORDNER ist ohne Angabe batch-writer/src.
# Ausgabe: je fehlender Kommentar eine Zeile "Datei:Zeile: Deklaration".
# Exit-Code: 0, wenn nichts fehlt; 1, wenn mindestens ein Kommentar fehlt.

folder=${1:-batch-writer/src}

# Findet Klassen und Methoden ohne Kommentar direkt darüber.
check_comments() {
  find "$folder" -name '*.java' | sort | while read -r file; do
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

missing=$(check_comments)
if [ -n "$missing" ]; then
  echo "$missing"
  exit 1
fi
exit 0
