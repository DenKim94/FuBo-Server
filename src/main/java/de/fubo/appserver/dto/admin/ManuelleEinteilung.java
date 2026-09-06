package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.config.AlgorithmType;
import de.fubo.appserver.domain.config.AuswechselModus;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import de.fubo.appserver.domain.team.Teamergebnis;
import de.fubo.appserver.dto.spieltag.TeamEintrag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Antwort von {@code POST /api/v1/admin/teams/generieren} (A24, S5 Abschnitt 9.4).
 *
 * <h2>Warum sie unter {@code dto/admin} liegt und nicht bei den Terminen</h2>
 * Sie traegt {@code differenzTeamstaerke} - eine abgeleitete Kennzahl der Teamstaerke und
 * damit eine Aussage ueber Skillsummen. A12 laesst solche Werte ausschliesslich unterhalb von
 * {@code /api/*&#47;admin/**} nach aussen; der Schutz haengt am Pfad, aber dass die Typen dort
 * liegen, soll beim Lesen auffallen. {@link TeamEintrag} selbst traegt keine Bewertung und
 * bleibt deshalb in {@code dto/spieltag}.
 *
 * <h2>Sechs Felder - und kein {@code seed}</h2>
 * Festlegung vom 05.09.2026: Er waere fuer den Admin eine Zahl ohne Verwendung, denn
 * nachrechnen liesse sich ein Lauf nur mit den Skillwerten dazu, und die stehen hier bewusst
 * nicht. <b>Damit ist der Audit-Eintrag der einzige Ort, an dem der Seed eines manuellen
 * Laufs ueberhaupt steht.</b>
 *
 * <h2>Zweimal "tatsaechlich verwendet" ist kein Zufall</h2>
 * Der manuelle Lauf hat zwei Stellen, an denen die Anwendung von der Konfiguration abweichen
 * darf - {@code MAX_EXHAUSTIV} und die fehlende Meldezeit (8.4) - und keine Tabelle, in der
 * man spaeter nachsaehe. Was abgewichen ist, steht deshalb in der Antwort.
 *
 * <p><b>Das Ergebnis wird nirgends gespeichert.</b> Es erscheint bei keinem anderen Nutzer und
 * ist nach dem Verlassen der Seite weg; das gehoert sichtbar auf den Bildschirm, sonst haelt
 * der Admin es fuer die Einteilung eines Spieltags.
 *
 * @param algorithmType        das tatsaechlich gerechnete Verfahren, nicht das eingestellte
 * @param auswechselModus      die tatsaechlich angewandte Regel, nicht die eingestellte
 * @param auswechselspieler    Anzeigename oder {@code null} bei gerader Teilnehmerzahl
 * @param differenzTeamstaerke die <b>Kosten der Zielfunktion</b> - die gewichtete Summe der
 *                             Kategoriedifferenzen, nicht die Differenz der Gesamtstaerken.
 *                             {@code 0} heisst perfekt ausgeglichen, groesser heisst schlechter
 * @param teamA                Team A
 * @param teamB                Team B
 */
public record ManuelleEinteilung(AlgorithmType algorithmType,
                                 AuswechselModus auswechselModus,
                                 String auswechselspieler,
                                 BigDecimal differenzTeamstaerke,
                                 List<TeamEintrag> teamA,
                                 List<TeamEintrag> teamB) {

    /** Bildet das Rechenergebnis auf den Vertrag ab. */
    public static ManuelleEinteilung von(Teamergebnis ergebnis) {
        return new ManuelleEinteilung(
                ergebnis.verwendetesVerfahren(),
                ergebnis.verwendeterModus(),
                ergebnis.auswechselName(),
                ergebnis.kostenAlsDezimal(),
                eintraege(ergebnis, ergebnis.aufteilung().teamA()),
                eintraege(ergebnis, ergebnis.aufteilung().teamB()));
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
                    ergebnis.istAuswechselspieler(index)));
        }
        return eintraege;
    }
}
