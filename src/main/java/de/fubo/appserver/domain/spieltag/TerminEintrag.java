package de.fubo.appserver.domain.spieltag;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Ergebniszeile der Terminuebersicht (S4, Abschnitt 2.3).
 *
 * <p>Bewusst <b>kein</b> JPA-Entity und keine {@link Termin}-Instanz. Die Zeile entsteht aus
 * einer Aggregation ueber zwei Tabellen; eine teilweise befuellte Entity zurueckzugeben waere
 * irrefuehrend, weil sie aussaehe wie ein verwaltetes Objekt, und ein {@code save()} darauf
 * Daten ueberschriebe. Dasselbe Muster wie bei {@code AktiveSitzung} und {@code Profileintrag}.
 *
 * <p>Der Record traegt bereits die {@code version}, obwohl die Liste sie nicht nach aussen
 * gibt: Dieselbe Abfrage bedient die Einzelansicht, und ein zweiter Lesezugriff nur fuer einen
 * Zaehler waere Verschwendung.
 *
 * @param id                technischer Schluessel des Termins
 * @param serieId           Serie oder {@code null} bei einem Einzeltermin
 * @param datum             Datum in Ortszeit
 * @param uhrzeit           Uhrzeit in Ortszeit
 * @param ort               Spielort oder {@code null}
 * @param status            Zustand des Termins
 * @param teilnehmerVersion Zaehler der Teilnehmeraenderungen (A15)
 * @param version           Stand der Zeile fuer das Optimistic Locking
 * @param zusagen           Anzahl der Zusagen; Absagen zaehlen nicht mit
 * @param eigeneRueckmeldung {@code true} zugesagt, {@code false} abgesagt,
 *                           <b>{@code null} noch nicht gemeldet</b> - drei Zustaende, nicht zwei
 */
public record TerminEintrag(Long id,
                            Long serieId,
                            LocalDate datum,
                            LocalTime uhrzeit,
                            String ort,
                            TerminStatus status,
                            int teilnehmerVersion,
                            Long version,
                            int zusagen,
                            Boolean eigeneRueckmeldung) {
}
