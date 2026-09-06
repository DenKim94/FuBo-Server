package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Aufstellung;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Die Zielfunktion beider Teamverfahren ({@code S5_ALGORITHMUS.md}, Abschnitt 3).
 *
 * <pre>
 * cost = SUMME_kat  gewicht_kat * |Summe_A(kat) - Summe_B(kat)|
 * </pre>
 *
 * Sekundaer, nur bei Gleichstand: {@code |Gesamtstaerke_A - Gesamtstaerke_B|}, ebenfalls
 * gewichtet summiert.
 *
 * <h2>Eine Klasse, nicht zwei Kopien</h2>
 * {@code EXHAUSTIV} und {@code HEURISTIK} rechnen ueber dieselbe Instanz. <b>Nur so traegt der
 * Vergleichstest</b> aus {@code S5_UMSETZUNG.md}, 12.2: Bei kleiner Spielerzahl muss
 * {@code HEURISTIK} dasselbe Optimum finden wie {@code EXHAUSTIV}. Laeuft die Kostenfunktion
 * doppelt im Code, prueft er nichts - er vergliche dann zwei Implementierungen desselben
 * Fehlers.
 *
 * <h2>Ganzzahlig, in Hundertsteln</h2>
 * Die Gewichte sind {@code NUMERIC(4,2)}; {@code 0.30} ist in {@code double} nicht exakt
 * darstellbar. Zwei mathematisch gleich teure Aufteilungen unterschieden sich dann im letzten
 * Bit - und eine fiele aus der Menge der Optima, aus der der Seed waehlt. Multipliziert mit
 * 100 sind die Gewichte {@code int}, die Skillwerte sind es ohnehin, und Vergleiche werden
 * exakt. Die Alternative - eine Toleranz {@code 1e-9} - verteilte eine Zahlenkonstante ueber
 * den Code.
 *
 * <h2>Optimiert wird auf Summen, nicht auf Durchschnitte</h2>
 * Bei ungerader Spielerzahl bekommt das kleinere Team dadurch tendenziell die staerkeren
 * Spieler - genau das verlangt A20a. Mit Durchschnitten waere der Ausgleich rechnerisch
 * perfekt und praktisch falsch: Elf Spieler mit Schnitt 4 schlagen zehn Spieler mit Schnitt 4.
 *
 * <h2>Die flache Matrix</h2>
 * {@code EXHAUSTIV} fasst diese Struktur bis zu 705.432-mal an; Objekte und Karten sind dort
 * zu teuer. Die Werte liegen deshalb als {@code int[spieler][kategorie]}, die Gesamtsumme je
 * Kategorie einmal vorberechnet: Fuer eine Aufteilung genuegt {@code summeA},
 * {@code summeB = gesamtsumme - summeA}. Das spart die Haelfte der Arbeit.
 *
 * <p>Die Klasse ist <b>zustandslos nach dem Aufbau</b> und damit ohne Weiteres mehrfach
 * verwendbar; die Puffer der Innenschleife gibt {@link #neuerPuffer()} heraus, sie gehoeren
 * dem Aufrufer.
 */
public final class Zielfunktion {

    /** {@code werte[spielerIndex][kategorieIndex]}, einmal aufgebaut. */
    private final int[][] werte;

    /** Gewicht je Kategorie in Hundertsteln, parallel zur Kategorieachse. */
    private final int[] gewichte;

    /** Summe je Kategorie ueber alle Spieler, einmal berechnet. */
    private final long[] gesamtsumme;

    private final int anzahlSpieler;
    private final int anzahlKategorien;

    /**
     * Baut die Matrix aus der Aufstellung auf.
     *
     * <p><b>Eine fehlende Kategorie ist hier ein Programmierfehler, kein Fachfall.</b> Die
     * Vollstaendigkeit prueft der {@code AufstellungService} und beantwortet sie mit
     * {@code 409 SKILLWERTE_UNVOLLSTAENDIG} samt Namen. Wer daran vorbeikommt, bekommt eine
     * {@link IllegalStateException} - und ausdruecklich keine stillschweigende {@code 0}: Die
     * saehe in der Einteilung aus wie ein gepflegter Wert, die Teams waeren falsch
     * ausbalanciert, und niemand koennte nachsehen, warum.
     *
     * @param aufstellung Teilnehmer und aktive Kategorien
     */
    public Zielfunktion(Aufstellung aufstellung) {
        List<Aufstellungsspieler> spieler = aufstellung.spieler();
        List<SkillKategorie> kategorien = aufstellung.kategorien();

        this.anzahlSpieler = spieler.size();
        this.anzahlKategorien = kategorien.size();
        this.werte = new int[anzahlSpieler][anzahlKategorien];
        this.gewichte = new int[anzahlKategorien];
        this.gesamtsumme = new long[anzahlKategorien];

        for (int k = 0; k < anzahlKategorien; k++) {
            gewichte[k] = gewichtAlsHundertstel(kategorien.get(k).gewicht());
        }

        for (int s = 0; s < anzahlSpieler; s++) {
            Aufstellungsspieler eintrag = spieler.get(s);
            for (int k = 0; k < anzahlKategorien; k++) {
                String schluessel = kategorien.get(k).schluessel();
                Integer wert = eintrag.werte().get(schluessel);
                if (wert == null) {
                    throw new IllegalStateException(
                            "Der Aufstellung fehlt der Skillwert zur Kategorie " + schluessel
                                    + " - die Vollstaendigkeit gehoert in den AufstellungService.");
                }
                werte[s][k] = wert;
                gesamtsumme[k] += wert;
            }
        }
    }

    /**
     * Rechnet ein Gewicht in Hundertstel um.
     *
     * <p>{@code NUMERIC(4,2)} hat genau zwei Nachkommastellen, der Faktor 100 ist deshalb
     * verlustfrei und keine Naeherung. {@code intValueExact} bricht ab, sobald doch mehr
     * Stellen auftauchen - <b>das soll auffallen</b> und nicht stillschweigend gerundet
     * werden, denn eine gerundete Gewichtung veraendert die Menge der Optima.
     */
    private static int gewichtAlsHundertstel(BigDecimal gewicht) {
        return gewicht.movePointRight(2).intValueExact();
    }

    // ------------------------------------------------------------------ Auskunft

    /** Zahl der Teilnehmer. */
    public int anzahlSpieler() {
        return anzahlSpieler;
    }

    /** Zahl der aktiven Kategorien - die Laenge jedes Summenpuffers. */
    public int anzahlKategorien() {
        return anzahlKategorien;
    }

    /**
     * Ein frischer Summenpuffer.
     *
     * <p>Die Innenschleife bekommt ihren Puffer <b>einmal</b> und schreibt ihn immer wieder
     * neu; ein neues {@code long[]} je Aufteilung waeren bei {@code EXHAUSTIV} 705.432
     * Kurzlebige - vermeidbar, ohne dass der Code darunter leidet.
     */
    public long[] neuerPuffer() {
        return new long[anzahlKategorien];
    }

    // ------------------------------------------------------------------ Rechnen

    /**
     * Schreibt die Kategoriesummen der genannten Spieler in {@code ziel}.
     *
     * @param indizes Spielerindizes des Teams A
     * @param anzahl  wie viele Eintraege von {@code indizes} gelten - erlaubt einen
     *                wiederverwendeten Puffer statt eines passgenauen Arrays
     * @param ziel    Puffer aus {@link #neuerPuffer()}; wird ueberschrieben
     */
    public void summiere(int[] indizes, int anzahl, long[] ziel) {
        Arrays.fill(ziel, 0L);
        for (int i = 0; i < anzahl; i++) {
            int[] zeile = werte[indizes[i]];
            for (int k = 0; k < anzahlKategorien; k++) {
                ziel[k] += zeile[k];
            }
        }
    }

    /**
     * Verschiebt eine Kategoriesumme um einen Spieler.
     *
     * <p><b>Der Kern der inkrementellen Rechnung in {@code HEURISTIK}:</b> Bei einem
     * Paar-Tausch aendern sich je Kategorie nur zwei Summanden. Neu ueber alle Spieler zu
     * summieren machte die Innenschleife um den Faktor {@code n} langsamer - der haeufigste
     * Grund, warum eine Heuristik langsamer laeuft als die vollstaendige Aufzaehlung.
     *
     * @param summe        Puffer, der veraendert wird
     * @param spielerIndex betroffener Spieler
     * @param vorzeichen   {@code +1} hinzufuegen, {@code -1} entfernen
     */
    public void verschiebe(long[] summe, int spielerIndex, int vorzeichen) {
        int[] zeile = werte[spielerIndex];
        for (int k = 0; k < anzahlKategorien; k++) {
            summe[k] += (long) vorzeichen * zeile[k];
        }
    }

    /**
     * Die Primaerkosten einer Aufteilung, in Hundertsteln. {@code 0} heisst perfekt
     * ausgeglichen, groesser heisst schlechter.
     *
     * <p>{@code Summe_B = gesamtsumme - Summe_A}, die Differenz also
     * {@code |2 * Summe_A - gesamtsumme|}. Das spart den zweiten Puffer und ist dieselbe Zahl.
     *
     * <p><b>Dieser Wert wird gespeichert und nach aussen gegeben</b>
     * ({@code team_generierung.differenz_teamstaerke}, {@code ManuelleEinteilung
     * .differenzTeamstaerke}) - nicht der Tie-Break, den der Spaltenname nahelegt.
     *
     * @param summeA Kategoriesummen des Teams A
     */
    public long kosten(long[] summeA) {
        long kosten = 0;
        for (int k = 0; k < anzahlKategorien; k++) {
            kosten += (long) gewichte[k] * Math.abs(2 * summeA[k] - gesamtsumme[k]);
        }
        return kosten;
    }

    /**
     * Der Tie-Break: {@code |Gesamtstaerke_A - Gesamtstaerke_B|}, gewichtet summiert, in
     * Hundertsteln.
     *
     * <p><b>Er lebt nur innerhalb eines Laufs und wird nirgends festgehalten.</b> Er kann
     * {@code 0} sein, waehrend die Teams kategorieweise weit auseinanderliegen - A vier Punkte
     * staerker im Angriff, B vier Punkte staerker in der Verteidigung. Gespeichert waere er
     * ausgerechnet die Zahl, die den Fehler verdeckt.
     *
     * @param summeA Kategoriesummen des Teams A
     */
    public long tieBreak(long[] summeA) {
        long differenz = 0;
        for (int k = 0; k < anzahlKategorien; k++) {
            differenz += (long) gewichte[k] * (2 * summeA[k] - gesamtsumme[k]);
        }
        return Math.abs(differenz);
    }

    /**
     * Die gewichtete Gesamtstaerke eines einzelnen Spielers, in Hundertsteln.
     *
     * <p>Gebraucht an drei Stellen: als Sortierschluessel des Snake-Drafts (5.1), als
     * {@code score_snapshot} der Zuteilung (7.2) und als Kriterium des Auswechselspielers im
     * Modus {@code SCHWAECHSTER_UEBERZAHL} (8.1). <b>Dieselbe Rechnung an drei Stellen heisst
     * dieselbe Methode</b> - sonst waere "schwaechster Spieler" beim Draft etwas anderes als
     * auf der Bank.
     *
     * @param spielerIndex Position in der Aufstellung
     */
    public long staerke(int spielerIndex) {
        long staerke = 0;
        int[] zeile = werte[spielerIndex];
        for (int k = 0; k < anzahlKategorien; k++) {
            staerke += (long) gewichte[k] * zeile[k];
        }
        return staerke;
    }

    /**
     * Rechnet Hundertstel in den gespeicherten {@code NUMERIC(6,2)}-Wert zurueck.
     *
     * <p>Die Umrechnung steht hier und nicht beim Schreiben, damit die Definition von
     * "Kosten" und ihre Darstellung nicht auseinanderlaufen koennen.
     */
    public static BigDecimal alsDezimal(long hundertstel) {
        return BigDecimal.valueOf(hundertstel, 2);
    }
}
