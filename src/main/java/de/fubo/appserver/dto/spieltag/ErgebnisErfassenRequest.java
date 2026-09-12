package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.Sieger;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Anfragekoerper von {@code POST /api/v1/ergebnis/erfassen} (A21, S6 Abschnitt 2).
 *
 * <h2>Was hier <i>nicht</i> drinsteht</h2>
 * Der Erfasser. <b>Die Identitaet kommt aus der Sitzung, nie aus dem Anfragekoerper</b> -
 * sonst koennte ein Gast das Ergebnis unter einem beliebigen Namen eintragen, und das Feld
 * {@code erfasstVon} waere als Auskunft wertlos. Dieselbe Regel wie bei der Rueckmeldung und
 * beim Generierungslauf.
 *
 * <p>Ebenso wenig ein Spielstand: {@code sieger} und {@code deutlich} sind die ganze
 * Erfassung (A21). Torschuetzen, Halbzeiten und Tore sind ausdruecklich nicht Gegenstand.
 *
 * @param terminId betroffener Termin; er muss {@code ABGESCHLOSSEN} sein und eine
 *                 unabgeloeste Teameinteilung haben
 * @param sieger   Ausgang des Spiels
 * @param deutlich ob der Sieg deutlich ausfiel; bei {@link Sieger#U} unzulaessig
 */
public record ErgebnisErfassenRequest(

        @NotNull(message = "Die Termin-Id fehlt.")
        Long terminId,

        @NotNull(message = "Der Sieger fehlt.")
        Sieger sieger,

        boolean deutlich) {

    /**
     * Ein deutliches Unentschieden gibt es nicht (2.5).
     *
     * <h2>Warum die Pruefung am DTO steht und nicht im Dienst</h2>
     * Sie verbindet zwei Felder <i>desselben</i> Anfragekoerpers, und die Auslegung des
     * Anfragekoerpers ist Aufgabe der API-Grenze. Der sichtbare Gewinn: Die Antwort traegt
     * denselben {@code felder}-Block wie jede andere Eingabepruefung, statt eines
     * Sonderformats aus der Fachlogik.
     *
     * <p><b>Der Methodenname bestimmt den Schluessel im {@code felder}-Block.</b> Bean
     * Validation erkennt {@code isXyz()} als Eigenschaft {@code xyz}; der Fehler erscheint
     * also unter {@code deutlichNurBeiSieg} und nicht unter {@code deutlich}. Das ist
     * gewollt - beanstandet wird die Kombination, nicht eines der beiden Felder.
     *
     * <p><b>{@code deutlich} aendert an der Bilanz nichts</b> - es beschreibt die Hoehe, nicht
     * den Ausgang. Das ist der haeufigste Irrtum beim Lesen von A21.
     *
     * @return {@code true}, wenn die Kombination zulaessig ist
     */
    @AssertTrue(message = "Ein Unentschieden kann nicht deutlich ausfallen.")
    public boolean isDeutlichNurBeiSieg() {
        // Fehlt der Sieger, meldet bereits @NotNull; diese Pruefung haelt sich dann heraus.
        return sieger == null || !deutlich || !sieger.istUnentschieden();
    }
}
