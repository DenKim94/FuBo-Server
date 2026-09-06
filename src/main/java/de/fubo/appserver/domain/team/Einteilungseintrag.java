package de.fubo.appserver.domain.team;

/**
 * Ein Teilnehmer in der fertigen Teameinteilung, so wie ihn jede Rolle sehen darf
 * (A12, S5 Abschnitt 9.2).
 *
 * <p><b>Drei Angaben, und keine vierte.</b> Keine Skillwerte, keine Scores, keine Gast-Stufe:
 * Die Einteilung erreicht ueber die Einzelansicht eines Termins jede Rolle, auch {@code GAST}.
 * Der Test prueft deshalb die <i>vollstaendige</i> Feldliste und nicht das Fehlen einzelner
 * Namen - was nicht aufgezaehlt ist, faellt sonst niemandem auf.
 *
 * @param anzeigeName       Profilname oder Gastname
 * @param gast              {@code true} bei einem Gast; das Frontend kennzeichnet ihn
 * @param auswechselspieler {@code true} fuer genau einen Teilnehmer des groesseren Teams bei
 *                          ungerader Teilnehmerzahl (A20b), sonst fuer niemanden
 */
public record Einteilungseintrag(String anzeigeName, boolean gast, boolean auswechselspieler) {
}
