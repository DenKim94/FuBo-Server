package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Aufstellung;
import de.fubo.appserver.domain.team.Bankentscheid;
import de.fubo.appserver.domain.team.Bankkandidat;
import de.fubo.appserver.domain.team.Teamaufteilung;
import de.fubo.appserver.domain.team.Teamergebnis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Die Rechnung eines Generierungslaufs, von der Aufstellung bis zum Auswechselspieler
 * (S5 Abschnitte 7.1 Schritte 4 und 5, 8).
 *
 * <h2>Die eine Stelle, an der beide Eingangstueren zusammenlaufen</h2>
 * Der Termin-Lauf und der manuelle Lauf des Admins (A24) rufen dieselbe Methode auf. <b>Wer
 * hier verzweigt, hat aus einer zweiten Eingangstuer einen zweiten Generator gemacht</b> - und
 * die zweite Fassung waere die, die niemand gegen die erste prueft.
 *
 * <h2>Was dieser Dienst nicht kennt</h2>
 * Termin, Sitzung, Kontingent, Audit-Log und jede Datenbanktabelle ausser den Kategorien, die
 * bereits in der Aufstellung stecken. Alles, was den Spieltag kennt, liegt in
 * {@code service.spieltag}; ab hier weiss niemand mehr, woher die Liste kommt.
 */
@Service
public class Teamrechner {

    private final TeamverfahrenAuswahl verfahrenAuswahl;
    private final AuswechselErmittlung auswechselErmittlung;

    public Teamrechner(TeamverfahrenAuswahl verfahrenAuswahl,
                       AuswechselErmittlung auswechselErmittlung) {
        this.verfahrenAuswahl = verfahrenAuswahl;
        this.auswechselErmittlung = auswechselErmittlung;
    }

    /**
     * Teilt die Aufstellung in zwei Teams und bestimmt bei ungerader Zahl den
     * Auswechselspieler.
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
     * @param modus       der eingestellte Auswechselmodus aus {@code configs.app_config}
     * @param seed        der Seed dieses Laufs
     * @return Aufteilung, Auswechselspieler und die tatsaechlich verwendeten Einstellungen
     */
    public Teamergebnis rechne(Aufstellung aufstellung, AlgorithmType verfahren,
                               AuswechselModus modus, long seed) {

        Teamaufteilung aufteilung = verfahrenAuswahl.fuer(verfahren).berechne(aufstellung, seed);
        List<Long> staerken = staerken(aufstellung);

        if (!aufstellung.ungerade()) {
            // Bei gerader Zahl ist nichts zu entscheiden. Der eingestellte Modus wird trotzdem
            // durchgereicht: Die Antwort nennt ihn, und "nicht angewandt" ist etwas anderes
            // als "abgewichen".
            return new Teamergebnis(aufstellung, aufteilung, staerken, null, modus, seed);
        }

        Bankentscheid entscheid = auswechselErmittlung.waehle(
                kandidaten(aufteilung, staerken, aufstellung), modus, seed);

        return new Teamergebnis(aufstellung, aufteilung, staerken,
                entscheid.position(), entscheid.verwendeterModus(), seed);
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
     * ({@code Zielfunktion#staerke}): "Schwaechster" soll auf der Bank nicht etwas anderes
     * heissen als beim Ziehen der Teams - und der {@code score_snapshot} einer Zuteilung
     * nicht etwas Drittes.
     */
    private static List<Long> staerken(Aufstellung aufstellung) {
        Zielfunktion zielfunktion = new Zielfunktion(aufstellung);
        List<Long> staerken = new ArrayList<>(aufstellung.groesse());
        for (int i = 0; i < aufstellung.groesse(); i++) {
            staerken.add(zielfunktion.staerke(i));
        }
        return staerken;
    }

    /**
     * Baut die Kandidatenliste aus dem Ueberzahl-Team.
     *
     * <p>Die Reihenfolge ist die des Ueberzahl-Teams und bleibt es: Sie entscheidet bei
     * Gleichstand zusammen mit dem Seed, und dieselbe Reihenfolge muss sich beim Lesen einer
     * gespeicherten Einteilung wieder herstellen lassen (8.3).
     */
    private static List<Bankkandidat> kandidaten(Teamaufteilung aufteilung,
                                                 List<Long> staerken,
                                                 Aufstellung aufstellung) {
        List<Integer> ueberzahl = aufteilung.ueberzahl();

        List<Bankkandidat> kandidaten = new ArrayList<>(ueberzahl.size());
        for (Integer index : ueberzahl) {
            Aufstellungsspieler spieler = aufstellung.spieler().get(index);
            kandidaten.add(new Bankkandidat(
                    index, staerken.get(index), spieler.gemeldetAm()));
        }
        return kandidaten;
    }
}
