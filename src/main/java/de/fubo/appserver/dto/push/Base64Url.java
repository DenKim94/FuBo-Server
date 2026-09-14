package de.fubo.appserver.dto.push;

/**
 * Das Muster fuer base64url ohne Polsterung, wie RFC 8291 es verwendet.
 *
 * <p><b>Eine Konstante und kein wiederholtes Literal.</b> Sie wird an drei Stellen gebraucht -
 * {@code p256dh} und {@code auth} beim Anlegen, und sie soll dieselbe sein. Ein zweites
 * abgeschriebenes Muster liefe auseinander, und die Abweichung zeigte sich erst an einem
 * Abonnement, das der Server annimmt und nicht entschluesseln kann.
 *
 * <p><b>Zulaessig sind auch Polsterzeichen am Ende.</b> RFC 8291 verwendet base64url ohne
 * Polsterung, die Push-API des Browsers liefert die Schluessel aber als Rohbytes - der Client
 * kodiert sie selbst, und manche Umsetzungen polstern. Ein {@code =} abzulehnen hiesse, ein
 * einwandfreies Abonnement wegen einer Kodierungsgewohnheit zurueckzuweisen; der Dekodierer
 * der Anwendung nimmt beides.
 *
 * <p><b>{@code +} und {@code /} sind dagegen unzulaessig</b>, obwohl sie in der
 * Standardvariante von Base64 vorkommen: Sie stehen in einer URL fuer etwas anderes, und der
 * Dekodierer lehnt sie ab. Ein Wert damit waere still unbrauchbar.
 */
final class Base64Url {

    /** Nur das Alphabet von base64url, mit optionaler Polsterung am Ende. */
    static final String MUSTER = "^[A-Za-z0-9_-]+={0,2}$";

    private Base64Url() {
    }
}
