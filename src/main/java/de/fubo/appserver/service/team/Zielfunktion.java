package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Aufstellung;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Die Zielfunktion beider Teamverfahren ({@code S5_ALGORITHMUS.md}, Abschnitt 3; seit dem
 * 03.11.2026 lexikografisch).
 *
 * <pre>
 * abweichung_kat = gewicht_kat * |Summe_A(kat) - Summe_B(kat)|
 * engpass        = MAX_kat   abweichung_kat        (zuerst)
 * kosten         = SUMME_kat abweichung_kat        (dann)
 * </pre>
 *
 * Tertiaer, nur in {@code HEURISTIK} beim Merken des Bestwerts:
 * {@code |Gesamtstaerke_A - Gesamtstaerke_B|}, ebenfalls gewichtet summiert.
 *
 * <h2>Warum der Engpass vor der Summe steht (03.11.2026)</h2>
 * Die Anforderung lautet, jede Kategorie moeglichst auszugleichen. Die Summe allein laesst
 * zu, dass eine Kategorie die ganze Abweichung traegt: Drei Punkte im Angriff kosten dort
 * dasselbe wie je ein Punkt in drei Kategorien. Gemessen an den 30 realen Profilen lag bei
 * zehn Spielern in 10 % der gezogenen Einteilungen eine Kategorie um drei Punkte
 * auseinander; mit dem Engpass zuerst in keiner. Die Summe bleibt das zweite Kriterium - ohne
 * sie waeren alle Aufteilungen mit gleichem Engpass gleich gut, auch eine, die in allen
 * uebrigen Kategorien schlechter ist. Verworfen: quadrierte Abweichungen (bestraft
 * Ungleichgewichte nur staerker, schliesst sie nicht aus, und das Band verloere seine
 * Einheit "Skillpunkt").
 *
 * <h2>Eine Zahl fuer zwei Kriterien</h2>
 * {@link #bewertung} fasst beide in einen {@code long}: {@code engpass * faktor + kosten}, mit
 * einem {@code faktor}, der groesser ist als jede moegliche Summe plus Band. <b>Damit ist der
 * Vergleich zweier Bewertungen exakt der lexikografische Vergleich</b>, und beide Verfahren
 * behalten ihre Mechanik (Optimum, Band, Reservoir, Kandidatenliste) unveraendert - nur die
 * Zahl, die sie vergleichen, ist eine andere. Gespeichert wird sie nicht: Sie ist bei 24
 * Spielern bis knapp 10^9 gross und hat keine anschauliche Bedeutung.
 * {@code team_generierung.differenz_teamstaerke} traegt weiter {@link #kosten}.
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

    /**
     * Breite des Durchmischungsbands in Punkten der am hoechsten gewichteten Kategorie
     * ({@link #durchmischungsband()}). <b>Der einzige Regler der Durchmischung:</b> Gemessen mit
     * zufaelligen Aufstellungen (8 bis 12 Spieler, Skills 1 bis 6) liegen bei {@code 1} meist
     * nur ein bis zwei Aufteilungen im Band; in zehn Laeufen erscheinen im Mittel 2,5 bis 2,9
     * verschiedene Einteilungen. {@code 3} ergaebe 3,7 bis 5,9, bei Mehrkosten von im Mittel
     * 1,3 bis 1,6 Punkten gegenueber dem Optimum (Messung in {@code CONTEXT_HANDOFF_SERVER.md}).
     */
    private static final int BAND_PUNKTE = 1;

    /**
     * Multiplikator des Engpasses in {@link #bewertung}: groesser als jede moegliche Summe
     * zuzueglich Band.
     *
     * <p>Warum genau diese Grenze: Zwei Aufteilungen mit verschiedenem Engpass liegen damit
     * mindestens {@code faktor} auseinander, und das ist mehr als jede Summe und mehr als das
     * Band. Kein Band kann deshalb eine Aufteilung mit schlechterem Engpass einschliessen, und
     * keine noch so kleine Summe macht einen schlechteren Engpass wett. Die groesste Summe ist
     * {@code SUMME_kat gewicht_kat * gesamtsumme_kat} - dann steht ein Team bei null.
     */
    private final long faktor;

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

        long hoechsteSumme = 0;
        for (int k = 0; k < anzahlKategorien; k++) {
            hoechsteSumme += (long) gewichte[k] * gesamtsumme[k];
        }
        this.faktor = hoechsteSumme + durchmischungsband() + 1;
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
     * Die Summe der gewichteten Kategorieabweichungen einer Aufteilung, in Hundertsteln -
     * das <b>zweite</b> Kriterium nach {@link #engpass}. {@code 0} heisst perfekt
     * ausgeglichen, groesser heisst schlechter.
     *
     * <p><b>Annehmen eines Tauschs in {@code HEURISTIK} richtet sich nach diesem Wert</b>, nicht
     * nach {@link #bewertung}: Deren Spruenge um {@code faktor} liessen die Temperatur nie
     * wirken, und die Suche fror ein (gemessen 03.11.2026: 3 von 60 Laeufen verfehlten das
     * Optimum, mit der Summe 0 von 105).
     *
     * <p>{@code Summe_B = gesamtsumme - Summe_A}, die Differenz also
     * {@code |2 * Summe_A - gesamtsumme|}. Das spart den zweiten Puffer und ist dieselbe Zahl.
     *
     * <p><b>Dieser Wert wird gespeichert</b> ({@code team_generierung.differenz_teamstaerke}) -
     * nicht der Tie-Break, den der Spaltenname nahelegt. Nach aussen geht er seit dem
     * 05.11.2026 nicht mehr; der manuelle Lauf zeigt stattdessen die Punktsummen je Team.
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
     * Die groesste gewichtete Abweichung einer einzelnen Kategorie, in Hundertsteln - das
     * <b>erste</b> Kriterium.
     *
     * <p>Gewichtet wie die Summe: Ein Punkt Torwart zaehlt {@code 30}, ein Punkt Angriff
     * {@code 100}. Der Torwart wird damit erst ab vier Punkten Differenz zum Engpass - bei
     * einem Wertebereich von 0 bis 3 genau dann, wenn mehrere Torhueter auf einer Seite stehen.
     *
     * @param summeA Kategoriesummen des Teams A
     */
    public long engpass(long[] summeA) {
        long engpass = 0;
        for (int k = 0; k < anzahlKategorien; k++) {
            engpass = Math.max(engpass,
                    (long) gewichte[k] * Math.abs(2 * summeA[k] - gesamtsumme[k]));
        }
        return engpass;
    }

    /**
     * Engpass und Summe in einer Zahl: <b>kleiner heisst besser, und der Vergleich zweier
     * Werte ist der lexikografische Vergleich von (Engpass, Summe)</b>.
     *
     * <p>Diese Zahl vergleichen beide Verfahren bei der Auswahl, und gegen sie wird das Band
     * gelegt: {@code bewertung <= optimum + durchmischungsband()} heisst <i>gleicher Engpass
     * wie das Optimum und eine Summe hoechstens ein Band darueber</i> - siehe {@link #faktor}.
     *
     * @param summeA Kategoriesummen des Teams A
     */
    public long bewertung(long[] summeA) {
        return engpass(summeA) * faktor + kosten(summeA);
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
     * Wie viel schlechter als das Optimum eine Aufteilung sein darf und noch "ausgeglichen"
     * heisst, in Hundertsteln: <b>ein Punkt Unterschied in der am hoechsten gewichteten
     * Kategorie</b> (bei den Vorgaben {@code 100}).
     *
     * <p>Warum aus den Gewichten abgeleitet und keine Zahl im Code: Die Gewichte stehen in
     * {@code skill_kategorie.gewicht} und sind administrierbar; eine feste {@code 100} waere
     * eine zweite Wahrheit, die bei einer Gewichtsaenderung stillschweigend etwas anderes
     * meinte. Die Kosten sind ganzzahlige Vielfache der Gewichte - ein Punkt in einer
     * Feldkategorie ist der kleinste Schritt, den eine ungerade Summe ueberhaupt zulaesst; das
     * Band erlaubt also etwa <i>eine</i> Kategorie, die um einen Punkt schlechter ausgeglichen
     * ist als im Optimum, nicht mehr.
     *
     * <p><b>Seit dem 03.11.2026 wirkt es auf die Summe, nicht auf den Engpass</b>: Gelegt wird
     * es an {@link #bewertung}, und dort trennt {@link #faktor} verschiedene Engpaesse weiter
     * als jedes Band. Im Band liegt also nur, was in keiner Kategorie weiter auseinanderliegt
     * als die schlechteste Kategorie des Optimums.
     *
     * <p>Beide Verfahren waehlen aus diesem Band; siehe {@link Teamverfahren}.
     */
    public long durchmischungsband() {
        long hoechstes = 0;
        for (int gewicht : gewichte) {
            hoechstes = Math.max(hoechstes, gewicht);
        }
        return BAND_PUNKTE * hoechstes;
    }

    /**
     * Die gewichtete Gesamtstaerke eines einzelnen Spielers, in Hundertsteln.
     *
     * <p>Gebraucht an drei Stellen: als Sortierschluessel des Snake-Drafts (5.1), als
     * {@code score_snapshot} der Zuteilung (7.2) und fuer das Startteam des Springers (seit
     * 03.11.2026: er beginnt beim schwaecheren Team).
     * <b>Dieselbe Rechnung an drei Stellen heisst dieselbe Methode</b> - sonst waere
     * "schwaecher" beim Draft etwas anderes als bei der Wahl des Startteams.
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

}
