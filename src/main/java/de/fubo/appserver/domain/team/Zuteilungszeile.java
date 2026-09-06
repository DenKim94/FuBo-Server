package de.fubo.appserver.domain.team;

import java.time.OffsetDateTime;

/**
 * Ein eingeteilter Teilnehmer, so wie er aus der Datenbank zurueckkommt (S5 Abschnitt 9.1).
 *
 * <h2>Warum {@code staerke} und {@code gemeldetAm} mitkommen, obwohl die Antwort sie nicht
 * traegt</h2>
 * Der Auswechselspieler wird nirgends gespeichert, sondern beim Lesen erneut bestimmt (8.3) -
 * und dafuer braucht es genau diese beiden Angaben, je nach Modus. <b>Aus der Antwort bleiben
 * sie draussen</b>: {@code staerke} ist ein abgeleiteter Skillwert (A12), und die Antwort
 * erreicht jede Rolle, auch {@code GAST}.
 *
 * @param anzeigeName Profilname oder Gastname der Teilnahme
 * @param gast        {@code true}, wenn die Zuteilung an einer Gast-Teilnahme haengt
 * @param team        {@code 'A'} oder {@code 'B'}
 * @param staerke     {@code score_snapshot} in <b>Hundertsteln</b> - dieselbe Einheit wie im
 *                    Lauf, damit die Wahl des Auswechselspielers dieselbe Rechnung ist
 * @param gemeldetAm  Zeitpunkt der Zusage aus {@code spieltag.teilnahme}
 */
public record Zuteilungszeile(String anzeigeName, boolean gast, char team,
                              long staerke, OffsetDateTime gemeldetAm) {
}
