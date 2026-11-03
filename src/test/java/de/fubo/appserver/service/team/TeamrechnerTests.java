package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.profil.SkillKategorie;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.BisherigeAufteilung;
import de.fubo.appserver.domain.team.Teamergebnis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft den Springer (A20b in der Fassung vom 03.11.2026): Wahl, Startteam, Teamgroessen und
 * das Meiden der bisherigen Aufteilung.
 *
 * <p><b>Ohne Spring-Kontext und ohne Datenbank</b>, wie {@code TeamverfahrenTests}: Der
 * {@link Teamrechner} ist reine Rechnung und wird hier von Hand zusammengesetzt. Damit sind es
 * acht kontextfreie Klassen; sie sind zusammen die Gegenprobe bei einem Kontextfehler
 * ({@code CONTEXT_HANDOFF_SERVER.md}, 6.3).
 *
 * <p><b>Die Erwartungen sind vorab nachgerechnet</b> - mit einem Python-Nachbau von
 * Zielfunktion, beiden Verfahren, Springerwahl und {@code java.util.Random}
 * ({@code harness/tmp/springer/modell.py}). Wo eine Erwartung an einem Seed haengt, ist sie
 * ueber viele Seeds geprueft, nicht ueber einen.
 */
class TeamrechnerTests {

    private static final BigDecimal FELD = new BigDecimal("1.00");

    private static final BigDecimal TORWART = new BigDecimal("0.30");

    /** Fester Bezugspunkt der Meldezeiten; nur die Reihenfolge zaehlt. */
    private static final OffsetDateTime BASIS = OffsetDateTime.of(2026, 11, 3, 18, 0, 0, 0, ZoneOffset.UTC);

    private final HeuristikVerfahren heuristik = new HeuristikVerfahren();
    private final ExhaustivVerfahren exhaustiv = new ExhaustivVerfahren(heuristik);
    private final Teamrechner rechner = new Teamrechner(
            new TeamverfahrenAuswahl(List.of(exhaustiv, heuristik)), new SpringerErmittlung());

    // ------------------------------------------------------------------ Gerade Zahl

    /** Bei gerader Zahl gibt es keinen Springer, und der eingestellte Modus wird genannt. */
    @Test
    void beiGeraderZahlGibtEsKeinenSpringer() {
        Teamergebnis ergebnis = rechner.rechne(aufstellung(zehnWerte(), null),
                AlgorithmType.EXHAUSTIV, AuswechselModus.ZULETZT_ANGEMELDET, 5L);

        assertThat(ergebnis.springerIndex()).isNull();
        assertThat(ergebnis.springerName()).isNull();
        assertThat(ergebnis.verwendeterModus()).isEqualTo(AuswechselModus.ZULETZT_ANGEMELDET);
        assertThat(ergebnis.aufteilung().teamA()).hasSize(5);
        assertThat(ergebnis.aufteilung().teamB()).hasSize(5);
    }

    // ------------------------------------------------------------------ Ungerade Zahl

    /**
     * Ungerade Zahl: Der Springer steht im groesseren Team, ohne ihn sind beide gleich gross,
     * und er beginnt beim schwaecheren - ueber hundert Seeds.
     *
     * <p>"Schwaecher" ist die gewichtete Gesamtstaerke ohne den Springer, dieselbe Zahl, die als
     * {@code score_snapshot} gespeichert wird.
     */
    @Test
    void derSpringerBeginntBeimSchwaecherenTeam() {
        Aufstellung aufstellung = aufstellung(elfWerte(), null);

        for (long seed = 0; seed < 100; seed++) {
            Teamergebnis ergebnis = rechner.rechne(aufstellung,
                    AlgorithmType.EXHAUSTIV, AuswechselModus.ZUFAELLIG, seed);
            int springer = ergebnis.springerIndex();
            List<Integer> startteam = ergebnis.aufteilung().teamA().contains(springer)
                    ? ergebnis.aufteilung().teamA() : ergebnis.aufteilung().teamB();
            List<Integer> gegner = startteam == ergebnis.aufteilung().teamA()
                    ? ergebnis.aufteilung().teamB() : ergebnis.aufteilung().teamA();

            assertThat(startteam).as("Seed %d", seed).hasSize(6);
            assertThat(gegner).as("Seed %d", seed).hasSize(5).doesNotContain(springer);
            assertThat(staerke(ergebnis, startteam) - ergebnis.staerke(springer))
                    .as("Seed %d: ohne den Springer nicht staerker", seed)
                    .isLessThanOrEqualTo(staerke(ergebnis, gegner));
        }
    }

    /**
     * Die Kosten sind die der Aufteilung <b>ohne</b> Springer - er spielt fuer beide Seiten und
     * gehoert in keine Balance.
     */
    @Test
    void dieKostenRechnenOhneDenSpringer() {
        Aufstellung aufstellung = aufstellung(elfWerte(), null);

        for (long seed = 0; seed < 20; seed++) {
            Teamergebnis ergebnis = rechner.rechne(aufstellung,
                    AlgorithmType.EXHAUSTIV, AuswechselModus.ZUFAELLIG, seed);
            int springer = ergebnis.springerIndex();
            Aufstellung grund = aufstellung.ohne(springer);
            List<Integer> teamAOhne = ergebnis.aufteilung().teamA().stream()
                    .filter(position -> position != springer)
                    .map(position -> position < springer ? position : position - 1)
                    .toList();

            Zielfunktion zielfunktion = new Zielfunktion(grund);
            long[] puffer = zielfunktion.neuerPuffer();
            int[] indizes = teamAOhne.stream().mapToInt(Integer::intValue).toArray();
            zielfunktion.summiere(indizes, indizes.length, puffer);

            assertThat(ergebnis.aufteilung().kosten()).as("Seed %d", seed)
                    .isEqualTo(zielfunktion.kosten(puffer));
            assertThat(zielfunktion.bewertung(puffer)).as("Seed %d: im Band", seed)
                    .isLessThanOrEqualTo(exhaustiv.optimum(grund) + zielfunktion.durchmischungsband());
        }
    }

    /** {@code ZUFAELLIG} trifft jeden Teilnehmer - auch den staerksten und den schwaechsten. */
    @Test
    void zufaelligKannJederSpringerSein() {
        Aufstellung aufstellung = aufstellung(elfWerte(), null);
        Set<Integer> gesehen = new HashSet<>();

        for (long seed = 0; seed < 200; seed++) {
            Teamergebnis ergebnis = rechner.rechne(aufstellung,
                    AlgorithmType.EXHAUSTIV, AuswechselModus.ZUFAELLIG, seed);
            assertThat(ergebnis.verwendeterModus()).isEqualTo(AuswechselModus.ZUFAELLIG);
            gesehen.add(ergebnis.springerIndex());
        }

        assertThat(gesehen).hasSize(11);
    }

    /** {@code ZULETZT_ANGEMELDET}: Wer zuletzt zugesagt hat, ist Springer - bei jedem Seed. */
    @Test
    void zuletztAngemeldetIstSpringer() {
        Aufstellung aufstellung = aufstellung(elfWerte(), aufsteigend(11));   // Position 10 zuletzt

        for (long seed = 0; seed < 30; seed++) {
            Teamergebnis ergebnis = rechner.rechne(aufstellung,
                    AlgorithmType.EXHAUSTIV, AuswechselModus.ZULETZT_ANGEMELDET, seed);
            assertThat(ergebnis.springerIndex()).isEqualTo(10);
            assertThat(ergebnis.springerName()).isEqualTo("Pruefspieler 11");
            assertThat(ergebnis.verwendeterModus()).isEqualTo(AuswechselModus.ZULETZT_ANGEMELDET);
        }
    }

    /**
     * Gleichstand bei der spaetesten Meldung: Der Seed entscheidet, und nur unter den beiden.
     *
     * <p>Sonst stuende Woche fuer Woche derselbe als Springer da, nur weil er in der Liste
     * weiter vorn steht.
     */
    @Test
    void beiGleicherMeldezeitEntscheidetDerSeed() {
        List<OffsetDateTime> meldezeiten = new ArrayList<>(aufsteigend(11));
        meldezeiten.set(4, BASIS.plusMinutes(20));
        meldezeiten.set(9, BASIS.plusMinutes(20));
        Aufstellung aufstellung = aufstellung(elfWerte(), meldezeiten);

        Set<Integer> gesehen = new HashSet<>();
        for (long seed = 0; seed < 60; seed++) {
            gesehen.add(rechner.rechne(aufstellung,
                    AlgorithmType.EXHAUSTIV, AuswechselModus.ZULETZT_ANGEMELDET, seed).springerIndex());
        }

        assertThat(gesehen).containsExactlyInAnyOrder(4, 9);
    }

    /**
     * Ohne Meldezeiten (der manuelle Lauf nach A24) faellt {@code ZULETZT_ANGEMELDET} auf
     * {@code ZUFAELLIG} zurueck - und das Ergebnis sagt es.
     */
    @Test
    void ohneMeldezeitFaelltDieWahlAufZufaelligZurueck() {
        Teamergebnis ergebnis = rechner.rechne(aufstellung(elfWerte(), null),
                AlgorithmType.EXHAUSTIV, AuswechselModus.ZULETZT_ANGEMELDET, 11L);

        assertThat(ergebnis.springerIndex()).isNotNull();
        assertThat(ergebnis.verwendeterModus()).isEqualTo(AuswechselModus.ZUFAELLIG);
    }

    /** Derselbe Seed liefert denselben Springer und dieselben Teams - in beiden Verfahren. */
    @Test
    void gleicherSeedLiefertGleichesErgebnis() {
        Aufstellung aufstellung = aufstellung(elfWerte(), null);

        for (AlgorithmType verfahren : AlgorithmType.values()) {
            Teamergebnis erstes = rechner.rechne(aufstellung, verfahren, AuswechselModus.ZUFAELLIG, 4711L);
            Teamergebnis zweites = rechner.rechne(aufstellung, verfahren, AuswechselModus.ZUFAELLIG, 4711L);

            assertThat(zweites.springerIndex()).isEqualTo(erstes.springerIndex());
            assertThat(zweites.aufteilung()).isEqualTo(erstes.aufteilung());
        }
    }

    // ------------------------------------------------------------------ Bisherige Aufteilung

    /**
     * Mit demselben Springer wird die bisherige Aufteilung der uebrigen gemieden - mit einem
     * anderen (oder unbekannten) Springer nicht.
     *
     * <p>Der Aufbau macht den Unterschied sichtbar: Position 10 meldet sich zuletzt und ist
     * immer Springer, die uebrigen sind {@code zehnSpieler} aus {@code TeamverfahrenTests}. Deren
     * Band enthaelt genau eine Aufteilung (Summe 390); die beste Alternative hat 510. Ohne
     * Ausschluss kommt also stets 390 heraus, mit Ausschluss stets 510.
     */
    @Test
    void dieBisherigeWirdNurMitDemselbenSpringerGemieden() {
        Aufstellung aufstellung = aufstellung(elfWerte(), aufsteigend(11));
        Teamergebnis erstes = rechner.rechne(aufstellung,
                AlgorithmType.EXHAUSTIV, AuswechselModus.ZULETZT_ANGEMELDET, 7L);
        assertThat(erstes.aufteilung().kosten()).isEqualTo(390L);

        BisherigeAufteilung mitSpringer = new BisherigeAufteilung(
                erstes.aufteilung().teamA(), erstes.aufteilung().teamB(), 10);
        BisherigeAufteilung ohneSpringer = new BisherigeAufteilung(
                erstes.aufteilung().teamA(), erstes.aufteilung().teamB());

        for (long seed = 0; seed < 20; seed++) {
            Teamergebnis gemieden = rechner.rechne(aufstellung,
                    AlgorithmType.EXHAUSTIV, AuswechselModus.ZULETZT_ANGEMELDET, seed, mitSpringer);
            Teamergebnis frei = rechner.rechne(aufstellung,
                    AlgorithmType.EXHAUSTIV, AuswechselModus.ZULETZT_ANGEMELDET, seed, ohneSpringer);

            assertThat(gemieden.aufteilung().kosten()).as("Seed %d", seed).isEqualTo(510L);
            assertThat(gruppenOhne(gemieden, 10)).isNotEqualTo(gruppenOhne(erstes, 10));
            assertThat(frei.aufteilung().kosten()).as("Seed %d", seed).isEqualTo(390L);
            assertThat(gruppenOhne(frei, 10)).isEqualTo(gruppenOhne(erstes, 10));
        }
    }

    // ------------------------------------------------------------------ Hilfsmittel

    private static long staerke(Teamergebnis ergebnis, List<Integer> team) {
        long summe = 0;
        for (int position : team) {
            summe += ergebnis.staerke(position);
        }
        return summe;
    }

    /** Die beiden Teams ohne den Springer, als Gruppen ohne Beschriftung. */
    private static Set<Set<Integer>> gruppenOhne(Teamergebnis ergebnis, int springer) {
        Set<Integer> a = new HashSet<>(ergebnis.aufteilung().teamA());
        Set<Integer> b = new HashSet<>(ergebnis.aufteilung().teamB());
        a.remove(springer);
        b.remove(springer);
        return Set.of(a, b);
    }

    /** Meldezeiten in Listenreihenfolge, eine Minute Abstand - die letzte Position zuletzt. */
    private static List<OffsetDateTime> aufsteigend(int anzahl) {
        List<OffsetDateTime> zeiten = new ArrayList<>(anzahl);
        for (int i = 0; i < anzahl; i++) {
            zeiten.add(BASIS.plusMinutes(i));
        }
        return zeiten;
    }

    /**
     * Baut eine Aufstellung mit allen fuenf Kategorien.
     *
     * @param meldezeiten je Position oder {@code null} fuer "keine Meldezeit" (manueller Lauf)
     */
    private static Aufstellung aufstellung(int[][] zeilen, List<OffsetDateTime> meldezeiten) {
        List<SkillKategorie> kategorien = List.of(
                kategorie("ANGRIFF", FELD, 1),
                kategorie("VERTEIDIGUNG", FELD, 2),
                kategorie("SPIELSTAERKE", FELD, 3),
                kategorie("LAUFSTAERKE", FELD, 4),
                new SkillKategorie("TORWART", "TORWART", TORWART, 5, 0, 3));

        List<Aufstellungsspieler> spieler = new ArrayList<>(zeilen.length);
        for (int i = 0; i < zeilen.length; i++) {
            Map<String, Integer> werte = new LinkedHashMap<>();
            for (int k = 0; k < kategorien.size(); k++) {
                werte.put(kategorien.get(k).schluessel(), zeilen[i][k]);
            }
            OffsetDateTime gemeldet = meldezeiten == null ? null : meldezeiten.get(i);
            spieler.add(new Aufstellungsspieler(
                    null, (long) (i + 1), "Pruefspieler " + (i + 1), false, gemeldet, werte));
        }
        return new Aufstellung(spieler, kategorien);
    }

    private static SkillKategorie kategorie(String schluessel, BigDecimal gewicht, int reihenfolge) {
        return new SkillKategorie(schluessel, schluessel, gewicht, reihenfolge, 0, 6);
    }

    /** Dieselben zehn Spieler wie {@code TeamverfahrenTests#zehnSpieler}. */
    private static int[][] zehnWerte() {
        return new int[][]{
                {5, 3, 4, 4, 0},
                {2, 6, 3, 5, 1},
                {4, 4, 5, 2, 3},
                {6, 1, 2, 6, 0},
                {3, 5, 6, 1, 2},
                {1, 2, 1, 3, 3},
                {5, 5, 3, 4, 1},
                {2, 3, 4, 2, 0},
                {6, 2, 5, 5, 2},
                {3, 4, 2, 6, 3}};
    }

    /** {@link #zehnWerte()} und ein elfter Spieler, wie {@code TeamverfahrenTests#elfSpieler}. */
    private static int[][] elfWerte() {
        int[][] zehn = zehnWerte();
        int[][] elf = new int[11][];
        System.arraycopy(zehn, 0, elf, 0, 10);
        elf[10] = new int[]{4, 4, 4, 4, 1};
        return elf;
    }
}
