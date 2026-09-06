package de.fubo.appserver.domain.team;

import java.time.OffsetDateTime;

/**
 * Ein Teilnehmer des Ueberzahl-Teams, soweit die Wahl des Auswechselspielers ihn braucht
 * (A20b, S5 Abschnitt 8).
 *
 * <h2>Warum ein eigener, sehr schmaler Typ</h2>
 * Die Wahl faellt an <b>zwei</b> Stellen mit unterschiedlicher Datengrundlage: unmittelbar
 * nach einem Lauf (Staerke aus {@code Zielfunktion#staerke}, Meldezeit aus der Aufstellung)
 * und beim Lesen einer gespeicherten Einteilung (Staerke aus {@code score_snapshot},
 * Meldezeit aus {@code spieltag.teilnahme}). <b>Beide muessen dasselbe Ergebnis liefern</b> -
 * sonst zeigte die Einzelansicht einen anderen Auswechselspieler als der Lauf, der ihn
 * bestimmt hat. Ein gemeinsamer Eingabetyp erzwingt dieselbe Rechnung; zwei Ueberladungen mit
 * je eigener Schleife liefen frueher oder spaeter auseinander.
 *
 * @param position   Stelle in der Liste, aus der gewaehlt wird - im Lauf der Index in der
 *                   Aufstellung, beim Lesen die Position innerhalb des Ueberzahl-Teams.
 *                   <b>Die Reihenfolge ist Teil der Zusicherung</b>: Bei Gleichstand
 *                   entscheidet der Seed, und derselbe Seed waehlt nur dann dasselbe, wenn die
 *                   Kandidaten in derselben Ordnung vorliegen
 * @param staerke    gewichtete Gesamtstaerke in <b>Hundertsteln</b>, wie ueberall im
 *                   Algorithmusteil
 * @param gemeldetAm Zeitpunkt der Zusage; {@code null} im manuellen Lauf (A24) - dort hat sich
 *                   niemand gemeldet, und der Modus {@code ZULETZT_ANGEMELDET} hat keine
 *                   Datengrundlage
 */
public record Bankkandidat(int position, long staerke, OffsetDateTime gemeldetAm) {
}
