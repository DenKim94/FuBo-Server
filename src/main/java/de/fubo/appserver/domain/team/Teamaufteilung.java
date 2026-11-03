package de.fubo.appserver.domain.team;

import de.fubo.appserver.domain.config.AlgorithmType;

import java.util.List;

/**
 * Das Ergebnis eines Generierungslaufs ({@code S5_ALGORITHMUS.md}, Abschnitte 4 und 5).
 *
 * <h2>Indizes, keine Spieler</h2>
 * Beide Teams sind Positionen in {@link Aufstellung#spieler()}. Das ist nicht Sparsamkeit,
 * sondern dieselbe Festlegung wie ueberall im Algorithmusteil: <b>Die Identitaet innerhalb
 * eines Laufs ist die Position in der Liste.</b> Wer den Namen braucht, greift ueber die
 * Aufstellung zu; wer die Staerke eines Spielers braucht - fuer {@code score_snapshot} (7.2)
 * oder das Startteam des Springers -, gibt denselben Index an {@code Zielfunktion#staerke}.
 * Ein Ergebnis, das Spieler traegt, muesste diesen Weg fuer jeden von ihnen erst wieder
 * herstellen.
 *
 * <h2>{@code verwendetesVerfahren} und nicht "das eingestellte"</h2>
 * {@code EXHAUSTIV} weicht oberhalb von {@code MAX_EXHAUSTIV} auf {@code HEURISTIK} aus (4.4).
 * <b>Was tatsaechlich gerechnet wurde, steht hier</b> - der manuelle Lauf gibt es nach aussen
 * weiter (9.4), und ohne dieses Feld muesste der Aufrufer die Grenze selbst kennen und
 * nachrechnen.
 *
 * @param teamA                Indizes des ersten Teams
 * @param teamB                Indizes des zweiten Teams
 * @param kosten               Summe der gewichteten Kategorieabweichungen in
 *                             <b>Hundertsteln</b> ({@code Zielfunktion#kosten}); {@code 0}
 *                             heisst perfekt ausgeglichen. Die Zahl, die als
 *                             {@code differenz_teamstaerke} gespeichert wird - nicht der
 *                             Tie-Break und nicht die {@code Zielfunktion#bewertung}, nach der
 *                             gewaehlt wurde (seit 03.11.2026: erst Engpass, dann diese Summe).
 *                             Bei ungerader Zahl ohne den Springer gerechnet
 * @param verwendetesVerfahren das Verfahren, das gerechnet hat
 */
public record Teamaufteilung(List<Integer> teamA, List<Integer> teamB, long kosten,
                             AlgorithmType verwendetesVerfahren) {

    /** Beide Listen unveraenderlich - ein Ergebnis, das sich nachtraeglich aendert, ist keines. */
    public Teamaufteilung {
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }
}
