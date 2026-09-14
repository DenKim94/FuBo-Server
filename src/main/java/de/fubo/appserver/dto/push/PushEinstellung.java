package de.fubo.appserver.dto.push;

/**
 * Antwort von {@code POST /api/v1/push/einstellung/aendern} (A25f).
 *
 * <p>Sie gibt den <b>gespeicherten</b> Stand zurueck und nicht den gesendeten. Der Unterschied
 * ist klein und traegt trotzdem: Der Client zeigt damit, was der Server fuehrt, und muss
 * seinen eigenen Wunsch nicht als Wahrheit annehmen.
 *
 * <p><b>Ein eigener Record und nicht {@link PushStatus}:</b> Der Statusendpunkt liefert zwei
 * Ebenen, dieser Aufruf aendert genau eine. Die Anlagenebene mitzugeben hiesse zu behaupten,
 * sie sei Teil dessen, was hier geschrieben wurde.
 *
 * @param pushErwuenscht der jetzt gespeicherte Stand des Personenschalters
 */
public record PushEinstellung(boolean pushErwuenscht) {
}
