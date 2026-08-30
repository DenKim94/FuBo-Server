package de.fubo.appserver.dto.spieltag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/serie/anlegen} (A18, S4 Abschnitt 4).
 *
 * <h2>Was hier geprueft wird und was nicht</h2>
 * Feldgrenzen stehen als Bean-Validation-Annotationen hier; feldeuebergreifende Regeln
 * kann Bean Validation nicht, und davon gibt es zwei:
 * <ul>
 *   <li>{@code enddatum > startdatum} - <b>strikt groesser</b>
 *       ({@code ck_terminserie_zeitraum}). Eine Serie mit nur einem Termin ist damit
 *       unmoeglich; wer einen einzelnen Termin will, nimmt
 *       {@code /admin/termin/anlegen}.</li>
 *   <li>Die Obergrenze von 52 Terminen. Sie ergibt sich erst aus der Rechnung aus
 *       Zeitraum und Wochentakt, nicht aus einem einzelnen Feld - {@code @Max} greift hier
 *       also nicht.</li>
 * </ul>
 * Beide liegen im {@code SerienService}. Die Datenbank bleibt bei der ersten die letzte
 * Instanz, aber sie ist nicht die Instanz, die dem Nutzer antwortet: Der CHECK-Constraint
 * braechte einen {@code 500} mit einem Constraint-Namen im Log.
 *
 * <h2>Der Wochentag zaehlt nach ISO-8601</h2>
 * 1 = Montag bis 7 = Sonntag - so wie {@code ck_terminserie_wochentag} und
 * {@code java.time.DayOfWeek}. Hier ist ausnahmsweise nichts umzurechnen.
 *
 * <p><b>{@code Integer} statt {@code short}:</b> Ein fehlendes Feld ist bei einem
 * Grundtyp nicht von einer eingetragenen {@code 0} zu unterscheiden - der Aufruf liefe
 * dann in den CHECK-Constraint statt in eine benennende Meldung.
 *
 * @param titel      Bezeichnung der Serie; Pflichtfeld, die Spalte ist {@code NOT NULL}
 * @param wochentag  1 (Montag) bis 7 (Sonntag)
 * @param uhrzeit    Uhrzeit aller erzeugten Termine, in Ortszeit
 * @param startdatum Beginn des Zeitraums; faellt er auf den Wochentag, gehoert er dazu
 * @param enddatum   Ende des Zeitraums, einschliesslich; muss nach dem Beginn liegen
 * @param ort        Ort aller erzeugten Termine oder {@code null}
 */
public record SerieAnlegenRequest(

        @NotBlank(message = "Der Titel fehlt.")
        @Size(max = 80, message = "Der Titel darf höchstens 80 Zeichen lang sein.")
        String titel,

        @NotNull(message = "Der Wochentag fehlt.")
        @Min(value = 1, message = "Der Wochentag reicht von 1 (Montag) bis 7 (Sonntag).")
        @Max(value = 7, message = "Der Wochentag reicht von 1 (Montag) bis 7 (Sonntag).")
        Integer wochentag,

        @NotNull(message = "Die Uhrzeit fehlt.")
        LocalTime uhrzeit,

        @NotNull(message = "Das Startdatum fehlt.")
        LocalDate startdatum,

        @NotNull(message = "Das Enddatum fehlt.")
        LocalDate enddatum,

        @Size(max = 160, message = "Der Ort darf höchstens 160 Zeichen lang sein.")
        String ort) {

    /** Der Titel ohne Randleerzeichen. */
    public String titelBereinigt() {
        return titel == null ? null : titel.trim();
    }

    /** Der Ort ohne Randleerzeichen; eine leere Angabe wird zu {@code null}. */
    public String ortBereinigt() {
        if (ort == null || ort.isBlank()) {
            return null;
        }
        return ort.trim();
    }
}
