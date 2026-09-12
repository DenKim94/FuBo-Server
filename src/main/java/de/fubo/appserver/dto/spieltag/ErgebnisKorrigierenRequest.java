package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.Sieger;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/ergebnis/korrigieren} (A21, S6 Abschnitt 3).
 *
 * <h2>Voll-Update, nicht feldweise</h2>
 * <b>Beide Felder sind Pflicht</b>, dazu die {@code version}. Der Grund ist derselbe wie bei
 * der Konfiguration und der Gegensatz zum Termin: <b>{@code deutlich} ist ein
 * {@code boolean}.</b> Feldweise waere {@code false} nicht von "nicht angegeben" zu
 * unterscheiden, und ein einmal gesetzter Haken liesse sich nie wieder entfernen. Bei zwei
 * Feldern, die zusammen einen Ausgang beschreiben, ist das Formular ohnehin die passende
 * Form.
 *
 * <h2>Zur {@code version}</h2>
 * Sie stammt aus der Antwort des Erfassens oder aus der Einzelansicht des Termins. Fehlt sie
 * oder ist sie veraltet, antwortet der Endpunkt mit {@code 409 DATEN_VERALTET} - das heisst
 * "neu laden und erneut speichern", nicht "Eingabe falsch".
 *
 * <p><b>Eine Korrektur, die nichts aendert, wird durchgelassen</b> - anders als beim Termin,
 * wo ein Koerper ohne zu aenderndes Feld {@code 400} liefert. Dort ist es feldweise und ein
 * leerer Koerper ein Programmierfehler des Clients; hier ist es ein Formular, das der Admin
 * absendet, und "ich habe nachgesehen und es stimmt so" ist eine Handlung.
 *
 * @param terminId betroffener Termin; das Ergebnis haengt an ihm und hat keine eigene Adresse
 * @param sieger   neuer Ausgang des Spiels
 * @param deutlich ob der Sieg deutlich ausfiel; bei {@link Sieger#U} unzulaessig
 * @param version  Stand des Ergebnisses; bei Abweichung {@code 409 DATEN_VERALTET}
 */
public record ErgebnisKorrigierenRequest(

        @NotNull(message = "Die Termin-Id fehlt.")
        Long terminId,

        @NotNull(message = "Der Sieger fehlt.")
        Sieger sieger,

        boolean deutlich,

        @NotNull(message = "Die Version fehlt. Vor dem Speichern das Ergebnis lesen.")
        Long version) {

    /**
     * Ein deutliches Unentschieden gibt es nicht (2.5) - dieselbe Pruefung wie beim Erfassen.
     *
     * <p><b>Bewusst wiederholt statt geteilt.</b> Eine gemeinsame Oberklasse gibt es fuer
     * Records nicht, und eine eigene Constraint-Annotation waere fuer zwei Aufrufer mehr
     * Maschinerie als Gewinn. Wer die Regel aendert, aendert sie an beiden Stellen - der
     * Testfall "deutlich bei Unentschieden" laeuft deshalb gegen beide Endpunkte.
     *
     * @return {@code true}, wenn die Kombination zulaessig ist
     */
    @AssertTrue(message = "Ein Unentschieden kann nicht deutlich ausfallen.")
    public boolean isDeutlichNurBeiSieg() {
        return sieger == null || !deutlich || !sieger.istUnentschieden();
    }
}
