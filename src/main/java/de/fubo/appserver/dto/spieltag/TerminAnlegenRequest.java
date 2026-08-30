package de.fubo.appserver.dto.spieltag;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/termin/anlegen} (S4, Abschnitt 3.2).
 *
 * <h2>Warum hier kein {@code @Future} steht</h2>
 * Die Pruefung "nicht in der Vergangenheit" braucht die {@code Clock}-Bean, und
 * {@code @Future} an einem {@code LocalDate} prueft gegen die Systemuhr. Der Grenzfall
 * "eine Minute vor Beginn" liesse sich dann nur mit {@code Thread.sleep} testen. Die
 * Pruefung liegt deshalb im Dienst - dieselbe Trennung wie bei den Sperrfristen des
 * {@code BruteForceService}.
 *
 * <p><b>Zusaetzlich reicht der Tag allein nicht.</b> Die Anleitung nennt in Abschnitt 3.2
 * "datum nicht in der Vergangenheit", begruendet die Regel aber mit Weggabelung C: Zugesagt
 * wird bis <i>Terminbeginn</i>. Ein Termin, der heute um 8 Uhr war und um 20 Uhr angelegt
 * wird, naehme also nie eine Rueckmeldung entgegen. Der Dienst prueft deshalb Datum
 * <i>und</i> Uhrzeit.
 *
 * @param datum   Datum in Ortszeit
 * @param uhrzeit Uhrzeit in Ortszeit; Minutengenauigkeit genuegt
 * @param ort     Spielort oder {@code null} (A18); hoechstens 160 Zeichen, wie die Spalte
 */
public record TerminAnlegenRequest(

        @NotNull(message = "Das Datum fehlt.")
        LocalDate datum,

        @NotNull(message = "Die Uhrzeit fehlt.")
        LocalTime uhrzeit,

        @Size(max = 160, message = "Der Ort darf höchstens 160 Zeichen lang sein.")
        String ort) {

    /**
     * Der Ort ohne Randleerzeichen; eine leere Angabe wird zu {@code null}.
     *
     * <p>Die Auslegung des Anfragekoerpers gehoert ins DTO und nicht in den Dienst: Ein
     * Formularfeld, das beim Leeren {@code ""} statt {@code null} sendet, ist eine
     * Eigenheit der API-Grenze - dieselbe Regel wie bei {@code halleEmailBereinigt()}.
     */
    public String ortBereinigt() {
        if (ort == null || ort.isBlank()) {
            return null;
        }
        return ort.trim();
    }
}
