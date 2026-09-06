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
 * oder den Auswechselspieler (8.1) -, gibt denselben Index an {@code Zielfunktion#staerke}.
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
 * @param kosten               Wert der Zielfunktion in <b>Hundertsteln</b>; {@code 0} heisst
 *                             perfekt ausgeglichen. Dieselbe Zahl, die der Lauf minimiert hat
 *                             und die als {@code differenz_teamstaerke} gespeichert wird -
 *                             nicht der Tie-Break
 * @param verwendetesVerfahren das Verfahren, das gerechnet hat
 */
public record Teamaufteilung(List<Integer> teamA, List<Integer> teamB, long kosten,
                             AlgorithmType verwendetesVerfahren) {

    /** Beide Listen unveraenderlich - ein Ergebnis, das sich nachtraeglich aendert, ist keines. */
    public Teamaufteilung {
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }

    /**
     * Die Indizes des groesseren Teams - dort steht bei ungerader Zahl der Auswechselspieler
     * (A20b).
     *
     * <p>Bei gerader Zahl sind beide gleich gross; die Methode liefert dann {@code teamA},
     * und der Aufrufer fragt vorher {@link Aufstellung#ungerade()}.
     */
    public List<Integer> ueberzahl() {
        return teamB.size() > teamA.size() ? teamB : teamA;
    }
}
