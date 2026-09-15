package de.fubo.appserver.domain.push;

/**
 * Wie auf die Antwort eines Push-Dienstes zu reagieren ist (A25b, S8 Abschnitt 6.2).
 *
 * <p>Die Zuordnung von HTTP-Status zu Reaktion steht an <b>genau einer Stelle</b>:
 * {@link PushAntwort#von(int)}. Dieser Aufzaehlungstyp ist ihr Ergebnis, und der Versand
 * entscheidet danach, was in die Datenbank geschrieben wird - er sieht den Statuscode nur
 * noch fuer das Protokoll.
 *
 * <h2>Die beiden Werte, die leicht zusammenfallen wuerden</h2>
 * {@link #ERLOSCHEN} und {@link #SCHLUESSEL_ABGELEHNT} sehen sich aehnlich und muessen
 * getrennt bleiben: Ein {@code 410} sagt etwas ueber <i>dieses</i> Abonnement, ein
 * {@code 401} etwas ueber <b>unseren</b> VAPID-Schluessel. Wer das zweite wie das erste
 * behandelt, loescht nach einem Konfigurationsfehler den gesamten Bestand - und der laesst
 * sich nur durch erneute Zustimmung jedes einzelnen Spielers im Browser wiederherstellen.
 */
public enum PushErgebnis {

    /**
     * Der Dienst hat die Nachricht angenommen ({@code 201}, {@code 200}).
     *
     * <p><b>Das ist keine Zustellbestaetigung.</b> Web Push kennt keine; die Nachricht kann
     * beim Empfaenger liegen bleiben, verworfen werden oder unlesbar ankommen. Gebucht wird
     * deshalb {@code letzter_versand_am} - der <i>Versuch</i>, wie
     * {@code termin.halle_abgesagt_am} in S7.
     */
    ZUGESTELLT,

    /**
     * Das Abonnement gibt es nicht mehr ({@code 404}, {@code 410}) - {@code deaktiviert_am}
     * setzen, kein weiterer Versuch.
     *
     * <p>Der Normalfall dahinter ist harmlos: Der Nutzer hat die Berechtigung im Browser
     * entzogen oder die Anwendung entfernt. <b>Geloescht wird die Zeile nicht</b>; der
     * Zwischenzustand unterscheidet "nie abonniert" von "Abonnement erloschen", und
     * {@code /push/abo/anlegen} heilt sie beim naechsten Anwendungsstart wieder.
     */
    ERLOSCHEN,

    /**
     * Der Dienst war nicht bereit ({@code 429}, {@code 5xx}), der Aufruf ist gescheitert oder
     * die Gesamtfrist abgelaufen - {@code fehlversuche} um eins erhoehen, ab fuenf
     * deaktivieren.
     *
     * <p><b>Keine Wiederholung dieser Nachricht.</b> {@code fehlversuche} ist ein
     * Gesundheitszaehler des Abonnements ueber mehrere Anlaesse hinweg, keine Warteschlange:
     * Der Termin ist nach dem bedingten {@code UPDATE} markiert, der naechste Lauf
     * ueberspringt ihn. Ein {@code 5xx} kostet diesem Empfaenger diese Erinnerung - der Preis
     * des Doppelversandschutzes, und bewusst so herum gewaehlt.
     */
    FEHLVERSUCH,

    /**
     * <b>Ein Fehler auf unserer Seite, keiner des Abonnements.</b>
     *
     * <p>Zwei Ursachen fuehren hierher: eine Nutzlast ueber der Grenze - vom Adapter selbst
     * erkannt oder als {@code 413} des Dienstes - und eine Nutzlast, die sich nicht
     * serialisieren liess.
     *
     * <p><b>Der Wert heisst deshalb nicht {@code NUTZLAST_ZU_GROSS}</b>, obwohl das die
     * haeufigere der beiden Ursachen waere: Ein Name, der nur den einen Fall nennt, waere beim
     * anderen eine falsche Auskunft im Protokoll - und die Reaktion ist bei beiden dieselbe.
     *
     * <p>Die Zeile bleibt unangetastet, auch der Zaehler: Dasselbe wuerde jedem anderen
     * Empfaenger widerfahren, und fuenf solche Nachrichten deaktivierten sonst ein
     * einwandfreies Abonnement. Der Fall soll im Anwendungsprotokoll auffallen; die Grenze von
     * 3 000 Byte liegt weit unter den zugelassenen 4 096 und wird vor dem Versand geprueft.
     */
    ANWENDUNGSFEHLER,

    /**
     * Der Dienst hat unsere Berechtigung abgelehnt ({@code 401}, {@code 403}) - <b>nicht
     * deaktivieren.</b>
     *
     * <p>Die Ursache liegt bei uns: falsches VAPID-Paar, ein vertauschter privater
     * Schluessel, ein {@code sub}-Anspruch, den der Dienst nicht annimmt, oder eine Signatur
     * in DER-Form statt in der festen 64-Byte-Form. <b>Diese Zeile steht nicht in
     * {@code AGENT.md} und gehoert dazu</b>: Ohne sie loescht ein Konfigurationsfehler jedes
     * Abonnement, das er beruehrt.
     */
    SCHLUESSEL_ABGELEHNT
}
