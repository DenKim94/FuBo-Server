package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/pin/aendern} (A3).
 *
 * <p><b>Kein Feld fuer die alte PIN.</b> Die zentrale PIN ist ein geteiltes Geheimnis, kein
 * persoenliches: Der Admin verwaltet sie, er beweist sie nicht - und er hat sie ohnehin
 * gerade eingegeben, um in die Stufe {@code PIN_VERIFIED} und von dort in den Adminbereich
 * zu gelangen.
 *
 * @param neuePin Klartext der neuen zentralen PIN
 */
public record PinAendernRequest(

        /*
         * Genau vier Ziffern (Festlegung des Haupt-Entwicklers vom 23.08.2026).
         *
         * - Rein numerisch und kurz, weil die PIN muendlich oder ueber einen Aushang
         *   weitergegeben und haeufig auf einer Zifferntastatur eingegeben wird. Eine feste
         *   Laenge erlaubt dem Frontend zudem ein Eingabefeld mit vier Kaestchen statt einer
         *   Textzeile mit unklarer Erwartung.
         * - Feste Laenge statt einer Spanne: Eine Spanne muesste das Frontend sichtbar
         *   machen ("4 bis 10 Ziffern"), ohne dass jemand davon etwas haette - die PIN wird
         *   ohnehin vorgegeben und nicht frei gewaehlt.
         *
         * WICHTIG: 10 000 Moeglichkeiten sind wenig. Tragfaehig wird die PIN erst durch den
         * BruteForceService - fuenf Fehlversuche je Adresse, 30 insgesamt, steigende
         * Sperrdauern. Diese Drosselung darf nicht abgeschwaecht werden, solange die PIN
         * vierstellig ist.
         *
         * Der Start-Bootstrap erzeugt seine Ersatz-PIN aus demselben Grund ebenfalls
         * vierstellig. FUBO_INITIAL_PIN bleibt davon unberuehrt: Das ist eine Betriebsangabe,
         * keine Eingabe an der API-Grenze - und der Anmeldeendpunkt schreibt der PIN bewusst
         * kein Format vor (siehe PinLoginRequest), damit ein abweichender Bestandswert
         * weiterhin funktioniert.
         */
        @NotBlank(message = "Die neue PIN darf nicht leer sein.")
        @Pattern(regexp = "\\d{4}", message = "Die PIN besteht aus genau vier Ziffern.")
        String neuePin) {
}
