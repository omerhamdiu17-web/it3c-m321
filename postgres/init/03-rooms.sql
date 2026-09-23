-- Läuft nach 02-chat-schema.sql, ebenfalls nur beim ERSTEN Start.
-- Legt die drei festen Räume an (Schritt 4 der Umsetzungsreihenfolge).
--
-- Warum fest und nicht über die Oberfläche: message.room_id ist ein
-- Fremdschlüssel auf room. Ohne Raum scheitert jeder INSERT des
-- batch-writer. Eine Raumverwaltung ist für M321 nicht der Lernstoff.
--
-- Die IDs sind absichtlich lesbar. Die Lobby-ID benutzt die Web-Oberfläche
-- schon seit Schritt 3, der load-generator schreibt in den Raum "Lasttest".

\connect chat

INSERT INTO room (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Lobby'),
    ('00000000-0000-0000-0000-000000000002', 'M321'),
    ('00000000-0000-0000-0000-000000000003', 'Lasttest');
