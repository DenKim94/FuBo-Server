package de.fubo.appserver.domain.spieltag;

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
 * @param termin     Stammdaten, Zaehler und die eigene Rueckmeldung des Aufrufers
 * @param teilnehmer die Zusagen in Warteschlangenreihenfolge samt der geltenden Grenzen
 */
public record TerminMitTeilnehmern(TerminEintrag termin, Teilnehmeruebersicht teilnehmer) {
}
