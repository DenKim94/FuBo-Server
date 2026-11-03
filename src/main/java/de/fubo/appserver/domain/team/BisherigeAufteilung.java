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
 * <h2>Der Springer (03.11.2026)</h2>
 * Bei ungerader Zahl steht er in einem der beiden Teams und ist zusaetzlich benannt. Der
 * {@code Teamrechner} braucht ihn, um zu entscheiden, ob es ueberhaupt etwas zu meiden gibt:
 * Nur wenn derselbe Teilnehmer wieder Springer ist, laesst sich die bisherige Aufteilung der
 * uebrigen mit der neuen vergleichen. Mit einem anderen Springer ist jede Einteilung eine neue.
 * Die Verfahren lesen das Feld nicht.
 *
 * @param teamA    Positionen des ersten Teams
 * @param teamB    Positionen des zweiten Teams
 * @param springer Position des Springers (steht zugleich in {@code teamA} oder {@code teamB})
 *                 oder {@code null}, wenn es keinen gab - bei gerader Zahl und bei Laeufen von
 *                 vor dem 03.11.2026
 */
public record BisherigeAufteilung(List<Integer> teamA, List<Integer> teamB, Integer springer) {

    /** Beide Listen unveraenderlich. */
    public BisherigeAufteilung {
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }

    /**
     * Eine bisherige Aufteilung ohne Springer - so, wie sie die Verfahren kennen.
     *
     * @param teamA Positionen des ersten Teams
     * @param teamB Positionen des zweiten Teams
     */
    public BisherigeAufteilung(List<Integer> teamA, List<Integer> teamB) {
        this(teamA, teamB, null);
    }
}
