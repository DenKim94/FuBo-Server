package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.BisherigeAufteilung;
import de.fubo.appserver.domain.team.Teamaufteilung;

/**
 * Ein austauschbares Verfahren der Teameinteilung ({@code S5_ALGORITHMUS.md}, 5.3).
 *
 * <h2>Die einzige Schnittstelle zum Rest von S5</h2>
 * Die Aufstellung kommt aus {@code AufstellungService} (Abschnitt 2), das Ergebnis geht in den
 * Generierungslauf (Abschnitt 7). <b>Wer hier eine Termin-Id, eine Sitzung oder ein Repository
 * braucht, hat den Schnitt verlassen</b> - und damit die Eigenschaft aufgegeben, die A24
 * billig macht: Beide Eingangstueren fuehren zu demselben Verfahren.
 *
 * <h2>Gewaehlt wird ueber eine Karte, nie ueber ein {@code switch}</h2>
 * {@link TeamverfahrenAuswahl} baut sie aus den Beans. Ein drittes Verfahren kaeme dann ohne
 * Aenderung am aufrufenden Dienst dazu.
 */
public interface Teamverfahren {

    /**
     * Teilt die Aufstellung in zwei Teams.
     *
     * <h2>Gewaehlt wird aus einem Band, nicht das Optimum</h2>
     * Die Teams sollen bei jedem Lauf neu durchmischt werden und trotzdem ausgeglichen sein
     * (Aenderung vom 22.10.2026). Das Verfahren bestimmt deshalb das Optimum und waehlt per Seed
     * <b>zufaellig unter allen Aufteilungen, die hoechstens
     * {@link Zielfunktion#durchmischungsband()} schlechter sind</b>. Gibt es neben der
     * {@code bisherige} keine weitere im Band, weicht es auf die beste Alternative aus -
     * "neu durchmischt" geht vor "noch ein Hundertstel besser".
     *
     * <p><b>Derselbe Seed liefert dasselbe Ergebnis</b>, auf jeder JVM und bei gleicher
     * {@code bisherige}: Beide Verfahren verbrauchen ihn ueber {@code java.util.Random}, dessen
     * Algorithmus in der Javadoc spezifiziert ist. {@code RandomGenerator.getDefault()} darf
     * sich zwischen Java-Versionen aendern - dann liesse sich ein gespeicherter Lauf nicht mehr
     * nachrechnen. <b>Die bisherige Aufteilung gehoert zur Eingabe</b>: Sie steht in der
     * Historie der Datenbank (abgeloeste Laeufe bleiben erhalten).
     *
     * @param aufstellung wer eingeteilt wird und nach welchen Kategorien
     * @param seed        je Lauf neu gezogen (mit {@code SecureRandom}, damit er nicht
     *                    vorhersagbar ist) und gespeichert
     * @param bisherige   die Aufteilung, die nicht wiederholt werden soll, oder {@code null}
     *                    (erster Lauf, anderer Teilnehmerkreis, manueller Lauf). Bei zwei
     *                    Teilnehmern gibt es nur eine Aufteilung; sie wird dann trotzdem
     *                    geliefert
     * @return die Einteilung samt Kosten und tatsaechlich verwendetem Verfahren
     */
    Teamaufteilung berechne(Aufstellung aufstellung, long seed, BisherigeAufteilung bisherige);

    /** Wie {@link #berechne(Aufstellung, long, BisherigeAufteilung)}, ohne eine Aufteilung zu meiden. */
    default Teamaufteilung berechne(Aufstellung aufstellung, long seed) {
        return berechne(aufstellung, seed, null);
    }

    /** Unter welchem Namen dieses Verfahren in {@code configs.app_config} steht. */
    AlgorithmType typ();
}
