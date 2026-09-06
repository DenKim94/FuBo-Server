package de.fubo.appserver.domain.spieltag;

import de.fubo.appserver.domain.auth.GastStufe;

/**
 * Ein vom Admin frei angelegter Gast fuer den manuellen Generierungslauf (A24, S5 2.5).
 *
 * <p><b>Kein {@code gast_slot}, keine Sitzung, keine Teilnahme.</b> Dieser Gast existiert nur
 * fuer die Dauer einer Rechnung; {@code configs.app_config.anz_guests} gilt fuer ihn nicht -
 * der Wert begrenzt gleichzeitige <i>Gastsitzungen</i>, nicht Mitspieler auf dem Platz. Die
 * Summe begrenzt {@code max_teilnehmer}.
 *
 * @param stufe Selbsteinschaetzung, hier vom Admin gesetzt; <b>Pflicht</b>. Anders als bei der
 *              Zusage am Termin (A17) gibt es hier niemanden, der sie spaeter nachtraegt - der
 *              Ersatzwert {@code MITTEL} aus 2.3 gehoert zur Termin-Quelle und nicht hierher
 * @param name  Anzeigename oder {@code null}. Fehlt er, vergibt der {@code AufstellungService}
 *              {@code Gast 1}, {@code Gast 2}, ... nach der Position in dieser Liste
 */
public record Gastauswahl(GastStufe stufe, String name) {
}
