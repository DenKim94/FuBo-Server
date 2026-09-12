package de.fubo.appserver.dto.profil;

import de.fubo.appserver.domain.profil.Bilanzstand;

/**
 * Siege, Niederlagen und Unentschieden eines Spielers nach aussen (A21 in der Ergaenzung vom
 * 30.08.2026; S6 Abschnitt 4.5).
 *
 * <h2>Ein Schema an zwei Stellen</h2>
 * Der Record erscheint als Feld {@code bilanz} in {@code dto.admin.SpielerDetails} und als
 * Antwort von {@code GET /api/v1/bilanz/lesen}. <b>Bewusst dasselbe Schema und nicht zweimal
 * drei Zahlen:</b> Zwei Formen derselben Auskunft wuerden auseinanderlaufen, sobald eine
 * davon einmal ein Feld bekommt.
 *
 * <h2>Kein A12-Fall</h2>
 * Die Bilanz ist Statistik und kein Skillwert - sie darf den Server auch in Richtung
 * {@code USER} verlassen. A16 bleibt unberuehrt: Ergebnisse wirken nicht auf Skills zurueck,
 * und diese Zaehler tun es auch nicht.
 *
 * <p><b>Dreimal Null bedeutet "nichts zu zeigen"</b> - bei einem Spieler ohne gewertete
 * Termine wie bei einem Gast, der grundsaetzlich keine Bilanz fuehrt. Beide Faelle sind
 * absichtlich nicht unterscheidbar; ein Kennzeichen verriete, wer Gast ist.
 *
 * @param siege         gewonnene Spiele
 * @param niederlagen   verlorene Spiele
 * @param unentschieden unentschiedene Spiele; {@code deutlich} spielt fuer keinen der drei
 *                      Zaehler eine Rolle - es beschreibt die Hoehe, nicht den Ausgang
 */
public record Bilanz(int siege, int niederlagen, int unentschieden) {

    /**
     * Bildet das Wertobjekt auf den Vertrag ab.
     *
     * <p>Die Abbildung steht hier und nicht im Dienst - dieselbe Aufteilung wie bei
     * {@code SpielerDetails#von}.
     *
     * @param stand die gelesenen Zaehler
     * @return das Antwortobjekt
     */
    public static Bilanz von(Bilanzstand stand) {
        return new Bilanz(stand.siege(), stand.niederlagen(), stand.unentschieden());
    }
}
