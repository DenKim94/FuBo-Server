package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.BisherigeAufteilung;
import de.fubo.appserver.domain.team.Teamaufteilung;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Das exakte Verfahren: vollstaendige Enumeration aller Aufteilungen
 * ({@code S5_ALGORITHMUS.md}, Abschnitt 4).
 *
 * <p>Aufgezaehlt werden alle Teilmengen der Groesse {@code n / 2}; die uebrigen bilden das
 * andere Team. <b>Die Groessendifferenz ist damit bauartbedingt hoechstens 1</b> - A20a
 * braucht keine zusaetzliche Pruefung. Das Ergebnis ist das globale Optimum der Zielfunktion.
 *
 * <h2>Drei Abweichungen von {@code Algorithmus.py}</h2>
 * <ol>
 *   <li><b>Der Dopplungsfilter laeuft ueber Index 0 und nur bei gerader Spielerzahl</b>, nicht
 *       ueber einen lexikographischen Namensvergleich. Das ist eine <i>Fehlerkorrektur</i>:
 *       Bei ungerader Zahl entstehen gar keine Dopplungen, der Namensvergleich verwirft dort
 *       aber rund die Haelfte aller gueltigen Aufteilungen - das Referenzverfahren kann das
 *       Optimum verfehlen.</li>
 *   <li><b>Der Torwart geht mit {@code 0.30} in die Primaerkosten ein</b>, nicht erst in den
 *       Tie-Break. So steht es in {@code AGENT.md}; die Referenz ist der Sonderfall
 *       {@code gewicht = 0}.</li>
 *   <li><b>Es wird nichts gesammelt und sortiert.</b> 705.432 Tupel im Speicher
 *       sind unnoetig; die Auswahl unter den Aufteilungen im Band (seit dem 22.10.2026, vorher
 *       unter den gleich guten) leistet das Reservoir-Sampling.</li>
 * </ol>
 * <b>Nicht abgewichen wird bei der Groessenwahl {@code n / 2}</b> - die Referenz nimmt
 * ebenfalls die kleinere Haelfte, und daraus folgt A20a.
 */
@Component
public class ExhaustivVerfahren implements Teamverfahren {

    private static final Logger LOG = LoggerFactory.getLogger(ExhaustivVerfahren.class);

    /**
     * Groesste Teilnehmerzahl, die dieses Verfahren noch selbst rechnet.
     *
     * <p>{@code C(24,12)} sind gut 2,7 Millionen Aufteilungen - noch im Sekundenbereich, aber
     * die letzte vertretbare Stufe; darueber waechst der Aufwand je zwei Teilnehmer um rund
     * das Vierfache. <b>24 ist damit erlaubt, 25 nicht</b> - die Anleitung laesst sich in
     * beide Richtungen lesen ("ab dieser Zahl weigert sich EXHAUSTIV" gegen "die letzte
     * vertretbare Stufe"); massgeblich ist die Begruendung, und die nennt {@code C(24,12)}
     * ausdruecklich als noch tragbar.
     *
     * <p><b>Die Grenze gehoert in den Code und nicht in die Konfiguration:</b>
     * {@code max_teilnehmer} ist administrierbar, und wer ihn auf 30 setzt, bekaeme
     * {@code C(30,15)} - rund 155 Millionen Aufteilungen und einen Serverstillstand. Im
     * manuellen Lauf des Admins (A24) ist sie zusaetzlich der einzige Schutz vor Dauerlaeufen,
     * weil dort kein Kontingent zaehlt.
     */
    static final int MAX_EXHAUSTIV = 24;

    private final HeuristikVerfahren heuristik;

    /**
     * <b>Warum dieses Verfahren das andere kennt:</b> Der Rueckfall ist die Regel von
     * {@code EXHAUSTIV} selbst - es weiss als Einziges, wann es nicht mehr kann. Ihn in den
     * aufrufenden Dienst zu legen hiesse, die Grenze dort ein zweites Mal zu fuehren, und
     * jeder kuenftige Aufrufer muesste sie kennen.
     */
    public ExhaustivVerfahren(HeuristikVerfahren heuristik) {
        this.heuristik = heuristik;
    }

    @Override
    public AlgorithmType typ() {
        return AlgorithmType.EXHAUSTIV;
    }

    /**
     * Waehlt eine Aufteilung aus dem Band um das Optimum - oder weicht aus, wenn die
     * Aufstellung zu gross ist.
     *
     * <h2>Der Rueckfall scheitert nicht, er weicht aus</h2>
     * Wer auf "Teams generieren" drueckt, hat {@code max_teilnehmer} nicht gesetzt und kann
     * ihn nicht aendern; eine Fehlermeldung waere fuer ihn eine Sackgasse. Der Lauf rechnet
     * deshalb mit {@code HEURISTIK} weiter und <b>protokolliert das</b>. Nach aussen sichtbar
     * wird es ueber {@code Teamaufteilung#verwendetesVerfahren} - stillschweigend abzuweichen
     * waere das Schlimmste von beidem.
     *
     * <h2>Warum nicht mehr nur unter den Optima gewaehlt wird (22.10.2026)</h2>
     * Bis dahin waehlte der Seed unter den <i>exakt gleich guten</i> Aufteilungen. Bei
     * ganzzahligen Skills und wenigen Spielern gibt es oft nur eine - jeder Lauf lieferte dieselben
     * Teams, nur mit vertauschten Namen. Der Seed waehlt jetzt unter allen Aufteilungen mit
     * {@code kosten <= optimum + band} ({@link Zielfunktion#durchmischungsband()}), und die
     * <b>aktuelle Einteilung des Termins ist ausgeschlossen</b>. Hat das Band nur diese eine
     * Aufteilung, gilt die beste Alternative: "neu durchmischt" geht vor "ein Punkt besser".
     *
     * <h2>Zwei Durchlaeufe statt eines</h2>
     * Das Band kennt man erst, wenn das Optimum feststeht. Alle Kandidaten zu merken und zu
     * sortieren verbietet sich - bei lauter gleich starken Spielern liegen alle 2,7 Millionen
     * Aufteilungen im Band. Der erste Durchlauf liefert deshalb nur zwei Zahlen (Optimum und
     * beste Alternative zur bisherigen), der zweite waehlt per <b>Reservoir-Sampling der Groesse
     * 1</b>: Die {@code k}-te Kandidatin wird mit Wahrscheinlichkeit {@code 1/k} uebernommen, am
     * Ende ist die Wahl gleichverteilt ueber das Band - in konstantem Speicher. Der Aufwand
     * verdoppelt sich, bleibt aber im Sekundenbereich ({@link #MAX_EXHAUSTIV}).
     *
     * <p>Der Zufallsgenerator ist {@code java.util.Random} und ausdruecklich weder
     * {@code SecureRandom} noch {@code RandomGenerator.getDefault()}: Sein Algorithmus ist in
     * der Javadoc spezifiziert, derselbe Seed liefert auf jeder JVM dieselbe Folge.
     * {@code getDefault()} darf sich zwischen Java-Versionen aendern - dann liesse sich ein
     * gespeicherter Lauf nicht mehr nachrechnen. Kryptographisch muss hier nichts sein; der
     * Seed ist kein Geheimnis. <b>Gezogen</b> wird er dagegen mit {@code SecureRandom} (6.5),
     * damit sich keine Aufteilung durch wiederholtes Generieren ansteuern laesst.
     *
     * <p>Der Tie-Break der Zielfunktion ({@code |Gesamtstaerke_A - Gesamtstaerke_B|}) entscheidet
     * hier nicht mehr: Innerhalb des Bands ist jede Aufteilung gleich willkommen.
     */
    @Override
    public Teamaufteilung berechne(Aufstellung aufstellung, long seed,
                                   BisherigeAufteilung bisherige) {
        int n = aufstellung.groesse();
        if (n > MAX_EXHAUSTIV) {
            LOG.info("Teamgenerierung: {} Teilnehmer liegen ueber der Grenze von {} fuer "
                            + "EXHAUSTIV; der Lauf weicht auf HEURISTIK aus.",
                    n, MAX_EXHAUSTIV);
            return heuristik.berechne(aufstellung, seed, bisherige);
        }

        Zielfunktion zielfunktion = new Zielfunktion(aufstellung);
        Random zufall = new Random(seed);

        int k = aufstellung.kleineTeamgroesse();

        // Bei gerader Spielerzahl entsteht jede Aufteilung zweimal, als {X} und als
        // {Komplement}. Spieler 0 liegt deshalb immer in der aufgezaehlten Teilmenge; von den
        // beiden spiegelbildlichen Aufzaehlungen ueberlebt genau eine - unabhaengig von Namen,
        // Ids und Sortierung. Bei ungerader Zahl gibt es keine Spiegelung, dort bleibt die
        // Position frei; wer dort trotzdem filterte, verwuerfe gueltige Aufteilungen.
        int fest = aufstellung.ungerade() ? 0 : 1;

        int[] bisherigeTeilmenge = teilmengeVon(bisherige, n, k, fest);
        Eckwerte eckwerte = eckwerte(zielfunktion, n, k, fest, bisherigeTeilmenge);

        // Es gibt eine Alternative, wenn irgendeine Aufteilung ausser der bisherigen existiert -
        // bei zwei Teilnehmern gibt es keine, dort bleibt die Wiederholung unvermeidlich.
        boolean ausschliessen = bisherigeTeilmenge != null
                && eckwerte.besteAlternative() != Long.MAX_VALUE;
        int[] verboten = ausschliessen ? bisherigeTeilmenge : null;

        long obergrenze = eckwerte.optimum() + zielfunktion.durchmischungsband();
        if (ausschliessen) {
            // Liegt im Band nur die bisherige, gilt die beste Alternative - auch ausserhalb.
            obergrenze = Math.max(obergrenze, eckwerte.besteAlternative());
        }

        Auswahl auswahl = new Auswahl(zufall);
        long grenze = obergrenze;
        aufzaehlen(zielfunktion, n, k, fest, (kombination, kosten) -> {
            if (kosten <= grenze && !gleich(kombination, verboten)) {
                auswahl.biete(kombination, kosten);
            }
        });

        if (auswahl.teilmenge == null) {
            throw new IllegalStateException(
                    "Das Band enthielt keine Aufteilung - das Optimum gehoert immer dazu.");
        }
        return zusammenstellen(auswahl.teilmenge, n, zufall, auswahl.kosten);
    }

    /**
     * Das Optimum der Zielfunktion ueber alle Aufteilungen - ohne Band, ohne Seed.
     *
     * <p>Paketsichtbar, weil die Tests daran messen, ob das Ergebnis im Band liegt und ob
     * {@code HEURISTIK} das Optimum findet. Der Wert ist die Wahrheit, gegen die
     * {@link #berechne} nicht selbst gemessen werden kann.
     */
    long optimum(Aufstellung aufstellung) {
        int n = aufstellung.groesse();
        if (n > MAX_EXHAUSTIV) {
            throw new IllegalArgumentException(
                    "Das Optimum zaehlt nur bis " + MAX_EXHAUSTIV + " Teilnehmer, hier " + n + ".");
        }
        return eckwerte(new Zielfunktion(aufstellung), n, aufstellung.kleineTeamgroesse(),
                aufstellung.ungerade() ? 0 : 1, null).optimum();
    }

    /**
     * Der Rueckgabewert des ersten Durchlaufs.
     *
     * @param optimum         kleinste Kosten ueber alle Aufteilungen
     * @param besteAlternative kleinste Kosten ueber alle ausser der bisherigen;
     *                        {@code Long.MAX_VALUE}, wenn es keine andere gibt
     */
    private record Eckwerte(long optimum, long besteAlternative) {
    }

    private static Eckwerte eckwerte(Zielfunktion zielfunktion, int n, int k, int fest,
                                     int[] bisherige) {
        long[] optimum = {Long.MAX_VALUE};
        long[] alternative = {Long.MAX_VALUE};
        aufzaehlen(zielfunktion, n, k, fest, (kombination, kosten) -> {
            if (kosten < optimum[0]) {
                optimum[0] = kosten;
            }
            // Der Vergleich mit der bisherigen kostet O(k); er laeuft nur, wenn die Aufteilung
            // die beste Alternative ueberhaupt verbessern wuerde.
            if (kosten < alternative[0] && !gleich(kombination, bisherige)) {
                alternative[0] = kosten;
            }
        });
        return new Eckwerte(optimum[0], alternative[0]);
    }

    /** Wird fuer jede aufgezaehlte Aufteilung einmal gerufen; {@code kombination} nicht behalten. */
    @FunctionalInterface
    private interface Besucher {
        void besuche(int[] kombination, long kosten);
    }

    /**
     * Zaehlt alle Teilmengen der Groesse {@code k} in lexikographischer Ordnung auf.
     *
     * <p>Position {@code i} ist ausgereizt, wenn sie ihren groesstmoeglichen Wert
     * {@code n - k + i} traegt; die Positionen unterhalb von {@code fest} bleiben
     * unangetastet. Eine Methode fuer beide Durchlaeufe - zwei Kopien der Schleife liessen die
     * Durchlaeufe auseinanderlaufen, und das Band passte dann nicht zum Optimum.
     */
    private static void aufzaehlen(Zielfunktion zielfunktion, int n, int k, int fest,
                                   Besucher besucher) {
        int[] kombination = new int[k];
        for (int i = 0; i < k; i++) {
            kombination[i] = i;
        }
        long[] puffer = zielfunktion.neuerPuffer();

        while (true) {
            zielfunktion.summiere(kombination, k, puffer);
            besucher.besuche(kombination, zielfunktion.kosten(puffer));

            int i = k - 1;
            while (i >= fest && kombination[i] == n - k + i) {
                i--;
            }
            if (i < fest) {
                break;
            }
            kombination[i]++;
            for (int j = i + 1; j < k; j++) {
                kombination[j] = kombination[j - 1] + 1;
            }
        }
    }

    /**
     * Rechnet die bisherige Aufteilung in die aufgezaehlte Teilmenge um.
     *
     * <p>Aufgezaehlt wird bei gerader Zahl die Haelfte, die Spieler 0 enthaelt, bei ungerader
     * die kleinere. Nur so trifft der Vergleich die bisherige Aufteilung - gleich, wie sie
     * beschriftet war. Passen die Groessen nicht zur Aufstellung, ist die Angabe fuer diese
     * Aufstellung wertlos; {@code null} heisst dann: nichts meiden.
     */
    private static int[] teilmengeVon(BisherigeAufteilung bisherige, int n, int k, int fest) {
        if (bisherige == null || bisherige.teamA().size() + bisherige.teamB().size() != n) {
            return null;
        }
        List<Integer> teilmenge;
        if (fest == 1) {
            if (bisherige.teamA().size() != k) {
                return null;
            }
            teilmenge = bisherige.teamA().contains(0) ? bisherige.teamA() : bisherige.teamB();
        } else {
            teilmenge = bisherige.teamA().size() == k ? bisherige.teamA() : bisherige.teamB();
            if (teilmenge.size() != k) {
                return null;
            }
        }
        return teilmenge.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    private static boolean gleich(int[] kombination, int[] verboten) {
        return verboten != null && Arrays.equals(kombination, verboten);
    }

    /** Das Reservoir der Groesse 1 des zweiten Durchlaufs. */
    private static final class Auswahl {

        private final Random zufall;
        private int anzahl;
        private int[] teilmenge;
        private long kosten;

        Auswahl(Random zufall) {
            this.zufall = zufall;
        }

        void biete(int[] kombination, long kosten) {
            anzahl++;
            if (zufall.nextInt(anzahl) == 0) {
                this.teilmenge = kombination.clone();
                this.kosten = kosten;
            }
        }
    }

    /**
     * Macht aus der gewaehlten Teilmenge zwei Teams.
     *
     * <p><b>Der Seed vergibt A und B</b> - sonst stuende bei ungerader Zahl immer dieselbe
     * Haelfte in Ueberzahl und damit Woche fuer Woche derselbe Spieler auf der Bank. Auf die
     * Kosten wirkt der Tausch nicht: Sie sind ein Betrag und damit symmetrisch.
     */
    private static Teamaufteilung zusammenstellen(int[] teilmenge, int n, Random zufall,
                                                  long kosten) {
        boolean[] gewaehlt = new boolean[n];
        List<Integer> erste = new ArrayList<>(teilmenge.length);
        for (int index : teilmenge) {
            gewaehlt[index] = true;
            erste.add(index);
        }
        List<Integer> zweite = new ArrayList<>(n - teilmenge.length);
        for (int i = 0; i < n; i++) {
            if (!gewaehlt[i]) {
                zweite.add(i);
            }
        }

        boolean tauschen = zufall.nextBoolean();
        return new Teamaufteilung(
                tauschen ? zweite : erste,
                tauschen ? erste : zweite,
                kosten,
                AlgorithmType.EXHAUSTIV);
    }
}
