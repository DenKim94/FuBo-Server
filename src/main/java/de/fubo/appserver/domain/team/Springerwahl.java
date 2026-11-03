package de.fubo.appserver.domain.team;

import de.fubo.appserver.domain.config.AuswechselModus;

/**
 * Wer Springer ist - und nach welcher Regel das entschieden wurde (A20b, 03.11.2026).
 *
 * <h2>Warum der Modus im Ergebnis steht und nicht nur in der Konfiguration</h2>
 * {@code ZULETZT_ANGEMELDET} hat im manuellen Lauf keine Datengrundlage: {@code gemeldetAm}
 * ist dort {@code null}, weil sich niemand gemeldet hat. Die Wahl faellt deshalb auf
 * {@code ZUFAELLIG} zurueck - und <b>sagt es</b>. Still zurueckzufallen waere das Schlimmste
 * von beidem: Der Admin hat die Einstellung gesetzt und saehe ein Ergebnis, das ihr
 * widerspricht, ohne Hinweis.
 *
 * @param position         Position des Springers in der Aufstellung
 * @param verwendeterModus die Regel, nach der tatsaechlich gewaehlt wurde - nicht unbedingt
 *                         die eingestellte
 */
public record Springerwahl(int position, AuswechselModus verwendeterModus) {
}
