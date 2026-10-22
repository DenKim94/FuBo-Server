package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.BisherigeAufteilung;
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
        long optimum = exhaustiv.optimum(aufstellung);

        for (long seed = 1; seed <= 20; seed++) {
            // Gemessen wird die Suche (suchoptimum), nicht die Auswahl: berechne() liefert seit
            // dem 22.10.2026 eine Aufteilung aus dem Band, und deren Kosten sagen nichts mehr
            // darueber, ob die Suche das Optimum gefunden hat.
            assertThat(heuristik.suchoptimum(aufstellung, seed)).isEqualTo(optimum);

            Teamaufteilung genaehert = heuristik.berechne(aufstellung, seed);
            assertThat(genaehert.verwendetesVerfahren()).isEqualTo(AlgorithmType.HEURISTIK);
            assertThat(genaehert.kosten()).isBetween(optimum,
                    optimum + new Zielfunktion(aufstellung).durchmischungsband());
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

    // ------------------------------------------------------------------ Durchmischung (22.10.2026)

    /**
     * Bei einem <b>eindeutigen</b> Optimum liefert ohne bisherige Aufteilung jeder Seed das
     * Optimum - und mit ihr die beste Alternative, nie dieselbe Einteilung.
     *
     * <p>Das ist der Fall hinter dem Fehlerbericht: Vier Spieler mit {@code 6, 1, 4, 3} haben
     * genau eine Aufteilung mit Kosten 0 ({@code {6,1}} gegen {@code {4,3}}); die anderen kosten
     * 400 und 600 und liegen ausserhalb des Bands von 100. Vorher kam bei jedem Lauf dieselbe
     * Einteilung zurueck. Jetzt gilt die beste Alternative ({@code {6,3}} gegen {@code {1,4}},
     * Kosten 400). <b>Beschriftung ist gleichgueltig:</b> Die bisherige Aufteilung wird einmal
     * als {@code A={0,1}} und einmal vertauscht angegeben.
     */
    @Test
    void einEindeutigesOptimumWirdBeiBisherigerAufteilungVerlassen() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)),
                new int[]{6}, new int[]{1}, new int[]{4}, new int[]{3});
        BisherigeAufteilung optimum = new BisherigeAufteilung(List.of(0, 1), List.of(2, 3));
        BisherigeAufteilung vertauscht = new BisherigeAufteilung(List.of(2, 3), List.of(0, 1));

        Random seedQuelle = new Random(20261022L);
        for (int lauf = 0; lauf < 20; lauf++) {
            long seed = seedQuelle.nextLong();

            assertThat(exhaustiv.berechne(aufstellung, seed).kosten())
                    .as("ohne bisherige: das Optimum").isZero();

            for (BisherigeAufteilung bisherige : List.of(optimum, vertauscht)) {
                Teamaufteilung ergebnis = exhaustiv.berechne(aufstellung, seed, bisherige);

                assertThat(ergebnis.kosten()).as("beste Alternative").isEqualTo(400L);
                assertThat(gruppen(ergebnis)).isEqualTo(Set.of(Set.of(0, 3), Set.of(1, 2)));
            }
        }
    }

    /**
     * Mehrere Aufteilungen im Band: Der Seed streut ueber alle, aber nie auf die bisherige.
     *
     * <p>Werte 5, 4, 3, 2, 1 in einer Kategorie. Die Summe ist 15 (ungerade), die kleinste
     * Differenz 1 - also Kosten 100 - und sie erreichen drei Aufteilungen des kleinen Teams:
     * {@code {0,2}}, {@code {0,3}} und {@code {1,2}}. Alle anderen kosten mindestens 300. Mit
     * {@code {0,2}} als bisherige bleiben zwei uebrig, und beide muessen vorkommen.
     */
    @Test
    void ausDemBandWirdGestreutUndDieBisherigeAusgelassen() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)),
                new int[]{5}, new int[]{4}, new int[]{3}, new int[]{2}, new int[]{1});
        BisherigeAufteilung bisherige = new BisherigeAufteilung(List.of(0, 2), List.of(1, 3, 4));

        Random seedQuelle = new Random(20261022L);
        Set<Set<Integer>> ohneBisherige = new HashSet<>();
        Set<Set<Integer>> mitBisherige = new HashSet<>();
        for (int lauf = 0; lauf < 80; lauf++) {
            long seed = seedQuelle.nextLong();

            Teamaufteilung frei = exhaustiv.berechne(aufstellung, seed);
            assertThat(frei.kosten()).isEqualTo(100L);
            ohneBisherige.add(kleinesTeam(frei));

            Teamaufteilung neu = exhaustiv.berechne(aufstellung, seed, bisherige);
            assertThat(neu.kosten()).isEqualTo(100L);
            mitBisherige.add(kleinesTeam(neu));
        }

        assertThat(ohneBisherige).containsExactlyInAnyOrder(Set.of(0, 2), Set.of(0, 3), Set.of(1, 2));
        assertThat(mitBisherige).containsExactlyInAnyOrder(Set.of(0, 3), Set.of(1, 2));
    }

    /**
     * Jede Wahl liegt im Band, und eine Folge von Laeufen wiederholt nie unmittelbar.
     *
     * <p>Elf Spieler (ungerade): Das Optimum kostet 500, zwei weitere Aufteilungen 560 - sonst
     * liegt nichts im Band bis 600. Zwanzig Laeufe hintereinander, jeder mit dem vorigen als
     * bisherige: Die Kosten bleiben in {@code [500, 600]}, und zwei aufeinanderfolgende Laeufe
     * sind nie gleich. Gemessen am echten Optimum aus {@link ExhaustivVerfahren#optimum} und
     * nicht an einer Zahl im Test.
     */
    @Test
    void jedeWahlLiegtImBandUndWiederholtDenVorgaengerNicht() {
        Aufstellung aufstellung = elfSpieler();
        long optimum = exhaustiv.optimum(aufstellung);
        long band = new Zielfunktion(aufstellung).durchmischungsband();

        Random seedQuelle = new Random(20261022L);
        Teamaufteilung vorige = null;
        Set<Set<Integer>> gesehen = new HashSet<>();
        for (int lauf = 0; lauf < 20; lauf++) {
            BisherigeAufteilung bisherige = vorige == null ? null
                    : new BisherigeAufteilung(vorige.teamA(), vorige.teamB());
            Teamaufteilung ergebnis = exhaustiv.berechne(aufstellung, seedQuelle.nextLong(), bisherige);

            assertThat(ergebnis.kosten()).isBetween(optimum, optimum + band);
            if (vorige != null) {
                assertThat(gruppen(ergebnis)).isNotEqualTo(gruppen(vorige));
            }
            gesehen.add(kleinesTeam(ergebnis));
            vorige = ergebnis;
        }

        assertThat(optimum).as("von Hand gerechnet: 11 Spieler, fuenf Kategorien").isEqualTo(500L);
        assertThat(gesehen).as("alle drei Aufteilungen im Band kommen vor").hasSize(3);
    }

    /**
     * Ein eindeutiges Optimum bleibt ohne bisherige Aufteilung das Ergebnis, mit ihr gilt die
     * beste Alternative - an den zehn Spielern des Vergleichstests.
     *
     * <p>Das Optimum kostet 390, die naechstbeste Aufteilung 510 (von Hand durchgezaehlt, siehe
     * {@code CONTEXT_HANDOFF_SERVER.md}). Das Band von 100 reicht nur bis 490.
     */
    @Test
    void beiZehnSpielernKommtNachDemOptimumDieBesteAlternative() {
        Aufstellung aufstellung = zehnSpieler();
        Teamaufteilung optimal = exhaustiv.berechne(aufstellung, 1L);
        assertThat(optimal.kosten()).isEqualTo(390L);

        BisherigeAufteilung bisherige = new BisherigeAufteilung(optimal.teamA(), optimal.teamB());
        for (long seed = 1; seed <= 20; seed++) {
            Teamaufteilung neu = exhaustiv.berechne(aufstellung, seed, bisherige);

            assertThat(neu.kosten()).isEqualTo(510L);
            assertThat(gruppen(neu)).isNotEqualTo(gruppen(optimal));
        }
    }

    /**
     * Eine bisherige Aufteilung, die nicht zur Aufstellung passt, wird ignoriert.
     *
     * <p>Der Dienst gibt sie nur bei gleichem Teilnehmerkreis weiter; fuer den Fall, dass eine
     * Groesse trotzdem abweicht, darf das Verfahren nicht scheitern und nichts meiden.
     */
    @Test
    void eineUnpassendeBisherigeAufteilungWirdIgnoriert() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)),
                new int[]{6}, new int[]{1}, new int[]{4}, new int[]{3});
        BisherigeAufteilung zuKlein = new BisherigeAufteilung(List.of(0), List.of(1, 2));

        assertThat(exhaustiv.berechne(aufstellung, 5L, zuKlein).kosten()).isZero();
        assertThat(heuristik.berechne(aufstellung, 5L, zuKlein).verwendetesVerfahren())
                .isEqualTo(AlgorithmType.HEURISTIK);
    }

    /** Bei zwei Teilnehmern gibt es nur eine Aufteilung; sie wird trotz Vermeidung geliefert. */
    @Test
    void beiZweiSpielernBleibtNurDieEineAufteilung() {
        Aufstellung aufstellung = aufstellung(
                List.of(kategorie("ANGRIFF", FELD, 1)), new int[]{3}, new int[]{5});
        BisherigeAufteilung bisherige = new BisherigeAufteilung(List.of(0), List.of(1));

        Teamaufteilung ergebnis = exhaustiv.berechne(aufstellung, 9L, bisherige);

        assertThat(gruppen(ergebnis)).isEqualTo(Set.of(Set.of(0), Set.of(1)));
    }

    /**
     * {@code HEURISTIK}: nie dieselbe Aufteilung wie die bisherige; ohne sie bleibt die Wahl im
     * Band um das beste Ergebnis der Suche.
     *
     * <p>Zehn Spieler mit eindeutigem Optimum - der Fall, in dem das Verfahren vorher immer
     * wieder dieselbe Aufteilung fand. <b>Eine Obergrenze fuer die zweite Wahl gibt es nicht:</b>
     * Liegt im Band nur die bisherige, gilt die beste Alternative, und die kann beliebig weit
     * darueber liegen. Dort zaehlt nur, dass sie nicht das Optimum unterbietet.
     */
    @Test
    void heuristikWiederholtDieBisherigeNichtUndBleibtImBand() {
        Aufstellung aufstellung = zehnSpieler();
        long band = new Zielfunktion(aufstellung).durchmischungsband();
        long optimum = exhaustiv.optimum(aufstellung);

        for (long seed = 1; seed <= 20; seed++) {
            Teamaufteilung erste = heuristik.berechne(aufstellung, seed);
            assertThat(erste.kosten()).isBetween(optimum, heuristik.suchoptimum(aufstellung, seed) + band);

            BisherigeAufteilung bisherige = new BisherigeAufteilung(erste.teamA(), erste.teamB());
            Teamaufteilung zweite = heuristik.berechne(aufstellung, seed + 1000, bisherige);

            assertThat(gruppen(zweite)).isNotEqualTo(gruppen(erste));
            assertThat(zweite.kosten()).isGreaterThanOrEqualTo(optimum);
            assertThat(Math.abs(zweite.teamA().size() - zweite.teamB().size())).isLessThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ Hilfsmittel

    /** Die Einteilung als zwei Gruppen ohne Beschriftung. */
    private static Set<Set<Integer>> gruppen(Teamaufteilung aufteilung) {
        return Set.of(new HashSet<>(aufteilung.teamA()), new HashSet<>(aufteilung.teamB()));
    }

    /** Das kleinere Team einer Aufteilung; bei ungerader Zahl unabhaengig von A und B eindeutig. */
    private static Set<Integer> kleinesTeam(Teamaufteilung aufteilung) {
        return new HashSet<>(aufteilung.teamA().size() < aufteilung.teamB().size()
                ? aufteilung.teamA() : aufteilung.teamB());
    }

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
