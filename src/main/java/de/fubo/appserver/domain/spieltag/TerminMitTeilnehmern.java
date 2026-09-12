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
 * <p><b>Mit S6 kam das Ergebnis dazu</b> (Weggabelung B, 5.1) - dieselbe Ueberlegung ein
 * viertes Mal: Termin, Teilnehmer, Teams und Ausgang gehoeren in eine Ansicht. Damit ist der
 * Record die Stelle, an der die Einzelansicht eines Termins vollstaendig zusammenlaeuft;
 * <b>ein weiteres Feld waere ein Anlass, ueber einen eigenen Endpunkt nachzudenken</b>, nicht
 * ueber ein fuenftes.
 *
 * @param termin      Stammdaten, Zaehler und die eigene Rueckmeldung des Aufrufers
 * @param teilnehmer  die Zusagen in Warteschlangenreihenfolge samt der geltenden Grenzen
 * @param einteilung  die aktuelle Teameinteilung oder {@code null}, wenn es noch keine gibt
 * @param ergebnis    das erfasste Ergebnis oder {@code null}, wenn noch keines vorliegt.
 *                    <b>Beide Nullwerte sind unabhaengig voneinander:</b> Eine Einteilung ohne
 *                    Ergebnis ist der Normalfall vor dem Spiel; ein Ergebnis ohne Einteilung
 *                    kann es dagegen nicht geben - der Eintragspfad lehnt es ab
 */
public record TerminMitTeilnehmern(TerminEintrag termin,
                                   Teilnehmeruebersicht teilnehmer,
                                   Einteilung einteilung,
                                   Ergebnis ergebnis) {
}
