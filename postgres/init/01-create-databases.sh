#!/bin/bash
# Läuft automatisch beim ERSTEN Start des Postgres-Containers (leeres Volume).
# Legt zwei getrennte Datenbanken mit je eigenem Benutzer an.
#
# Warum ein Shell-Skript und keine .sql-Datei: nur so kommen die Passwörter
# aus der Umgebung (.env) und stehen nicht im Repository.
#
# Warum getrennt: Die Chat-Anwendung darf die Keycloak-Tabellen nicht lesen
# können. Das ist keine Vereinbarung, sondern eine Berechtigung.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-SQL
    -- Datenbank für Keycloak (Realm, Benutzer, Sitzungen)
    CREATE USER keycloak WITH PASSWORD '$KEYCLOAK_DB_PASSWORD';
    CREATE DATABASE keycloak OWNER keycloak;

    -- Datenbank für den Chat-Verlauf (Tabellen kommen in 02-chat-schema.sql)
    CREATE USER chat WITH PASSWORD '$CHAT_DB_PASSWORD';
    CREATE DATABASE chat OWNER chat;

    -- Postgres erlaubt standardmässig JEDEM Benutzer, sich mit jeder
    -- Datenbank zu verbinden. Das nehmen wir zurück: nur der Eigentümer
    -- (und der Superuser) kommt noch hinein.
    REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
    REVOKE CONNECT ON DATABASE chat FROM PUBLIC;
SQL
