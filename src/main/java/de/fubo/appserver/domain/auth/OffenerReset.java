package de.fubo.appserver.domain.auth;

/**
 * Ergebnis der {@code RETURNING}-Klausel beim Zaehlen eines Reset-Versuchs (S2b).
 *
 * <p>Ein schlankes Wertobjekt wie {@link AktiveSitzung}, keine Entity: Die Tabelle
 * {@code profil.passwort_reset} wird ausschliesslich angehaengt und bedingt aktualisiert,
 * nie geladen, geaendert und zurueckgeschrieben. Wie alle Typen in {@code domain}
 * ueberschreitet auch dieser die API-Grenze nie - der {@code pinHash} hat in keiner
 * Antwort etwas zu suchen.
 *
 * @param id      Schluessel des Vorgangs, fuer das anschliessende Entwerten und fuer das
 *                Audit-Log
 * @param pinHash BCrypt-Hash der Bestaetigungs-PIN. Die PIN selbst wird nie gespeichert -
 *                ein Datenbankabzug verraet sie damit nicht.
 */
public record OffenerReset(Long id, String pinHash) {
}
