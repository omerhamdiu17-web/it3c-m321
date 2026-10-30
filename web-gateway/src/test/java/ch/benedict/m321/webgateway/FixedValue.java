package ch.benedict.m321.webgateway;

import java.util.function.Supplier;

/**
 * Ein fester Wert für @DynamicPropertySource.
 *
 * Spring verlangt dort einen Supplier, also etwas, das den Wert erst auf
 * Nachfrage liefert. Unsere Werte stehen schon fest, wenn Spring startet (Port
 * eines Containers oder Mini-Servers). Diese kleine benannte Klasse steht da,
 * wo man sonst ein Lambda schriebe; Lambdas gibt es in diesem Projekt nur in
 * der Konfiguration von Spring Security.
 *
 * @param value der Wert, den Spring für die Eigenschaft einsetzt
 */
public record FixedValue(Object value) implements Supplier<Object> {

    /** Gibt den Wert zurück, der beim Erzeugen mitgegeben wurde. */
    @Override
    public Object get() {
        return value;
    }
}
