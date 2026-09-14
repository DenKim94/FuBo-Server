package de.fubo.appserver.dto.push;

/**
 * Antwort von {@code GET /api/v1/push/schluessel/lesen} (A25b).
 *
 * <h2>Der oeffentliche Schluessel ist kein Geheimnis</h2>
 * Der Client braucht ihn, um im Browser zu abonnieren
 * ({@code pushManager.subscribe({ applicationServerKey })}). Er geht ohnehin in jede
 * Push-Anfrage des Servers ein. <b>Der private Schluessel verlaesst den Server nie</b> - er
 * erscheint in keiner Antwort und in keiner Logzeile.
 *
 * <h2>Warum der Client ihn holt und nicht mitbringt</h2>
 * Ein in das Frontend eingebauter Schluessel waere ein zweiter Ort, an dem er steht - und bei
 * einem Wechsel des Paares muesste er dort nachgezogen werden. <b>Ein Schluesselwechsel
 * entwertet ohnehin saemtliche bestehenden Abonnements</b>; dass der Client den neuen sofort
 * bekommt, ist die Bedingung dafuer, dass die Spieler erneut zustimmen koennen.
 *
 * <p><b>Ist Push nicht eingerichtet, antwortet der Endpunkt {@code 503}</b> und nicht mit einem
 * leeren Feld: "auf diesem Server nicht eingerichtet" ist eine andere Aussage als "der
 * Schluessel ist leer", und nur die erste sagt dem Client, dass er den Bereich ausblenden und
 * es nicht wieder versuchen soll.
 *
 * @param vapidPublicKey oeffentlicher P-256-Schluessel als base64url ohne Polsterung, so wie er
 *                       in der Umgebung steht - <b>nicht neu kodiert</b>: Eine zweite Kodierung
 *                       waere eine zweite Gelegenheit, sie anders zu machen
 */
public record VapidSchluesselInfo(String vapidPublicKey) {
}
