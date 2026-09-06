package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.Teamaufteilung;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 *   <li><b>Es wird nur das laufende Optimum gehalten</b>, nicht alles gesammelt und sortiert.
 *       705.432 Tupel im Speicher sind unnoetig; die Auswahl unter gleich guten Aufteilungen
 *       leistet das Reservoir-Sampling.</li>
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
     * Rechnet das globale Optimum aus - oder weicht aus, wenn die Aufstellung zu gross ist.
     *
     * <h2>Der Rueckfall scheitert nicht, er weicht aus</h2>
     * Wer auf "Teams generieren" drueckt, hat {@code max_teilnehmer} nicht gesetzt und kann
     * ihn nicht aendern; eine Fehlermeldung waere fuer ihn eine Sackgasse. Der Lauf rechnet
     * deshalb mit {@code HEURISTIK} weiter und <b>protokolliert das</b>. Nach aussen sichtbar
     * wird es ueber {@code Teamaufteilung#verwendetesVerfahren} - stillschweigend abzuweichen
     * waere das Schlimmste von beidem.
     *
     * <h2>Wie der Seed auswaehlt, ohne alles zu speichern</h2>
     * {@code EXHAUSTIV} ist deterministisch, A15 verlangt aber, dass ein zweiter erlaubter
     * Lauf bei unveraenderten Teilnehmern ein anderes Ergebnis liefern <i>kann</i> - sonst
     * waere das Kontingent sinnlos. Der Seed waehlt deshalb unter den gleich guten
     * Aufteilungen aus, per <b>Reservoir-Sampling der Groesse 1</b>: Die {@code k}-te
     * gefundene Aufteilung wird mit Wahrscheinlichkeit {@code 1/k} uebernommen, am Ende ist
     * die Auswahl gleichverteilt ueber alle Optima - in konstantem Speicher.
     *
     * <p>Der Zufallsgenerator ist {@code java.util.Random} und ausdruecklich weder
     * {@code SecureRandom} noch {@code RandomGenerator.getDefault()}: Sein Algorithmus ist in
     * der Javadoc spezifiziert, derselbe Seed liefert auf jeder JVM dieselbe Folge.
     * {@code getDefault()} darf sich zwischen Java-Versionen aendern - dann liesse sich ein
     * gespeicherter Lauf nicht mehr nachrechnen. Kryptographisch muss hier nichts sein; der
     * Seed ist kein Geheimnis. <b>Gezogen</b> wird er dagegen mit {@code SecureRandom} (6.5),
     * damit sich keine Aufteilung durch wiederholtes Generieren ansteuern laesst.
     */
    @Override
    public Teamaufteilung berechne(Aufstellung aufstellung, long seed) {
        int n = aufstellung.groesse();
        if (n > MAX_EXHAUSTIV) {
            LOG.info("Teamgenerierung: {} Teilnehmer liegen ueber der Grenze von {} fuer "
                            + "EXHAUSTIV; der Lauf weicht auf HEURISTIK aus.",
                    n, MAX_EXHAUSTIV);
            return heuristik.berechne(aufstellung, seed);
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

        int[] kombination = new int[k];
        for (int i = 0; i < k; i++) {
            kombination[i] = i;
        }

        long[] puffer = zielfunktion.neuerPuffer();
        long besteKosten = Long.MAX_VALUE;
        long besteZweitkosten = Long.MAX_VALUE;
        int anzahlOptima = 0;
        int[] beste = null;

        while (true) {
            zielfunktion.summiere(kombination, k, puffer);
            long kosten = zielfunktion.kosten(puffer);
            long zweitkosten = zielfunktion.tieBreak(puffer);

            int vergleich = Long.compare(kosten, besteKosten);
            if (vergleich == 0) {
                vergleich = Long.compare(zweitkosten, besteZweitkosten);
            }

            if (vergleich < 0) {
                besteKosten = kosten;
                besteZweitkosten = zweitkosten;
                anzahlOptima = 1;
                beste = kombination.clone();
            } else if (vergleich == 0) {
                anzahlOptima++;
                if (zufall.nextInt(anzahlOptima) == 0) {
                    beste = kombination.clone();
                }
            }

            // Naechste Kombination in lexikographischer Ordnung. Position i ist ausgereizt,
            // wenn sie ihren groesstmoeglichen Wert n - k + i traegt; die Positionen unterhalb
            // von "fest" bleiben unangetastet.
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

        return zusammenstellen(beste, n, zufall, besteKosten);
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
