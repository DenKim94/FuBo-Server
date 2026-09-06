package de.fubo.appserver.domain.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ein fertig gerechneter Generierungslauf, bevor irgendetwas mit ihm geschieht
 * (S5 Abschnitte 7 bis 9).
 *
 * <h2>Die gemeinsame Ausgabe beider Eingangstueren</h2>
 * Der Termin-Lauf schreibt ihn in {@code spieltag.team_generierung} und gibt ihn zurueck; der
 * manuelle Lauf nach A24 gibt ihn nur zurueck. <b>Gerechnet wird beide Male dasselbe</b> - das
 * ist der Kern von "zweite Eingangstuer, kein zweiter Generator".
 *
 * <h2>Er buendelt, was zusammen entstanden ist</h2>
 * Aufstellung, Aufteilung, Auswechselspieler und Seed gehoeren zu <i>einem</i> Lauf. Sie
 * einzeln durchzureichen hiesse, dass jeder Aufrufer sie wieder zusammenfuehrt - und der
 * erste, der eine Aufteilung mit der falschen Aufstellung paart, bekommt Namen zu Indizes, die
 * nie zusammengehoerten.
 *
 * @param aufstellung        wer gerechnet wurde; die Indizes der Aufteilung zeigen hierauf
 * @param aufteilung         die beiden Teams, Kosten und das tatsaechlich verwendete Verfahren
 * @param staerken           gewichtete Gesamtstaerke je Aufstellungsindex, in Hundertsteln.
 *                           <b>Sie reist mit, statt dreimal gerechnet zu werden</b>: Der
 *                           Auswechselspieler braucht sie, der {@code score_snapshot} jeder
 *                           Zuteilung ebenfalls (7.2) - und eine zweite Rechnung ist eine
 *                           zweite Gelegenheit, sie anders zu machen
 * @param auswechselIndex    Index des Auswechselspielers in {@link #aufstellung} oder
 *                           {@code null} bei gerader Teilnehmerzahl. <b>{@code null} und kein
 *                           Platzhalter</b> - die Antwort sagt das genauso
 * @param verwendeterModus   die Regel, nach der er bestimmt wurde. Kann von der Konfiguration
 *                           abweichen (8.4); bei gerader Zahl ist es der eingestellte Wert,
 *                           denn es war nichts zu entscheiden
 * @param seed               der Seed dieses Laufs. Er wird am Termin gespeichert und steht im
 *                           manuellen Lauf ausschliesslich im Audit-Eintrag (9.4)
 */
public record Teamergebnis(Aufstellung aufstellung,
                           Teamaufteilung aufteilung,
                           List<Long> staerken,
                           Integer auswechselIndex,
                           AuswechselModus verwendeterModus,
                           long seed) {

    /** Die Staerken unveraenderlich - sie sind Teil des Snapshots. */
    public Teamergebnis {
        staerken = List.copyOf(staerken);
    }

    /** Gewichtete Gesamtstaerke des Teilnehmers an dieser Stelle der Aufstellung. */
    public long staerke(int index) {
        return staerken.get(index);
    }

    /**
     * Die Kosten der Zielfunktion in der Darstellung, in der sie die Grenze verlassen:
     * {@code team_generierung.differenz_teamstaerke} und
     * {@code ManuelleEinteilung.differenzTeamstaerke}, beide {@code NUMERIC(6,2)}.
     *
     * <h2>Warum die Umrechnung hier steht</h2>
     * Gerechnet wird ganzzahlig in Hundertsteln - sonst bestimmte das letzte Bit eines
     * {@code double}, welche Aufteilungen in der Menge der Optima liegen, aus der der Seed
     * waehlt ({@code S5_ALGORITHMUS.md}, 3.1). <b>Die Zahl und ihre Darstellung gehoeren
     * damit zusammen</b>; stuende die Umrechnung beim Schreiben und noch einmal im DTO,
     * liefen sie frueher oder spaeter auseinander. {@code BigDecimal.valueOf(wert, 2)} ist
     * verlustfrei - die Skala ist genau die der Spalte.
     */
    public BigDecimal kostenAlsDezimal() {
        return alsDezimal(aufteilung.kosten());
    }

    /** Die Staerke eines Teilnehmers als {@code score_snapshot} der Zuteilung (7.2). */
    public BigDecimal staerkeAlsDezimal(int index) {
        return alsDezimal(staerke(index));
    }

    /** Hundertstel als {@code NUMERIC(6,2)}. */
    private static BigDecimal alsDezimal(long hundertstel) {
        return BigDecimal.valueOf(hundertstel, 2);
    }

    /** Der Verfahrensname, unter dem das Ergebnis tatsaechlich entstanden ist. */
    public AlgorithmType verwendetesVerfahren() {
        return aufteilung.verwendetesVerfahren();
    }

    /** Anzeigename des Auswechselspielers oder {@code null} bei gerader Teilnehmerzahl. */
    public String auswechselName() {
        return auswechselIndex == null ? null : spieler(auswechselIndex).anzeigeName();
    }

    /** {@code true}, wenn dieser Index den Auswechselspieler bezeichnet. */
    public boolean istAuswechselspieler(int index) {
        return auswechselIndex != null && auswechselIndex == index;
    }

    /** Der Teilnehmer an dieser Stelle der Aufstellung. */
    public Aufstellungsspieler spieler(int index) {
        return aufstellung.spieler().get(index);
    }
}
