package de.fubo.appserver.domain.team;

/**
 * Ein eingeteilter Teilnehmer, so wie er aus der Datenbank zurueckkommt (S5 Abschnitt 9.1).
 *
 * <p><b>Ohne Staerke und ohne Meldezeit (seit 03.11.2026).</b> Beide wurden nur gebraucht, um
 * den Auswechselspieler beim Lesen neu zu bestimmen. Der Springer steht seither in
 * {@code team_zuteilung.springer}; was nicht gebraucht wird, wird nicht gelesen - und ein
 * abgeleiteter Skillwert ({@code score_snapshot}) ist genau das, was in der Antwort an jede
 * Rolle nicht stehen darf (A12).
 *
 * @param anzeigeName Profilname oder Gastname der Teilnahme
 * @param gast        {@code true}, wenn die Zuteilung an einer Gast-Teilnahme haengt
 * @param team        {@code 'A'} oder {@code 'B'} - beim Springer das Team, bei dem er beginnt
 * @param springer    {@code true} fuer den Springer des Laufs, hoechstens einmal je Lauf
 *                    ({@code uq_team_zuteilung_springer})
 */
public record Zuteilungszeile(String anzeigeName, boolean gast, char team, boolean springer) {
}
