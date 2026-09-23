package ch.benedict.m321.desktopclient.api;

/**
 * Was das Gateway unter /api/me liefert — beim Desktop-Client gelesen aus
 * dem Bearer-Token statt aus der Sitzung.
 *
 * @param username    der Anmeldename
 * @param displayName Vor- und Nachname
 * @param admin       true bei der Rolle admin
 */
public record CurrentUser(String username, String displayName, boolean admin) {
}
