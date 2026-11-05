package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Teamergebnis;
import de.fubo.appserver.dto.spieltag.TeamEintrag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Antwort von {@code POST /api/v1/admin/teams/generieren} (A24, S5 Abschnitt 9.4).
 *
 * <h2>Warum sie unter {@code dto/admin} liegt und nicht bei den Terminen</h2>
 * Sie traegt die Punktsummen je Team und Kategorie ({@code gesamtpunkteTeamA}/{@code B}) -
 * Skillsummen und damit genau das, was A12 ausschliesslich unterhalb von
 * {@code /api/*&#47;admin/**} nach aussen laesst; der Schutz haengt am Pfad, aber dass die
 * Typen dort liegen, soll beim Lesen auffallen. {@link TeamEintrag} selbst traegt keine
 * Bewertung und bleibt deshalb in {@code dto/spieltag}.
 *
 * <h2>Punktsummen statt {@code differenzTeamstaerke} (05.11.2026)</h2>
 * Anforderung des Entwicklers: Der Admin soll je Kategorie sehen, wie viele Punkte jedes Team
 * hat. Die eine Zahl {@code differenzTeamstaerke} (gewichtete Summe der Abweichungen) sagte,
 * <i>dass</i> die Teams ausgeglichen sind, aber nicht <i>wo</i> sie auseinanderliegen - und
 * ihr Name versprach etwas anderes. Sie entfaellt; die Abweichung je Kategorie ist die
 * Differenz der beiden Summen. Gespeichert wird sie am Termin-Lauf weiterhin
 * ({@code team_generierung.differenz_teamstaerke}).
 *
 * <h2>Kein {@code seed}</h2>
 * Festlegung vom 05.09.2026: Er waere fuer den Admin eine Zahl ohne Verwendung, denn
 * nachrechnen liesse sich ein Lauf nur mit den Skillwerten jedes Einzelnen, und die stehen hier
 * bewusst nicht - die Summen genuegen dafuer nicht. <b>Damit ist der Audit-Eintrag der einzige
 * Ort, an dem der Seed eines manuellen Laufs ueberhaupt steht.</b>
 *
 * <h2>Zweimal "tatsaechlich verwendet" ist kein Zufall</h2>
 * Der manuelle Lauf hat zwei Stellen, an denen die Anwendung von der Konfiguration abweichen
 * darf - {@code MAX_EXHAUSTIV} und die fehlende Meldezeit, mit der {@code ZULETZT_ANGEMELDET}
 * auf {@code ZUFAELLIG} zurueckfaellt - und keine Tabelle, in der man spaeter nachsaehe. Was
 * abgewichen ist, steht deshalb in der Antwort.
 *
 * <p><b>Das Ergebnis wird nirgends gespeichert.</b> Es erscheint bei keinem anderen Nutzer und
 * ist nach dem Verlassen der Seite weg; das gehoert sichtbar auf den Bildschirm, sonst haelt
 * der Admin es fuer die Einteilung eines Spieltags.
 *
 * @param algorithmType     das tatsaechlich gerechnete Verfahren, nicht das eingestellte
 * @param auswechselModus   die tatsaechlich angewandte Regel, nicht die eingestellte
 * @param auswechselspieler Anzeigename des Springers oder {@code null} bei gerader
 *                          Teilnehmerzahl; er steht zusaetzlich in seinem Startteam
 * @param gesamtpunkteTeamA ungewichtete Punktsumme je aktiver Kategorie fuer Team A,
 *                          Kategorieschluessel in Kategorienreihenfolge, <b>ohne den
 *                          Springer</b> ({@code Teamergebnis#gesamtpunkte})
 * @param gesamtpunkteTeamB dasselbe fuer Team B
 * @param teamA             Team A
 * @param teamB             Team B
 */
public record ManuelleEinteilung(AlgorithmType algorithmType,
                                 AuswechselModus auswechselModus,
                                 String auswechselspieler,
                                 Map<String, Integer> gesamtpunkteTeamA,
                                 Map<String, Integer> gesamtpunkteTeamB,
                                 List<TeamEintrag> teamA,
                                 List<TeamEintrag> teamB) {

    /** Bildet das Rechenergebnis auf den Vertrag ab. */
    public static ManuelleEinteilung von(Teamergebnis ergebnis) {
        List<Integer> teamA = ergebnis.aufteilung().teamA();
        List<Integer> teamB = ergebnis.aufteilung().teamB();
        return new ManuelleEinteilung(
                ergebnis.verwendetesVerfahren(),
                ergebnis.verwendeterModus(),
                ergebnis.springerName(),
                ergebnis.gesamtpunkte(teamA),
                ergebnis.gesamtpunkte(teamB),
                eintraege(ergebnis, teamA),
                eintraege(ergebnis, teamB));
    }

    /**
     * Setzt ein Team aus den Indizes der Aufteilung zusammen.
     *
     * <p>Die Indizes zeigen in die Aufstellung des Laufs; sie sind die Identitaet eines
     * Teilnehmers innerhalb eines Laufs. Erst hier werden daraus Namen - vorher braucht sie
     * niemand.
     */
    private static List<TeamEintrag> eintraege(Teamergebnis ergebnis, List<Integer> team) {
        List<TeamEintrag> eintraege = new ArrayList<>(team.size());
        for (Integer index : team) {
            Aufstellungsspieler spieler = ergebnis.spieler(index);
            eintraege.add(new TeamEintrag(spieler.anzeigeName(), spieler.gast(),
                    ergebnis.istSpringer(index)));
        }
        return eintraege;
    }
}
