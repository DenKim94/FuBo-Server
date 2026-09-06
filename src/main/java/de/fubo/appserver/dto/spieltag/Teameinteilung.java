package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.team.Einteilung;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Die Teameinteilung eines Termins an der API-Grenze (S5 Abschnitt 9.2).
 *
 * <h2>Sie erscheint an zwei Stellen</h2>
 * als nullbares Feld {@code teams} in {@link TerminDetails} und als Antwort von
 * {@code POST /api/v1/teams/generieren}. Beide Male dieselbe Form - wer gerade generiert hat,
 * sieht dasselbe wie der, der den Termin oeffnet.
 *
 * <h2>Ohne {@code differenzTeamstaerke}</h2>
 * Weggabelung C, entschieden entlang der Empfehlung: Der Wert ist die gewichtete Summe der
 * Kategoriedifferenzen und damit eine Aussage ueber Skillsummen. Wer ihn ueber viele Laeufe
 * sammelt und die wechselnden Aufstellungen kennt, kann auf einzelne Werte schliessen; der
 * Nutzen fuer den Spieler - eine Zahl, die er nicht einordnen kann - wiegt das nicht auf.
 * <b>Unterhalb von {@code /admin/} ist er erlaubt</b> und steht dort in
 * {@code ManuelleEinteilung}. Die Weggabelung ist damit nicht umgangen, sondern entlang der
 * Pfadregel entschieden.
 *
 * @param erzeugtAm         Zeitpunkt des Laufs
 * @param erzeugtVon        Anzeigename des Aufrufers zum Zeitpunkt des Laufs
 * @param veraltet          {@code true} heisst "noch anzeigen, aber nicht mehr aktuell" -
 *                          verstecken waere falsch, jemand hat sie vielleicht schon vorgelesen
 * @param auswechselspieler Anzeigename oder {@code null} bei gerader Teilnehmerzahl
 * @param teamA             Team A; die Reihenfolge ist <b>keine Rangfolge</b>
 * @param teamB             Team B
 */
public record Teameinteilung(OffsetDateTime erzeugtAm,
                             String erzeugtVon,
                             boolean veraltet,
                             String auswechselspieler,
                             List<TeamEintrag> teamA,
                             List<TeamEintrag> teamB) {

    /** Bildet das Wertobjekt auf den Vertrag ab. */
    public static Teameinteilung von(Einteilung einteilung) {
        return new Teameinteilung(
                einteilung.erzeugtAm(),
                einteilung.erzeugtVon(),
                einteilung.veraltet(),
                einteilung.auswechselspieler(),
                einteilung.teamA().stream().map(TeamEintrag::von).toList(),
                einteilung.teamB().stream().map(TeamEintrag::von).toList());
    }
}
