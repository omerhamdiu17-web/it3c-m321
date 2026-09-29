package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der Queues, die der batch-writer braucht, an genau EINER Stelle.
 *
 * Eine eigene Kopie, keine gemeinsame Klasse mit dem chat-service: der
 * Vertrag zwischen den Diensten sind die Namen, nicht eine Java-Datei.
 */
public final class QueueNames {

    /** Schreibweg: hier legt der chat-service jede Nachricht ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Dead Letter: was der batch-writer endgültig nicht speichern kann. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
