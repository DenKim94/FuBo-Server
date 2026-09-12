package de.fubo.appserver.domain.profil;

/**
 * Siege, Niederlagen und Unentschieden eines Spielers (A21 in der Ergaenzung vom 30.08.2026,
 * {@code V011}; S6 Abschnitt 4).
 *
 * <h2>Die Zaehler sind eine Projektion, keine eigene Wahrheit</h2>
 * Sie werden bei jeder Ergebnisaenderung <b>neu berechnet</b> und nicht fortgeschrieben.
 * {@code +1}/{@code -1} setzte voraus, dass die Teameinteilung zwischen Eintrag und Korrektur
 * gleich bleibt; taete sie es nicht, traefe die Ruecknahme andere Spieler als die Erhoehung -
 * und es gaebe keine zweite Quelle, an der das auffiele, weil der Zaehler selbst die einzige
 * waere. Dass die Einteilung nach dem Terminabschluss tatsaechlich eingefroren ist, macht
 * {@code +1}/{@code -1} nicht richtig, sondern nur zufaellig unschaedlich.
 *
 * <p><b>Ein Gast taucht hier nie auf.</b> Er hat keine Zeile in {@code profil.spieler}; ein
 * Zaehler an {@code gast_slot} summierte die Ergebnisse verschiedener Personen, weil der Platz
 * wiederverwendet wird (Begruendung in {@code V011}).
 *
 * <p>Der Record ueberschreitet die API-Grenze nie; nach aussen geht {@code dto.profil.Bilanz}.
 *
 * @param siege         gewonnene Spiele
 * @param niederlagen   verlorene Spiele
 * @param unentschieden unentschiedene Spiele; ein dritter Ausgang, kein fehlender Sieger
 */
public record Bilanzstand(int siege, int niederlagen, int unentschieden) {

    /**
     * Die leere Bilanz.
     *
     * <p>Gebraucht an zwei Stellen, und an beiden bedeutet sie dasselbe - "nichts zu zeigen":
     * bei einem Spieler, dessen Termine noch kein Ergebnis haben, und bei einem Gast, der
     * grundsaetzlich keine fuehrt. <b>Beide Faelle absichtlich nicht unterscheidbar</b>: Die
     * Antwort waere in jedem Fall dreimal die Null, und ein zusaetzliches Kennzeichen
     * verriete, wer Gast ist, ohne dass jemand danach gefragt hat.
     */
    public static final Bilanzstand LEER = new Bilanzstand(0, 0, 0);

    /** @return Zahl der gewerteten Spiele */
    public int gesamt() {
        return siege + niederlagen + unentschieden;
    }
}
