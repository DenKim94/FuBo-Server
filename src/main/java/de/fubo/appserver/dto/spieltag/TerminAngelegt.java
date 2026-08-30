package de.fubo.appserver.dto.spieltag;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Antwort von {@code POST /api/v1/admin/termin/anlegen} und zugleich ein Eintrag in
 * {@link SerieAngelegt} (S4, Abschnitte 3 und 4).
 *
 * <p>Die Id ist der einzige Wert, den der Aufrufer nicht schon kennt - ohne sie muesste er
 * die Terminliste erneut lesen, um den eben angelegten Termin aendern oder absagen zu
 * koennen. Dasselbe Muster wie bei {@code SpielerAngelegt}.
 *
 * <p><b>Warum ein Objekt und nicht die blosse Zahl:</b> Die Anleitung nennt in Abschnitt 3.1
 * nur "201 -&gt; Id". Eine nackte Zahl als Antwortkoerper laesst sich spaeter nicht
 * erweitern, ohne den Vertrag zu brechen - jedes zusaetzliche Feld waere dann ein
 * Typwechsel. Datum und Uhrzeit kommen mit, weil sie in der Serie je Eintrag verschieden
 * sind und dort ohnehin gebraucht werden.
 *
 * @param terminId Id des neuen Termins
 * @param datum    uebernommenes Datum
 * @param uhrzeit  uebernommene Uhrzeit
 */
public record TerminAngelegt(Long terminId, LocalDate datum, LocalTime uhrzeit) {
}
