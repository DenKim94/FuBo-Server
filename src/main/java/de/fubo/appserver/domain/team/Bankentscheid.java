package de.fubo.appserver.domain.team;

import de.fubo.appserver.domain.config.AuswechselModus;

/**
 * Wer aussetzt - und nach welcher Regel das entschieden wurde (A20b, S5 Abschnitte 8.2, 8.4).
 *
 * <h2>Warum der Modus im Ergebnis steht und nicht nur in der Konfiguration</h2>
 * {@code ZULETZT_ANGEMELDET} hat im manuellen Lauf keine Datengrundlage: {@code gemeldetAm}
 * ist dort {@code null}, weil sich niemand gemeldet hat. Der Lauf faellt deshalb auf
 * {@code SCHWAECHSTER_UEBERZAHL} zurueck - und <b>sagt es</b>. Still zurueckzufallen waere das
 * Schlimmste von beidem: Der Admin hat die Einstellung gesetzt und saehe ein Ergebnis, das
 * ihr widerspricht, ohne Hinweis.
 *
 * @param position          gewaehlter Kandidat, in der Zaehlung von {@link Bankkandidat}
 * @param verwendeterModus  die Regel, nach der tatsaechlich gewaehlt wurde - nicht unbedingt
 *                          die eingestellte
 */
public record Bankentscheid(int position, AuswechselModus verwendeterModus) {
}
