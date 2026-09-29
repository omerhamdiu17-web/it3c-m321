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
