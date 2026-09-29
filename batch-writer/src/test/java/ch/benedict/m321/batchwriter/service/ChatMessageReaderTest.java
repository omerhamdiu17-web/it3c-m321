package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Lesen des JSON, ohne Spring und ohne Broker.
 *
 * Die Beispielnachricht ist genau die, die am 29.09.2026 aus chat.persist
 * mitgeschnitten wurde (Spezifikation 2.4). Der Reader bekommt nur Bytes,
 * keine Header: das ist der Fall aus Szenario S5.
 */
class ChatMessageReaderTest {

    /** Der Body aus dem Mitschnitt, Zeichen für Zeichen. */
    private static final String CAPTURED_BODY = """
            {"id":"9813b68e-d290-44b1-b217-e43fa7a544db","roomId":"3f2b1c4e-0000-0000-0000-000000000001",\
            "senderId":"anna","senderName":"Anna Muster","content":"Hallo Vertrag",\
            "sentAt":"2026-09-29T13:38:12.974043374Z"}""";

    /** Der Reader mit einem ObjectMapper, wie ihn auch Spring Boot baut. */
    private final ChatMessageReader reader = createReader();

    /** Die mitgeschnittene Nachricht wird vollständig gelesen, allein aus dem Body. */
    @Test
    void readsCapturedMessageFromBodyAlone() {
        byte[] body = bytes(CAPTURED_BODY);

        ChatMessage message = reader.read(body);

        assertNotNull(message);
        UUID expectedId = UUID.fromString("9813b68e-d290-44b1-b217-e43fa7a544db");
        UUID expectedRoomId = UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001");
        OffsetDateTime expectedSentAt = OffsetDateTime.parse("2026-09-29T13:38:12.974043374Z");
        assertEquals(expectedId, message.id());
        assertEquals(expectedRoomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo Vertrag", message.content());
        OffsetDateTime sentAt = message.sentAt();
        assertTrue(expectedSentAt.isEqual(sentAt), "gelesen: " + sentAt);
    }

    /** Ein Zeitpunkt mit anderer Zeitzone ist derselbe Zeitpunkt. */
    @Test
    void readsTimestampWithOffset() {
        String json = CAPTURED_BODY.replace("2026-09-29T13:38:12.974043374Z", "2026-09-29T15:38:12.974043374+02:00");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNotNull(message);
        OffsetDateTime expectedSentAt = OffsetDateTime.parse("2026-09-29T13:38:12.974043374Z");
        OffsetDateTime sentAt = message.sentAt();
        assertTrue(expectedSentAt.isEqual(sentAt), "gelesen: " + sentAt);
    }

    /** Ein Feld, das der chat-service später vielleicht ergänzt, stört nicht. */
    @Test
    void ignoresUnknownField() {
        String json = CAPTURED_BODY.replace("{\"id\"", "{\"priority\":\"hoch\",\"id\"");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNotNull(message);
        assertEquals("Hallo Vertrag", message.content());
    }

    /** Fehlt ein Pflichtfeld, ist die Nachricht nicht speicherbar. */
    @Test
    void rejectsMissingField() {
        String json = CAPTURED_BODY.replace("\"content\":\"Hallo Vertrag\",", "");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Ein Feld mit dem Wert null zählt wie ein fehlendes Feld. */
    @Test
    void rejectsFieldWithNullValue() {
        String json = CAPTURED_BODY.replace("\"Hallo Vertrag\"", "null");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Eine id, die keine UUID ist, kann nie Primärschlüssel werden. */
    @Test
    void rejectsInvalidUuid() {
        String json = CAPTURED_BODY.replace("9813b68e-d290-44b1-b217-e43fa7a544db", "keine-uuid");
        byte[] body = bytes(json);

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Text, der gar kein JSON ist. */
    @Test
    void rejectsBodyThatIsNoJson() {
        byte[] body = bytes("das ist kein JSON");

        ChatMessage message = reader.read(body);

        assertNull(message);
    }

    /** Baut den Reader wie Spring Boot seinen ObjectMapper: mit Zeit-Typen, unbekannte Felder erlaubt. */
    private static ChatMessageReader createReader() {
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        return new ChatMessageReader(objectMapper);
    }

    /** Macht aus Text die Bytes, die im Body einer Nachricht stehen. */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
