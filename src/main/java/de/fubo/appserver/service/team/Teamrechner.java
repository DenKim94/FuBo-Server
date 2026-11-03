package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.BisherigeAufteilung;
import de.fubo.appserver.domain.team.Springerwahl;
import de.fubo.appserver.domain.team.Teamaufteilung;
import de.fubo.appserver.domain.team.Teamergebnis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Die Rechnung eines Generierungslaufs, von der Aufstellung bis zum Springer
 * (S5 Abschnitte 7.1 Schritte 4 und 5; A20b in der Fassung vom 03.11.2026).
 *
 * <h2>Die eine Stelle, an der beide Eingangstueren zusammenlaufen</h2>
 * Der Termin-Lauf und der manuelle Lauf des Admins (A24) rufen dieselbe Methode auf. <b>Wer
 * hier verzweigt, hat aus einer zweiten Eingangstuer einen zweiten Generator gemacht</b> - und
 * die zweite Fassung waere die, die niemand gegen die erste prueft.
 *
 * <h2>Ungerade Zahl: erst der Springer, dann die Teams</h2>
 * Der Springer wechselt das Team, sobald sein aktuelles Team ein Tor erzielt, und spielt damit
 * fuer beide Seiten. <b>Er gehoert deshalb in keine Balance.</b> Bestimmt wird er zuerst
 * ({@link SpringerErmittlung}); die uebrigen {@code n - 1} - eine gerade Zahl - teilt das
 * Verfahren in zwei gleich grosse Teams. Danach beginnt der Springer beim schwaecheren der
 * beiden (gewichtete Gesamtstaerke), weil die Ueberzahl dort mehr ausgleicht; bei Gleichstand
 * entscheidet der Zufall des Laufs. Damit entfaellt der fruehere Satz aus A20a, das Team in
 * Unterzahl bekomme die staerkeren Spieler: Es gibt kein festes Team in Unterzahl mehr.
 *
 * <h2>Ein eigener Zufallsstrom fuer den Springer</h2>
 * Das Verfahren zieht aus {@code new Random(seed)}. Der Springer zieht aus
 * {@code new Random(springerSeed(seed))} - derselbe Seed fuer beide hiesse zwei Stroeme mit
 * derselben Zahlenfolge, und die erste Wahl des einen haengte an der ersten Wahl des anderen.
 * Beide bleiben aus dem gespeicherten Seed reproduzierbar.
 *
 * <h2>Was dieser Dienst nicht kennt</h2>
 * Termin, Sitzung, Kontingent, Audit-Log und jede Datenbanktabelle ausser den Kategorien, die
 * bereits in der Aufstellung stecken. Alles, was den Spieltag kennt, liegt in
 * {@code service.spieltag}; ab hier weiss niemand mehr, woher die Liste kommt.
 */
@Service
public class Teamrechner {

    /**
     * Multiplikator fuer den Seed der Springerwahl: die Konstante des goldenen Schnitts in 64
     * Bit, wie sie {@code SplittableRandom} zum Streuen verwendet. Ungerade und damit eine
     * Bijektion auf {@code long} - verschiedene Seeds ergeben verschiedene Springer-Seeds.
     */
    private static final long SPRINGER_STREUUNG = 0x9E3779B97F4A7C15L;

    private final TeamverfahrenAuswahl verfahrenAuswahl;
    private final SpringerErmittlung springerErmittlung;

    public Teamrechner(TeamverfahrenAuswahl verfahrenAuswahl,
                       SpringerErmittlung springerErmittlung) {
        this.verfahrenAuswahl = verfahrenAuswahl;
        this.springerErmittlung = springerErmittlung;
    }

    /**
     * Teilt die Aufstellung in zwei Teams und bestimmt bei ungerader Zahl den Springer.
     *
     * <p><b>Das genannte Verfahren ist nicht unbedingt das gerechnete:</b>
     * {@code EXHAUSTIV} weicht oberhalb von {@code MAX_EXHAUSTIV} auf {@code HEURISTIK} aus
     * und protokolliert das ({@code S5_ALGORITHMUS.md}, 4.4). Was wirklich lief, steht im
     * Ergebnis - der Rueckfall liegt bewusst in {@code ExhaustivVerfahren} und nicht hier:
     * Die Grenze ist die Regel des Verfahrens, und jeder kuenftige Aufrufer muesste sie sonst
     * kennen.
     *
     * @param aufstellung wer eingeteilt wird
     * @param verfahren   das eingestellte Verfahren aus {@code configs.app_config}
     * @param modus       der eingestellte Springer-Modus aus {@code configs.app_config}
     * @param seed        der Seed dieses Laufs
     * @return Aufteilung, Springer und die tatsaechlich verwendeten Einstellungen
     */
    public Teamergebnis rechne(Aufstellung aufstellung, AlgorithmType verfahren,
                               AuswechselModus modus, long seed) {
        return rechne(aufstellung, verfahren, modus, seed, null);
    }

    /**
     * Wie {@link #rechne(Aufstellung, AlgorithmType, AuswechselModus, long)}, nur dass der Lauf
     * die {@code bisherige} Aufteilung <b>nicht wiederholt</b> (22.10.2026).
     *
     * <p>Der Termin-Lauf uebergibt die aktuelle Einteilung des Termins, der manuelle Lauf
     * (A24) {@code null}: Dort gibt es nichts, was sich wiederholen koennte, weil nichts
     * gespeichert wird. Der Dienst bleibt derselbe - nur die Eingabe unterscheidet sich.
     *
     * <p><b>Bei ungerader Zahl wird nur gemieden, wenn derselbe Teilnehmer wieder Springer
     * ist</b> (03.11.2026). Dann vergleicht das Verfahren die Aufteilung der uebrigen mit der
     * bisherigen. Mit einem anderen Springer stehen andere Menschen zur Aufteilung, und jede
     * Einteilung ist eine neue - ein Ausschluss waere dort ein Vergleich zwischen zwei
     * verschiedenen Kreisen.
     *
     * @param bisherige die zu meidende Aufteilung in Positionen von {@code aufstellung},
     *                  oder {@code null}
     */
    public Teamergebnis rechne(Aufstellung aufstellung, AlgorithmType verfahren,
                               AuswechselModus modus, long seed, BisherigeAufteilung bisherige) {
        Teamverfahren teamverfahren = verfahrenAuswahl.fuer(verfahren);
        List<Long> staerken = staerken(aufstellung);

        if (!aufstellung.ungerade()) {
            // Bei gerader Zahl ist nichts zu entscheiden. Der eingestellte Modus wird trotzdem
            // durchgereicht: Die Antwort nennt ihn, und "nicht angewandt" ist etwas anderes
            // als "abgewichen".
            Teamaufteilung aufteilung = teamverfahren.berechne(aufstellung, seed, bisherige);
            return new Teamergebnis(aufstellung, aufteilung, staerken, null, modus, seed);
        }

        Random zufall = new Random(springerSeed(seed));
        Springerwahl wahl = springerErmittlung.waehle(aufstellung, modus, zufall);
        int springer = wahl.position();

        Teamaufteilung grund = teamverfahren.berechne(
                aufstellung.ohne(springer), seed, ohneSpringer(bisherige, springer));

        List<Integer> teamA = zurueckbilden(grund.teamA(), springer);
        List<Integer> teamB = zurueckbilden(grund.teamB(), springer);
        long staerkeA = summe(staerken, teamA);
        long staerkeB = summe(staerken, teamB);

        // Der Springer beginnt beim schwaecheren Team. Kurzschlussauswertung ist Absicht: Nur
        // bei Gleichstand wird eine Zufallszahl verbraucht.
        boolean beginntBeiA = staerkeA < staerkeB
                || (staerkeA == staerkeB && !zufall.nextBoolean());
        (beginntBeiA ? teamA : teamB).add(springer);

        Teamaufteilung aufteilung = new Teamaufteilung(
                teamA, teamB, grund.kosten(), grund.verwendetesVerfahren());
        return new Teamergebnis(aufstellung, aufteilung, staerken,
                springer, wahl.verwendeterModus(), seed);
    }

    /**
     * Der Seed der Springerwahl, abgeleitet aus dem Seed des Laufs.
     *
     * <p>Paketsichtbar, damit Tests den Strom nachbilden koennen, ohne die Konstante zu
     * kennen. Multipliziert wird modulo {@code 2^64} - der Ueberlauf ist gewollt.
     */
    static long springerSeed(long seed) {
        return seed * SPRINGER_STREUUNG;
    }

    /**
     * Bildet die bisherige Aufteilung auf die Aufstellung ohne Springer ab - oder {@code null},
     * wenn es nichts zu meiden gibt.
     *
     * <p>Zu meiden gibt es nur etwas, wenn der bisherige Springer derselbe ist. Seine Position
     * faellt aus beiden Teams heraus, die dahinter ruecken um eins vor - genau wie in
     * {@link Aufstellung#ohne(int)}.
     */
    private static BisherigeAufteilung ohneSpringer(BisherigeAufteilung bisherige, int springer) {
        if (bisherige == null || bisherige.springer() == null || bisherige.springer() != springer) {
            return null;
        }
        return new BisherigeAufteilung(
                verschieben(bisherige.teamA(), springer), verschieben(bisherige.teamB(), springer));
    }

    /** Positionen der vollen Aufstellung auf die ohne Springer, der Springer selbst entfaellt. */
    private static List<Integer> verschieben(List<Integer> team, int springer) {
        List<Integer> ergebnis = new ArrayList<>(team.size());
        for (int position : team) {
            if (position != springer) {
                ergebnis.add(position < springer ? position : position - 1);
            }
        }
        return ergebnis;
    }

    /**
     * Positionen der Aufstellung ohne Springer zurueck auf die volle Aufstellung - die
     * Umkehrung von {@link #verschieben}. Die Liste ist veraenderlich; der Aufrufer haengt den
     * Springer an.
     */
    private static List<Integer> zurueckbilden(List<Integer> team, int springer) {
        List<Integer> ergebnis = new ArrayList<>(team.size() + 1);
        for (int position : team) {
            ergebnis.add(position < springer ? position : position + 1);
        }
        return ergebnis;
    }

    private static long summe(List<Long> staerken, List<Integer> team) {
        long summe = 0;
        for (int position : team) {
            summe += staerken.get(position);
        }
        return summe;
    }

    /**
     * Rechnet die gewichtete Gesamtstaerke je Teilnehmer, ein einziges Mal je Lauf.
     *
     * <h2>Eine zweite {@link Zielfunktion} und keine durchgereichte</h2>
     * Die Verfahren bauen sich ihre eigene; sie herauszureichen hiesse,
     * {@code Teamverfahren} um einen Rueckgabewert zu erweitern, den nur dieser eine Aufrufer
     * braucht. Der Aufbau kostet {@code n} mal {@code k} Ganzzahlen - bei 22 Teilnehmern und
     * fuenf Kategorien 110 Werte, gemessen an einer Enumeration von 705.432 Aufteilungen
     * nichts.
     *
     * <p><b>Massgeblich ist dieselbe Methode wie beim Snake-Draft</b>
     * ({@code Zielfunktion#staerke}): "schwaecher" soll beim Startteam des Springers nicht
     * etwas anderes heissen als beim Ziehen der Teams - und der {@code score_snapshot} einer
     * Zuteilung nicht etwas Drittes.
     */
    private static List<Long> staerken(Aufstellung aufstellung) {
        Zielfunktion zielfunktion = new Zielfunktion(aufstellung);
        List<Long> staerken = new ArrayList<>(aufstellung.groesse());
        for (int i = 0; i < aufstellung.groesse(); i++) {
            staerken.add(zielfunktion.staerke(i));
        }
        return staerken;
    }
}
