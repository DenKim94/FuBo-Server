package de.fubo.appserver.domain.spieltag;

/**
 * Ausgang eines Spiels (A21, S6 Abschnitt 2).
 *
 * <h2>Warum ein Aufzaehlungstyp und nicht die Spalte selbst</h2>
 * {@code spieltag.ergebnis.sieger} ist {@code CHAR(1)} mit
 * {@code ck_ergebnis_sieger CHECK (sieger IN ('A','B','U'))}. Die Datenbank bleibt damit die
 * letzte Instanz - geprueft wird trotzdem vorher: Ein loses Zeichen im Anfragekoerper braechte
 * bei einem Tippfehler eine {@code DataIntegrityViolationException} und daraus einen
 * {@code 500}, waehrend ein unbekannter Aufzaehlungswert schon an der API-Grenze mit
 * {@code 400 EINGABE_UNGUELTIG} scheitert.
 *
 * <p><b>Der Vertrag fuehrt ihn als eigenes Schema</b> ({@code Sieger}) und bindet ihn per
 * {@code $ref} ein - dieselbe Behandlung wie {@code Rolle}, {@code Stage}, {@code GastStufe}
 * und {@code AlgorithmType}. Der Generator des Client-Tracks macht daraus einen
 * Aufzaehlungstyp statt eines losen Zeichenkettenfelds.
 *
 * <h2>{@code U} ist ein dritter Ausgang, kein fehlender Sieger</h2>
 * Deshalb steht er hier und nicht als {@code null} in der Spalte: Ein Unentschieden ist ein
 * Ergebnis wie jedes andere und zaehlt in {@code profil.spieler.anz_unentschieden} mit. Ein
 * {@code null} liesse sich von "noch nicht erfasst" nicht unterscheiden - und genau das ist
 * das fehlende Ergebnis selbst, das gar keine Zeile hat.
 */
public enum Sieger {

    /** Team A hat gewonnen. */
    A('A'),

    /** Team B hat gewonnen. */
    B('B'),

    /** Unentschieden; {@code deutlich} ist dann unzulaessig (2.5). */
    U('U');

    private final char kennung;

    Sieger(char kennung) {
        this.kennung = kennung;
    }

    /**
     * Liefert das Zeichen, das in der Spalte steht.
     *
     * @return {@code 'A'}, {@code 'B'} oder {@code 'U'}
     */
    public char kennung() {
        return kennung;
    }

    /**
     * Uebersetzt das Zeichen aus der Datenbank zurueck.
     *
     * <p><b>Ein unbekanntes Zeichen ist kein Eingabefall</b>, sondern ein Bruch zwischen
     * Spalte und Aufzaehlungstyp: {@code ck_ergebnis_sieger} laesst nur die drei Werte zu, ein
     * vierter kann nur durch eine Migration oder einen Eingriff von Hand entstehen. Deshalb
     * eine {@link IllegalStateException} und kein {@code FachlicherFehler}.
     *
     * @param kennung Zeichen aus {@code spieltag.ergebnis.sieger}
     * @return der zugehoerige Ausgang
     */
    public static Sieger vonKennung(char kennung) {
        for (Sieger sieger : values()) {
            if (sieger.kennung == kennung) {
                return sieger;
            }
        }
        throw new IllegalStateException("Unbekannter Sieger in der Datenbank: " + kennung);
    }

    /** @return {@code true}, wenn das Spiel unentschieden ausgegangen ist */
    public boolean istUnentschieden() {
        return this == U;
    }
}
