package de.fubo.appserver.domain.spieltag;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Ein Termin ist von {@code GEPLANT} auf {@code ABGESAGT} gewechselt (A25b, Anlass 2; S8
 * Abschnitt 9.1).
 *
 * <h2>S8 fuehrt damit das erste Ereignis des Projekts ein</h2>
 * Bis S7 gibt es weder einen {@code ApplicationEventPublisher} noch einen Listener. Der Grund
 * fuer den Bruch mit dem bisherigen Aufbau ist <b>nicht</b> Entkopplung um ihrer selbst
 * willen, sondern die Zahl der Ausloeser.
 *
 * <h2>Ausgeloest wird vom Statuswechsel, nicht vom Endpunkt</h2>
 * {@code AGENT.md} nennt {@code POST /admin/termin/absagen}. Es gibt aber <b>drei</b> Wege von
 * {@code GEPLANT} nach {@code ABGESAGT}:
 * <ol>
 *   <li>{@code TerminService#absagen} - der in {@code AGENT.md} genannte (S4),</li>
 *   <li>{@code TerminService#aendern} mit Zielstatus {@code ABGESAGT} (S4, A19),</li>
 *   <li>{@code HallenService#absagen}, das seit S7 einen geplanten Termin mit absagt.</li>
 * </ol>
 * <b>Hinge die Benachrichtigung am Endpunkt, blieben zwei der drei Wege stumm</b> - und es
 * fiele niemandem auf, weil eine ausbleibende Benachrichtigung der Normalfall ist. Ein
 * Ereignis am Statuswechsel gibt es dreimal an drei Stellen und einmal an einer Stelle zu
 * empfangen.
 *
 * <h2>Der Statuswechsel ist zugleich die Einmal-Bedingung</h2>
 * {@code ABGESAGT} ist nur aus {@code GEPLANT} heraus erreichbar und endgueltig. <b>Eine
 * zusaetzliche Spalte wie {@code push_erinnerung_am} braucht es hier deshalb nicht</b>; bei
 * {@code HallenService} ist der bedingte {@code UPDATE} auf {@code status = 'GEPLANT'} selbst
 * die Abfrage danach, und nur wenn er eine Zeile trifft, wird veroeffentlicht.
 *
 * <h2>Die Angaben reisen mit, statt nachgelesen zu werden</h2>
 * Der Empfaenger braucht Datum, Uhrzeit und Ort fuer den Text der Nachricht. Sie mitzugeben
 * spart eine Abfrage - und, was mehr zaehlt, es bindet die Nachricht an den Stand, der
 * tatsaechlich abgesagt wurde: Bei {@code aendern} koennen Datum und Uhrzeit im <i>selben</i>
 * Aufruf geaendert worden sein.
 *
 * <p><b>Kein Handelnder im Ereignis.</b> Der Versand ist eine Systemfolge, nicht die Handlung
 * selbst - die steht mit Handelndem und Client-Adresse schon als {@code TERMIN_ABGESAGT} im
 * Protokoll. Ein zweites Mal denselben Akteur zu fuehren verdoppelte personenbezogene Daten
 * ohne Gewinn, und die Client-Adresse in einem fachlichen Ereignis waere ein Transportdetail
 * an der falschen Stelle.
 *
 * @param terminId abgesagter Termin
 * @param datum    Datum in Ortszeit, Stand nach dem Vorgang
 * @param uhrzeit  Uhrzeit in Ortszeit, Stand nach dem Vorgang
 * @param ort      Spielort oder {@code null}
 */
public record TerminAbgesagtEreignis(Long terminId, LocalDate datum, LocalTime uhrzeit,
                                     String ort) {
}
