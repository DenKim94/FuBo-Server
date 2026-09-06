package de.fubo.appserver.domain.team;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Eine Zeile fuer {@code spieltag.team_zuteilung} (S5 Abschnitt 7.2).
 *
 * <h2>Der Snapshot ist Pflicht, nicht Zierde</h2>
 * {@code score_snapshot} und {@code skills_snapshot} halten die Werte fest, <b>die zum
 * Zeitpunkt des Laufs galten</b>. Ohne sie liesse sich eine Woche spaeter nicht mehr sagen,
 * warum die Teams so aussahen - die Profilwerte sind bis dahin womoeglich korrigiert worden,
 * und die Einteilung erschiene grundlos schief.
 *
 * @param teilnahmeId Schluessel in {@code spieltag.teilnahme}. <b>Im manuellen Lauf gibt es
 *                    ihn nicht</b> - deshalb wird der gar nicht erst gespeichert (A24, 0.6)
 * @param team        {@code 'A'} oder {@code 'B'}; {@code ck_team_zuteilung_team} laesst
 *                    nichts anderes zu
 * @param score       gewichtete Gesamtstaerke, bereits in der Darstellung der Spalte
 *                    ({@code NUMERIC(6,2)}). <b>Umgerechnet wird in {@code Zielfunktion}</b> und
 *                    nicht hier oder im Repository: Die Definition von "Staerke" und ihre
 *                    Darstellung sollen nicht an zwei Orten stehen
 * @param werte       Skillwert je Kategorieschluessel, flach. <b>Flach und nicht
 *                    geschachtelt</b>, wie der handgeschriebene Serialisierer im
 *                    {@code AuditService}: zwei Formen fuer dieselbe Sache waeren beim
 *                    Auswerten eine Fallunterscheidung ohne Anlass
 */
public record Zuteilungssatz(Long teilnahmeId, char team, BigDecimal score,
                             Map<String, Integer> werte) {

    /** Die Karte wird unveraenderlich uebernommen - ein Snapshot, der sich aendert, ist keiner. */
    public Zuteilungssatz {
        werte = Map.copyOf(werte);
    }
}
