package de.fubo.appserver.domain.team;

import java.util.List;

/**
 * Die Aufteilung, die ein neuer Lauf <b>nicht</b> wiederholen soll: die aktuelle Einteilung
 * des Termins, ausgedrueckt in Positionen der neuen {@link Aufstellung}.
 *
 * <h2>Warum ein eigener Typ und nicht {@link Teamaufteilung}</h2>
 * Eine {@code Teamaufteilung} ist das <i>Ergebnis</i> eines Laufs und traegt Kosten und
 * Verfahren. Hier zaehlt nur, wer mit wem zusammenspielte - und dass die Positionen zur
 * <i>neuen</i> Aufstellung gehoeren: Der Dienst rechnet die gespeicherten Zuteilungen ueber
 * die Teilnahme-Id darauf um, die Verfahren selbst kennen weder Termin noch Datenbank.
 *
 * <h2>Welches Team A und welches B heisst, spielt keine Rolle</h2>
 * Dieselben zwei Gruppen mit vertauschten Namen sind <b>dieselbe</b> Einteilung - fuer den
 * Spieler aendert sich nichts. Die Verfahren vergleichen deshalb Gruppen, nicht Beschriftungen.
 *
 * @param teamA Positionen des ersten Teams
 * @param teamB Positionen des zweiten Teams
 */
public record BisherigeAufteilung(List<Integer> teamA, List<Integer> teamB) {

    /** Beide Listen unveraenderlich. */
    public BisherigeAufteilung {
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }
}
