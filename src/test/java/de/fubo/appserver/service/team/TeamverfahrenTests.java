package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.Teamaufteilung;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft Zielfunktion und beide Teamverfahren aus {@code S5_ALGORITHMUS.md}, Abschnitte 3 bis 5
 * (Pruefpunkte in {@code S5_UMSETZUNG.md}, 12.2).
 *
 * <p><b>Ohne Spring-Kontext und ohne Datenbank.</b> Zielfunktion und Verfahren sind reine
 * Rechnung; sie kennen weder Termin noch Sitzung noch Repository. Genau das laesst sich hier
 * nachweisen - die Klasse laeuft in Millisekunden und faende einen Algorithmusfehler auch dann,
 * wenn kein Docker liefe. Damit waechst die Zahl der kontextfreien Klassen von drei auf vier;
 * sie sind zusammen die Gegenprobe aus {@code CONTEXT_HANDOFF_SERVER.md}, 6.4: Sind sie gruen
 * und alles andere rot, liegt ein Kontextfehler vor und kein Anwendungsfehler.
 *
 * <p><b>Kein Zeitstreifen noetig</b> (12.1): Es gibt hier keinen Termin, den
 * {@code uq_termin_zeit} treffen koennte.
 */
class TeamverfahrenTests {

    /** Gewicht der vier Feldkategorien. */
    private static final BigDecimal FELD = new BigDecimal("1.00");

    /** Gewicht des Torwarts - der einzige Wert, der nicht 1.00 ist (A12). */
    private static final BigDecimal TORWART = new BigDecimal("0.30");

    private final HeuristikVerfahren heuristik = new HeuristikVerfahren();
    private final ExhaustivVerfahren exhaustiv = new ExhaustivVerfahren(heuristik);

    // ------------------------------------------------------------------ Zielfunktion

    /**
     * Ein von Hand gerechnetes Beispiel, damit die Formel nicht gegen sich selbst geprueft
     * wird.
     *
     * <p>Zwei Feldkategorien, vier Spieler. Team A = {0, 1}: Angriff 6, Verteidigung 6.
     * Gesamtsummen: Angriff 11, Verteidigung 10. Also Angriffsdifferenz
     * {@code |2*6 - 11| = 1} und Verteidigungsdifferenz {@code |2*6 - 10| = 2}, beide mit
     * Gewicht 100 - zusammen 300 Hundertstel.
     */
    @Test
    void zielfunktionRechnetDasHandbeispiel() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1), kategorie("VERTEIDIGUNG", FELD, 2)),
                new int[]{5, 2},
                new int[]{1, 4},
                new int[]{3, 3},
                new int[]{2, 1});

        assertThat(kostenVon(aufstellung, 0, 1)).isEqualTo(300L);
    }

    /**
     * Der Torwart geht mit 0,30 in die <b>Primaerkosten</b> ein, nicht erst in den Tie-Break
     * (Abweichung 2 von {@code Algorithmus.py}).
     *
     * <p>Derselbe Aufbau zweimal, nur die Kategorie wechselt: Ein Punkt Unterschied im Angriff
     * kostet 100 Hundertstel, derselbe Unterschied im Tor 30.
     */
    @Test
    void torwartZaehltMitDreissigProzent() {
        List<SkillKategorie> kategorien =
                List.of(kategorie("ANGRIFF", FELD, 1), kategorie("TORWART", TORWART, 5));

        Aufstellung imAngriff = aufstellung(kategorien,
                new int[]{1, 0}, new int[]{0, 0}, new int[]{0, 0}, new int[]{0, 0});
        Aufstellung imTor = aufstellung(kategorien,
                new int[]{0, 1}, new int[]{0, 0}, new int[]{0, 0}, new int[]{0, 0});

        assertThat(kostenVon(imAngriff, 0, 1)).isEqualTo(100L);
        assertThat(kostenVon(imTor, 0, 1)).isEqualTo(30L);
    }

    // ------------------------------------------------------------------ EXHAUSTIV

    /**
     * Gerade Zahl: zwei Teams zu zweien, und das Optimum ist von Hand nachpruefbar.
     *
     * <p>Werte 6, 1, 4, 3 in einer Kategorie. Nur {@code {6,1}} gegen {@code {4,3}} ergibt
     * gleiche Summen und damit Kosten 0; die beiden anderen Aufteilungen kosten 600 und 400.
     */
    @Test
    void exhaustivFindetDasOptimumBeiVierSpielern() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)),
                new int[]{6}, new int[]{1}, new int[]{4}, new int[]{3});

        Teamaufteilung ergebnis = exhaustiv.berechne(aufstellung, 42L);

        assertThat(ergebnis.verwendetesVerfahren()).isEqualTo(AlgorithmType.EXHAUSTIV);
        assertThat(ergebnis.kosten()).isZero();
        assertThat(ergebnis.teamA()).hasSize(2);
        assertThat(ergebnis.teamB()).hasSize(2);
        // Welche Haelfte A und welche B heisst, entscheidet der Seed - geprueft wird deshalb
        // nur, dass die Einteilung eine der beiden Sichten auf dieselbe Aufteilung ist.
        assertThat(new HashSet<>(ergebnis.teamA())).isIn(Set.of(0, 1), Set.of(2, 3));
    }

    /**
     * Ungerade Zahl: Teams zu 2 und 3 - und das kleinere ist das staerkere (A20a).
     *
     * <p>Das ist die Folge daraus, dass auf <b>Summen</b> optimiert wird und nicht auf
     * Durchschnitte: Sind die Summen nahezu gleich, muss die kleinere Haelfte je Spieler
     * staerker sein. Mit Durchschnitten waere der Ausgleich rechnerisch perfekt und praktisch
     * falsch - elf Spieler mit Schnitt 4 schlagen zehn Spieler mit Schnitt 4.
     */
    @Test
    void beiUngeraderZahlIstDasKleinereTeamDasStaerkere() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)),
                new int[]{5}, new int[]{4}, new int[]{3}, new int[]{2}, new int[]{1});

        Teamaufteilung ergebnis = exhaustiv.berechne(aufstellung, 7L);
        Zielfunktion zielfunktion = new Zielfunktion(aufstellung);

        boolean aIstKlein = ergebnis.teamA().size() < ergebnis.teamB().size();
        List<Integer> klein = aIstKlein ? ergebnis.teamA() : ergebnis.teamB();
        List<Integer> gross = aIstKlein ? ergebnis.teamB() : ergebnis.teamA();

        assertThat(klein).hasSize(2);
        assertThat(gross).hasSize(3);
        assertThat(durchschnitt(zielfunktion, klein)).isGreaterThan(durchschnitt(zielfunktion, gross));
    }

    /**
     * Bei ungerader Zahl wird nichts weggezaehlt - alle {@code C(5,2) = 10} Aufteilungen
     * werden bewertet.
     *
     * <p><b>Der Pruefpunkt zur Fehlerkorrektur gegenueber {@code Algorithmus.py}.</b> Dort
     * verwirft ein lexikographischer Namensvergleich rund die Haelfte aller Aufteilungen -
     * bei gerader Zahl richtig, bei ungerader falsch, weil dort keine Dopplungen entstehen.
     *
     * <p>Gemessen wird ueber fuenf gleich starke Spieler: Dann sind alle zehn Aufteilungen
     * gleich gut, und das Reservoir-Sampling muss jede von ihnen erreichen koennen.
     *
     * <p><b>Die Seeds werden gezogen und nicht hochgezaehlt</b> - aus einer festen Quelle, das
     * Ergebnis bleibt also reproduzierbar. Fortlaufende Seeds waeren hier eine schlechte Wahl:
     * {@code new Random(n)} und {@code new Random(n + 1)} liefern in den ersten Werten
     * aehnliche Folgen, und der Test soll die Streuung des Verfahrens messen, nicht die
     * Startwertaufbereitung von {@code java.util.Random}.
     */
    @Test
    void exhaustivBewertetBeiUngeraderZahlAlleAufteilungen() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)),
                new int[]{3}, new int[]{3}, new int[]{3}, new int[]{3}, new int[]{3});

        Random seedQuelle = new Random(20260905L);
        Set<Set<Integer>> gesehen = new HashSet<>();
        for (int lauf = 0; lauf < 400; lauf++) {
            Teamaufteilung ergebnis = exhaustiv.berechne(aufstellung, seedQuelle.nextLong());
            List<Integer> klein = ergebnis.teamA().size() == 2 ? ergebnis.teamA() : ergebnis.teamB();
            gesehen.add(new HashSet<>(klein));
        }

        assertThat(gesehen).hasSize(10);
    }

    // ------------------------------------------------------------------ Seed

    /** Derselbe Seed liefert dasselbe Ergebnis - bei beiden Verfahren. */
    @Test
    void gleicherSeedLiefertGleichesErgebnis() {
        Aufstellung aufstellung = zehnSpieler();

        assertThat(exhaustiv.berechne(aufstellung, 4711L))
                .isEqualTo(exhaustiv.berechne(aufstellung, 4711L));
        assertThat(heuristik.berechne(aufstellung, 4711L))
                .isEqualTo(heuristik.berechne(aufstellung, 4711L));
    }

    /**
     * Verschiedene Seeds liefern bei mehreren gleich guten Aufteilungen auch verschiedene
     * Ergebnisse.
     *
     * <p><b>Ohne diese Eigenschaft waere das Kontingent aus A15 sinnlos:</b> Ein zweiter
     * erlaubter Lauf muss bei unveraenderten Teilnehmern eine andere Einteilung liefern
     * koennen. {@code EXHAUSTIV} ist deterministisch; die Streuung leistet allein das
     * Reservoir-Sampling ueber den Seed.
     */
    @Test
    void verschiedeneSeedsLiefernVerschiedeneErgebnisse() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)),
                new int[]{3}, new int[]{3}, new int[]{3}, new int[]{3}, new int[]{3});

        Random seedQuelle = new Random(20260905L);
        Set<List<Integer>> gesehen = new HashSet<>();
        for (int lauf = 0; lauf < 20; lauf++) {
            Teamaufteilung ergebnis = exhaustiv.berechne(aufstellung, seedQuelle.nextLong());
            gesehen.add(new ArrayList<>(new TreeSet<>(ergebnis.teamA())));
        }

        assertThat(gesehen).hasSizeGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ HEURISTIK

    /**
     * Der wertvollste Fall der Klasse: {@code HEURISTIK} muss bei kleiner Spielerzahl dieselben
     * Kosten erreichen wie die vollstaendige Aufzaehlung.
     *
     * <p>Er prueft die Heuristik gegen eine Wahrheit, die unabhaengig von ihr entstanden ist -
     * und er traegt nur, weil sich beide Verfahren <b>dieselbe</b> Zielfunktionsklasse teilen.
     * Verglichen werden die Kosten, nicht die Aufteilung: Bei mehreren Optima duerfen die
     * beiden Verfahren verschiedene davon waehlen.
     *
     * <p>Schlaegt er gelegentlich fehl, sind die Iterationen zu knapp - nicht der Test zu
     * streng.
     */
    @Test
    void heuristikFindetDasOptimumBeiZehnSpielern() {
        Aufstellung aufstellung = zehnSpieler();

        for (long seed = 1; seed <= 20; seed++) {
            long optimum = exhaustiv.berechne(aufstellung, seed).kosten();
            Teamaufteilung genaehert = heuristik.berechne(aufstellung, seed);

            assertThat(genaehert.verwendetesVerfahren()).isEqualTo(AlgorithmType.HEURISTIK);
            assertThat(genaehert.kosten()).isEqualTo(optimum);
        }
    }

    /**
     * Die Teamgroessen unterscheiden sich nach {@code HEURISTIK} um hoechstens eins (A20a),
     * ueber 100 Laeufe.
     *
     * <p>Die Eigenschaft entsteht aus dem Aufbau und nicht aus einer Pruefung: Der Snake-Draft
     * verteilt bereits ausgeglichen, und der Paar-Tausch laesst beide Groessen unberuehrt.
     * Eine Verschiebung statt eines Tauschs braeuchte an jeder Stelle eine zusaetzliche
     * Bedingung - der Test wuerde ihr Fehlen sofort finden.
     */
    @Test
    void heuristikHaeltDieTeamgroessen() {
        Aufstellung aufstellung = elfSpieler();

        for (long seed = 0; seed < 100; seed++) {
            Teamaufteilung ergebnis = heuristik.berechne(aufstellung, seed);
            assertThat(ergebnis.teamA().size() + ergebnis.teamB().size()).isEqualTo(11);
            assertThat(Math.abs(ergebnis.teamA().size() - ergebnis.teamB().size()))
                    .isLessThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /** Kosten einer von Hand angegebenen Aufteilung, in Hundertsteln. */
    private static long kostenVon(Aufstellung aufstellung, int... teamA) {
        Zielfunktion zielfunktion = new Zielfunktion(aufstellung);
        long[] puffer = zielfunktion.neuerPuffer();
        zielfunktion.summiere(teamA, teamA.length, puffer);
        return zielfunktion.kosten(puffer);
    }

    /** Gewichtete Durchschnittsstaerke eines Teams, in Hundertsteln. */
    private static double durchschnitt(Zielfunktion zielfunktion, List<Integer> team) {
        long summe = 0;
        for (int index : team) {
            summe += zielfunktion.staerke(index);
        }
        return (double) summe / team.size();
    }

    /** Eine Kategorie mit dem Wertebereich der Feldkategorien. */
    private static SkillKategorie kategorie(String schluessel, BigDecimal gewicht, int reihenfolge) {
        return new SkillKategorie(schluessel, schluessel, gewicht, reihenfolge, 0, 6);
    }

    /**
     * Baut eine Aufstellung aus Wertezeilen; je Zeile ein Spieler, je Spalte eine Kategorie in
     * der Reihenfolge der uebergebenen Liste.
     *
     * <p>Die Namen folgen der Projektkonvention: keine realen Personennamen, auch nicht in
     * Testdaten.
     */
    private static Aufstellung aufstellung(List<SkillKategorie> kategorien, int[]... zeilen) {
        List<Aufstellungsspieler> spieler = new ArrayList<>(zeilen.length);
        for (int i = 0; i < zeilen.length; i++) {
            Map<String, Integer> werte = new LinkedHashMap<>();
            for (int k = 0; k < kategorien.size(); k++) {
                werte.put(kategorien.get(k).schluessel(), zeilen[i][k]);
            }
            spieler.add(new Aufstellungsspieler(
                    null, (long) (i + 1), "Pruefspieler " + (i + 1), false, null, werte));
        }
        return new Aufstellung(spieler, kategorien);
    }

    /** Zehn Spieler ueber alle fuenf Kategorien - klein genug fuer die vollstaendige Aufzaehlung. */
    private static Aufstellung zehnSpieler() {
        return aufstellung(alleKategorien(),
                new int[]{5, 3, 4, 4, 0},
                new int[]{2, 6, 3, 5, 1},
                new int[]{4, 4, 5, 2, 3},
                new int[]{6, 1, 2, 6, 0},
                new int[]{3, 5, 6, 1, 2},
                new int[]{1, 2, 1, 3, 3},
                new int[]{5, 5, 3, 4, 1},
                new int[]{2, 3, 4, 2, 0},
                new int[]{6, 2, 5, 5, 2},
                new int[]{3, 4, 2, 6, 3});
    }

    /** Elf Spieler - ungerade, damit die Groessenpruefung etwas zu pruefen hat. */
    private static Aufstellung elfSpieler() {
        List<Aufstellungsspieler> spieler = new ArrayList<>(zehnSpieler().spieler());
        Map<String, Integer> werte = new LinkedHashMap<>();
        List<SkillKategorie> kategorien = alleKategorien();
        int[] zeile = {4, 4, 4, 4, 1};
        for (int k = 0; k < kategorien.size(); k++) {
            werte.put(kategorien.get(k).schluessel(), zeile[k]);
        }
        spieler.add(new Aufstellungsspieler(null, 11L, "Pruefspieler 11", false, null, werte));
        return new Aufstellung(spieler, kategorien);
    }

    /** Die fuenf Kategorien des Seed-Datenbestands, mit ihren Gewichten. */
    private static List<SkillKategorie> alleKategorien() {
        return List.of(
                kategorie("ANGRIFF", FELD, 1),
                kategorie("VERTEIDIGUNG", FELD, 2),
                kategorie("SPIELSTAERKE", FELD, 3),
                kategorie("LAUFSTAERKE", FELD, 4),
                new SkillKategorie("TORWART", "TORWART", TORWART, 5, 0, 3));
    }
}
