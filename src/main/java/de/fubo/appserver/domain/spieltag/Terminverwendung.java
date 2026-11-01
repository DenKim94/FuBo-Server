package de.fubo.appserver.domain.spieltag;

/**
 * Was an einem Termin hing, als er entfernt wurde (A19, Ergaenzung vom 31.10.2026).
 *
 * <p>Ein <b>abgesagter</b> Termin laesst sich auch entfernen, wenn Rueckmeldungen oder
 * Generierungslaeufe vorliegen; die Kaskaden loeschen sie mit. Danach ist der
 * Protokolleintrag der einzige Beleg, dass es sie gab - diese Zahlen kommen deshalb
 * <b>vor</b> dem {@code DELETE} hinein.
 *
 * @param teilnahmen   Rueckmeldungen insgesamt, Zusagen wie Absagen, Spieler wie Gaeste
 * @param zusagen      davon Zusagen
 * @param generierungen Generierungslaeufe ({@code team_generierung}), einschliesslich der
 *                      verworfenen frueheren Einteilungen
 */
public record Terminverwendung(int teilnahmen, int zusagen, int generierungen) {
}
