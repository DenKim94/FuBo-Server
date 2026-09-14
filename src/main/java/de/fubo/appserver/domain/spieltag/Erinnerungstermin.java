package de.fubo.appserver.domain.spieltag;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Ein Termin, dessen Erinnerung an offene Rueckmeldungen faellig ist (A25b, Anlass 1; S8
 * Abschnitt 8.2).
 *
 * <h2>Nativ gelesen, nicht als Entity</h2>
 * Der Erinnerungsauftrag markiert den Termin anschliessend mit einem bedingten {@code UPDATE},
 * und der erhoeht {@code version}. <b>Eine im selben Vorgang geladene {@code Termin}-Entity
 * traege danach eine veraltete Version im Speicher</b>, und der naechste Flush scheiterte an
 * einem Sperrkonflikt, den niemand verursacht hat. Dieselbe Regel und dasselbe Muster wie bei
 * {@link Hallentermin} in S7, beim Rueckmeldepfad aus S4 und beim Generierungslauf aus S5.
 *
 * <h2>Genau die vier Felder der Nachricht</h2>
 * {@code status} und {@code push_erinnerung_am} stehen nicht darin, obwohl die Abfrage nach
 * beiden filtert: Sie sind <b>Bedingungen</b>, keine Angaben - der Aufrufer trifft damit keine
 * Entscheidung mehr. Ein mitgefuehrter Wert waere eine Einladung, die Bedingung ein zweites Mal
 * in Java zu pruefen.
 *
 * @param id      betroffener Termin
 * @param datum   Datum in Ortszeit ({@code DATE} ohne Zone)
 * @param uhrzeit Uhrzeit in Ortszeit ({@code TIME} ohne Zone)
 * @param ort     Spielort oder {@code null} (A18)
 */
public record Erinnerungstermin(Long id, LocalDate datum, LocalTime uhrzeit, String ort) {
}
