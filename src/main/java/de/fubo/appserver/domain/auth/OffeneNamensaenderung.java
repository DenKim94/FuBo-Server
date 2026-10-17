package de.fubo.appserver.domain.auth;

/**
 * Ergebnis der {@code RETURNING}-Klausel beim Zaehlen eines Bestaetigungsversuchs zur
 * Aenderung des Admin-Anmeldenamens (Ergaenzung vom 04.10.2026).
 *
 * <p>Das Gegenstueck zu {@link OffenerReset}, bewusst als eigener Typ: Die beiden Vorgaenge
 * liegen in getrennten Tabellen, und ein gemeinsamer Typ verleitete dazu, die Id des einen beim
 * anderen einzuloesen. Wie alle Typen in {@code domain} ueberschreitet auch dieser die
 * API-Grenze nie - der {@code pinHash} hat in keiner Antwort etwas zu suchen.
 *
 * <p><b>Der gewuenschte Name ist nicht enthalten.</b> Er wird erst beim Einloesen gelesen, in
 * derselben Anweisung, die den Vorgang abschliesst - so kann zwischen Pruefen und Uebernehmen
 * kein anderer Name dazwischenkommen.
 *
 * @param id      Schluessel des Vorgangs, fuer das anschliessende Einloesen
 * @param pinHash BCrypt-Hash der Bestaetigungs-PIN
 */
public record OffeneNamensaenderung(Long id, String pinHash) {
}
