package de.fubo.appserver.domain.auth;

/**
 * Ergebnis einer erfolgreichen Sitzungspruefung: genau die Felder, welche die
 * Filterchain benoetigt.
 *
 * <p>Bewusst <b>kein</b> JPA-Entity. Die Zeile stammt aus der {@code RETURNING}-Klausel
 * eines nativen {@code UPDATE} und ist nicht vom Persistence-Context verwaltet. Eine
 * teilweise befuellte {@link Session}-Entity zurueckzugeben waere irrefuehrend: Sie saehe
 * aus wie ein vollstaendiges Objekt, haette aber leere Felder, und ein {@code save()}
 * darauf wuerde Daten ueberschreiben.
 *
 * @param id         technischer Schluessel der Sitzung
 * @param spielerId  Profil-Id; {@code null} bei Gastsitzungen und in {@link Stage#PIN_VERIFIED}
 * @param gastName   temporaerer Name eines Gastes; sonst {@code null}
 * @param rolle      {@code null}, solange die Sitzung in {@link Stage#PIN_VERIFIED} ist
 * @param stage      erreichte Login-Stufe
 */
public record AktiveSitzung(Long id,
                            Long spielerId,
                            String gastName,
                            Rolle rolle,
                            Stage stage) {
}
