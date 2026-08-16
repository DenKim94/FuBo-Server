package de.fubo.appserver.dto.profil;

/**
 * Ein Eintrag der Namensliste an der API-Grenze (Antwort von {@code GET /api/auth/users}).
 *
 * <p><b>Enthaelt keine Skillwerte</b> (A12). Der Endpunkt ist bereits in der Stufe
 * {@code PIN_VERIFIED} erreichbar, also fuer jeden, der die zentrale PIN kennt - er ist
 * damit die am weitesten geoeffnete Stelle der API.
 *
 * @param id     Profil-Id; wird bei der Namensauswahl unveraendert zurueckgesendet
 * @param name   Anzeigename fuer die Auswahlliste
 * @param belegt {@code true}, wenn der Name gerade von einer aktiven Sitzung belegt ist;
 *               das Frontend stellt ihn dann als nicht waehlbar dar und pollt den Endpunkt
 */
public record NameOption(Long id, String name, boolean belegt) {
}
