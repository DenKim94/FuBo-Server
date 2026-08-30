package de.fubo.appserver.dto.spieltag;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/termin/aendern} (S4, Abschnitt 3.5).
 *
 * <h2>Feldweise, kein Voll-Update</h2>
 * Anders als bei der Konfiguration. Der Grund fuer das Voll-Update dort sind die
 * {@code null}-faehigen Felder: Feldweise waere {@code null} nicht von "nicht angegeben" zu
 * unterscheiden, und eine einmal gesetzte Hallenadresse liesse sich nie wieder entfernen.
 * Hier gibt es genau ein solches Feld, {@code ort}, und das laesst sich mit einer leeren
 * Zeichenkette sauber leeren. Damit entfaellt der Grund, und es bleibt die Regel aus S3:
 * <b>Weglassen heisst "nicht aendern".</b>
 *
 * <h2>Drei Bedeutungen von {@code ort}</h2>
 * <table border="1">
 *   <caption>Auslegung des Feldes</caption>
 *   <tr><th>Wert</th><th>Wirkung</th></tr>
 *   <tr><td>Text</td><td>setzt den Ort</td></tr>
 *   <tr><td>{@code ""}</td><td>leert den Ort</td></tr>
 *   <tr><td>{@code null} oder nicht vorhanden</td><td>laesst ihn unveraendert</td></tr>
 * </table>
 * Deshalb hat dieses DTO kein {@code ortBereinigt()} wie {@link TerminAnlegenRequest}: Dort
 * wird die leere Angabe zu {@code null}, hier waeren beide dann nicht mehr zu unterscheiden.
 * Getrimmt wird trotzdem, und zwar im Dienst, der ohnehin entscheiden muss, ob ein Feld
 * angegeben war.
 *
 * @param terminId zu aendernder Termin
 * @param version  Stand aus der Einzelansicht; bei Abweichung {@code 409 DATEN_VERALTET}
 * @param datum    neues Datum oder {@code null} fuer "unveraendert"
 * @param uhrzeit  neue Uhrzeit oder {@code null} fuer "unveraendert"
 * @param ort      neuer Ort, leere Zeichenkette zum Leeren, {@code null} fuer "unveraendert"
 */
public record TerminAendernRequest(

        @NotNull(message = "Die Termin-Id fehlt.")
        Long terminId,

        @NotNull(message = "Die Version fehlt. Vor dem Speichern den Termin lesen.")
        Long version,

        LocalDate datum,

        LocalTime uhrzeit,

        @Size(max = 160, message = "Der Ort darf höchstens 160 Zeichen lang sein.")
        String ort) {

    /**
     * Meldet, ob der Aufruf ueberhaupt etwas aendern will.
     *
     * <p>Ein Aufruf ohne jede Angabe wird abgelehnt: Er taete nichts, hinterliesse aber
     * einen Protokolleintrag und eine erhoehte {@code version} - dieselbe Regel wie bei
     * {@code /admin/user/bearbeiten}.
     */
    public boolean ohneAenderung() {
        return datum == null && uhrzeit == null && ort == null;
    }
}
