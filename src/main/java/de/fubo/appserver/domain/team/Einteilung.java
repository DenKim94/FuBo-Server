package de.fubo.appserver.domain.team;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Die gespeicherte Teameinteilung eines Termins, wie sie die Einzelansicht traegt
 * (S5 Abschnitte 9.1 und 9.2).
 *
 * <h2>Sie reist in der Einzelansicht mit</h2>
 * Entschieden am 06.09.2026 entlang der Empfehlung aus 9.1 (Weggabelung B): Das Dashboard
 * zeigt Termin, Teilnehmerliste und Teams zusammen - ein zweiter Endpunkt waeren zwei Aufrufe
 * fuer eine Ansicht. Und "gibt es schon eine Einteilung" beantwortet ein leeres Feld, ohne die
 * Wahl zwischen {@code 404} (klingt nach Fehler) und {@code 204} (klingt nach "nichts zu
 * holen").
 *
 * @param erzeugtAm         Zeitpunkt des Laufs
 * @param erzeugtVon        Anzeigename des Aufrufers zum Zeitpunkt des Laufs
 * @param veraltet          {@code true}, wenn sich der Teilnehmerkreis seither geaendert hat.
 *                          <b>Heisst "noch anzeigen, aber nicht mehr aktuell"</b> - verstecken
 *                          waere falsch, jemand hat sie vielleicht schon vorgelesen
 * @param auswechselspieler Anzeigename oder {@code null} bei gerader Teilnehmerzahl.
 *                          <b>{@code null} und kein leerer String</b>: Die Antwort sagt es
 *                          genauso
 * @param teamA             die Teilnehmer des ersten Teams
 * @param teamB             die Teilnehmer des zweiten Teams. <b>Die Reihenfolge in beiden
 *                          Listen ist keine Rangfolge</b> und darf im Frontend frei sortiert
 *                          werden - anders als die Teilnehmerliste, die ihre Ordnung nicht
 *                          verlieren darf
 */
public record Einteilung(OffsetDateTime erzeugtAm,
                         String erzeugtVon,
                         boolean veraltet,
                         String auswechselspieler,
                         List<Einteilungseintrag> teamA,
                         List<Einteilungseintrag> teamB) {

    /** Beide Listen unveraenderlich. */
    public Einteilung {
        teamA = List.copyOf(teamA);
        teamB = List.copyOf(teamB);
    }
}
