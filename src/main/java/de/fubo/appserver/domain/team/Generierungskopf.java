package de.fubo.appserver.domain.team;

import java.time.OffsetDateTime;

/**
 * Der Kopfsatz einer gespeicherten Teameinteilung (S5 Abschnitte 7.3, 7.4).
 *
 * @param id         Schluessel in {@code spieltag.team_generierung}
 * @param erzeugtAm  Zeitpunkt des Laufs, aus dem Spaltendefault {@code now()}
 * @param erzeugtVon Anzeigename des Aufrufers, wie er beim Lauf galt. <b>Eine Kopie und kein
 *                   Verweis</b> - ein spaeter geloeschtes Profil liesse die Auskunft sonst leer
 * @param seed       der Seed des Laufs; wird zum Nachvollziehen des Auswechselspielers
 *                   gebraucht (8.3)
 * @param veraltet   {@code true}, wenn sich der Teilnehmerkreis seither geaendert hat.
 *                   <b>Abgeleitet, nicht gespeichert</b> ({@code tg.teilnehmer_version <>
 *                   t.teilnehmer_version}): Eine Spalte muesste bei jeder Zusage ueber alle
 *                   Laeufe nachgezogen werden - dieselbe Regel wie bei der
 *                   Warteschlangenposition in S4
 */
public record Generierungskopf(Long id, OffsetDateTime erzeugtAm, String erzeugtVon,
                               long seed, boolean veraltet) {
}
