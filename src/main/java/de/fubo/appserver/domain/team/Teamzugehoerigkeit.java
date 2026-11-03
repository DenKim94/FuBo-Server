package de.fubo.appserver.domain.team;

/**
 * Wo eine Teilnahme in der aktuellen Einteilung eines Termins steht - die Grundlage fuer
 * {@link BisherigeAufteilung}.
 *
 * <p>Ein eigener Typ statt eines {@code Character}, seit der Springer dazugehoert
 * (03.11.2026): Der {@code Teamrechner} meidet die bisherige Aufteilung nur, wenn derselbe
 * Teilnehmer wieder Springer ist, und muss dafuer wissen, wer es war.
 *
 * @param team     {@code 'A'} oder {@code 'B'}
 * @param springer {@code true} fuer den Springer des Laufs
 */
public record Teamzugehoerigkeit(char team, boolean springer) {
}
