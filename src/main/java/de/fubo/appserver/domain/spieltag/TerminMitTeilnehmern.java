package de.fubo.appserver.domain.spieltag;

import de.fubo.appserver.domain.team.Einteilung;

import java.time.LocalDateTime;

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
 * Record die Stelle, an der die Einzelansicht eines Termins vollstaendig zusammenlaeuft.
 *
 * <p><b>Mit S7 kam die Absagefrist dazu</b> (A23, Entscheidung des Haupt-Entwicklers vom
 * 13.09.2026) - und zwar entgegen dem Satz, der hier stand: ein fuenftes Feld sei ein Anlass,
 * ueber einen eigenen Endpunkt nachzudenken. <b>Der Anlass wurde geprueft und verworfen</b>, aus
 * zwei Gruenden: Es ist kein weiteres Unterobjekt, sondern ein einzelner abgeleiteter Zeitpunkt,
 * und er entsteht aus {@code datum}, {@code uhrzeit} und einem Konfigurationswert - also aus
 * Daten, die diese Abfrage ohnehin in der Hand hat. Ein eigener Endpunkt dafuer waere ein
 * zweiter Aufruf fuer eine Zahl. <b>Der naechste Zusatz gehoert wirklich geprueft</b>, nicht
 * angehaengt.
 *
 * @param termin      Stammdaten, Zaehler und die eigene Rueckmeldung des Aufrufers
 * @param teilnehmer  die Zusagen in Warteschlangenreihenfolge samt der geltenden Grenzen
 * @param einteilung  die aktuelle Teameinteilung oder {@code null}, wenn es noch keine gibt
 * @param ergebnis    das erfasste Ergebnis oder {@code null}, wenn noch keines vorliegt.
 *                    <b>Beide Nullwerte sind unabhaengig voneinander:</b> Eine Einteilung ohne
 *                    Ergebnis ist der Normalfall vor dem Spiel; ein Ergebnis ohne Einteilung
 *                    kann es dagegen nicht geben - der Eintragspfad lehnt es ab
 * @param halleAbsageMoeglichBis spaetester Zeitpunkt, zu dem {@code /admin/halle/absagen} die
 *                    Absage noch annimmt: {@code datum + uhrzeit} minus
 *                    {@code halle_vorlauf_stunden} (A23). <b>Immer gefuellt</b>, auch fuer
 *                    vergangene und abgesagte Termine und auch ohne hinterlegte Hallenadresse -
 *                    es ist eine Tatsache ueber den Termin und keine Aussage darueber, wer was
 *                    darf. Ortszeit ohne Zone, wie {@code datum} und {@code uhrzeit}, aus denen
 *                    er entsteht
 */
public record TerminMitTeilnehmern(TerminEintrag termin,
                                   Teilnehmeruebersicht teilnehmer,
                                   Einteilung einteilung,
                                   Ergebnis ergebnis,
                                   LocalDateTime halleAbsageMoeglichBis) {
}
