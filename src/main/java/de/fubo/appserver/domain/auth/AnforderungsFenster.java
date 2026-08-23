package de.fubo.appserver.domain.auth;

import java.time.OffsetDateTime;

/**
 * Auskunft ueber die Reset-Anforderungen einer Client-Adresse innerhalb der letzten
 * Stunde - die Grundlage der Drosselung (S2b, Abschnitt 5).
 *
 * <p><b>Warum auch der aelteste Zeitpunkt mitkommt:</b> Aus ihm ergibt sich, wann der
 * naechste Versuch wieder zulaessig ist. Ohne diesen Wert braeuchte es eine zweite Abfrage,
 * oder das Frontend bekaeme ein {@code 429} ohne {@code Retry-After} - und muesste raten.
 *
 * @param anzahl   Zahl der Anforderungen im Zeitfenster
 * @param aeltestes Zeitpunkt der aeltesten Anforderung im Zeitfenster; {@code null}, wenn
 *                  es keine gibt
 */
public record AnforderungsFenster(int anzahl, OffsetDateTime aeltestes) {
}
