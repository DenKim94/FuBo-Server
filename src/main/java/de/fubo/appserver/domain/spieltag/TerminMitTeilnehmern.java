package de.fubo.appserver.domain.spieltag;

import de.fubo.appserver.domain.team.Einteilung;

/**
 * Ergebnis der Einzelansicht: der Termin und seine Teilnehmer (S4, Abschnitt 2.1).
 *
 * <p><b>Ein Rueckgabewert und nicht zwei Aufrufe.</b> Wer einen Termin oeffnet, will die
 * Teilnehmer sehen; zwei Aufrufe fuer eine Ansicht sind zwei Gelegenheiten fuer einen
 * inkonsistenten Stand - zwischen ihnen kann jemand zusagen, und der Zaehler im Kopf passte
 * dann nicht mehr zur Liste darunter.
 *
 * <p>Der Record buendelt, was der Dienst aus zwei Abfragen holt, und haelt beides zusammen,
 * bis das DTO daraus die Antwort baut. Beide Abfragen laufen in derselben Transaktion und
 * damit auf demselben Stand.
 *
 * <p><b>Mit S5 kam die Teameinteilung dazu</b> (Weggabelung B, 9.1) - aus demselben Grund und
 * als drittes Feld: Das Dashboard zeigt Termin, Teilnehmerliste und Teams zusammen. Sie fehlt,
 * solange niemand generiert hat; das ist der Normalzustand und kein Fehler.
 *
 * @param termin      Stammdaten, Zaehler und die eigene Rueckmeldung des Aufrufers
 * @param teilnehmer  die Zusagen in Warteschlangenreihenfolge samt der geltenden Grenzen
 * @param einteilung  die aktuelle Teameinteilung oder {@code null}, wenn es noch keine gibt
 */
public record TerminMitTeilnehmern(TerminEintrag termin,
                                   Teilnehmeruebersicht teilnehmer,
                                   Einteilung einteilung) {
}
