-- Läuft nach 01-create-databases.sh, ebenfalls nur beim ERSTEN Start.
-- Legt die Tabellen aus PLANUNG.md, Abschnitt 3.7, in der Datenbank "chat" an.
--
-- Benutzer werden NICHT hier verwaltet, dafür ist Keycloak da. Wir speichern
-- nur die sub-Kennung aus dem Token und den Anzeigenamen.

\connect chat

-- Ein Chatraum.
CREATE TABLE room (
    id          uuid         PRIMARY KEY,
    name        varchar(100) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

-- Wer in welchem Raum ist. user_id ist die sub-Kennung aus Keycloak.
CREATE TABLE room_member (
    room_id     uuid         NOT NULL REFERENCES room (id),
    user_id     varchar(255) NOT NULL,
    joined_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

-- Eine Nachricht. Die id vergibt der chat-service, nicht die Datenbank.
-- Weil sie Primärschlüssel ist, verwirft "ON CONFLICT DO NOTHING" im
-- batch-writer ein Duplikat, das RabbitMQ erneut zustellt (At-least-once).
-- sender_name ist bewusst denormalisiert: der Verlauf bleibt lesbar, auch
-- wenn das Konto in Keycloak später gelöscht wird.
CREATE TABLE message (
    id           uuid         PRIMARY KEY,
    room_id      uuid         NOT NULL REFERENCES room (id),
    sender_id    varchar(255) NOT NULL,
    sender_name  varchar(255) NOT NULL,
    content      text         NOT NULL,
    sent_at      timestamptz  NOT NULL
);

-- Die einzige Abfrage im Lesepfad: "die letzten 50 Nachrichten eines Raums".
CREATE INDEX message_room_sent_at ON message (room_id, sent_at DESC);

-- Die Tabellen gehören dem Anwendungsbenutzer, nicht dem Superuser.
ALTER TABLE room        OWNER TO chat;
ALTER TABLE room_member OWNER TO chat;
ALTER TABLE message     OWNER TO chat;
