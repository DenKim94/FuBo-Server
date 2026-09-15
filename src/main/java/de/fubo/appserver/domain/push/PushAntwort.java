package de.fubo.appserver.domain.push;

/**
 * Die Antwort eines Push-Dienstes auf genau eine Nachricht (A25b, S8 Abschnitt 6.2).
 *
 * <p><b>Hier steht die einzige Zuordnung von Statuscode zu Reaktion.</b> Sie an zwei Stellen
 * zu fuehren waere in diesem Meilenstein besonders teuer: Ein Fehler darin faellt nicht auf,
 * weil "keine Nachricht" der Normalzustand vieler Spieler ist - und im schlimmsten Fall
 * deaktiviert er Abonnements, die in Ordnung sind.
 *
 * @param status  HTTP-Status des Push-Dienstes; {@code 0} steht fuer "keine Antwort erhalten"
 *                (Netzfehler oder abgelaufene Gesamtfrist) und ist deshalb kein Statuscode,
 *                sondern eine Kennzeichnung fuer das Protokoll
 * @param ergebnis abgeleitete Reaktion
 */
public record PushAntwort(int status, PushErgebnis ergebnis) {

    /** Kennzeichen im Feld {@link #status} fuer einen Aufruf ohne Antwort. */
    public static final int OHNE_ANTWORT = 0;

    /**
     * Leitet die Reaktion aus dem HTTP-Status ab.
     *
     * <table>
     *   <caption>Die Zuordnung</caption>
     *   <tr><th>Antwort</th><th>Reaktion</th></tr>
     *   <tr><td>{@code 200}, {@code 201}</td><td>{@link PushErgebnis#ZUGESTELLT}</td></tr>
     *   <tr><td>{@code 404}, {@code 410}</td><td>{@link PushErgebnis#ERLOSCHEN}</td></tr>
     *   <tr><td>{@code 413}</td><td>{@link PushErgebnis#ANWENDUNGSFEHLER}</td></tr>
     *   <tr><td>{@code 401}, {@code 403}</td><td>{@link PushErgebnis#SCHLUESSEL_ABGELEHNT}</td></tr>
     *   <tr><td>alles Uebrige</td><td>{@link PushErgebnis#FEHLVERSUCH}</td></tr>
     * </table>
     *
     * <p><b>Der Auffangzweig ist {@code FEHLVERSUCH} und nicht {@code ERLOSCHEN}</b>, und das
     * ist die tragende Entscheidung dieser Methode: Ein unbekannter Status darf ein
     * funktionierendes Abonnement nicht wegwerfen. Fuenf Fehlversuche tun es am Ende doch -
     * aber erst, nachdem die Adresse fuenfmal nicht erreichbar war, und
     * {@code /push/abo/anlegen} heilt sie danach wieder.
     *
     * <p>{@code 200} steht neben {@code 201}, obwohl RFC 8030 {@code 201} vorschreibt: Einige
     * Dienste antworten mit {@code 200}, und ein erfolgreicher Versand als Fehlversuch
     * gebucht endete nach fuenf Nachrichten in einem deaktivierten Abonnement.
     *
     * @param status HTTP-Status der Antwort
     * @return Statuscode samt abgeleiteter Reaktion
     */
    public static PushAntwort von(int status) {
        PushErgebnis ergebnis = switch (status) {
            case 200, 201 -> PushErgebnis.ZUGESTELLT;
            case 404, 410 -> PushErgebnis.ERLOSCHEN;
            case 413 -> PushErgebnis.ANWENDUNGSFEHLER;
            case 401, 403 -> PushErgebnis.SCHLUESSEL_ABGELEHNT;
            default -> PushErgebnis.FEHLVERSUCH;
        };
        return new PushAntwort(status, ergebnis);
    }

    /**
     * Der Aufruf hat keine Antwort geliefert - Netzfehler, Zeitgrenze oder Abbruch nach
     * Ablauf der Gesamtfrist.
     *
     * <p><b>Ein abgebrochener Aufruf zaehlt als Fehlversuch</b>, und der Preis ist benannt:
     * Ein tatsaechlich zugestellter, nur langsam bestaetigter Versand wird dabei mitgezaehlt.
     * Bei einer Gesamtfrist von zehn Sekunden trifft das nur einen dauerhaft kranken Dienst -
     * und ihn weiterlaufen zu lassen waere schlechter: Er schriebe sein Ergebnis, nachdem der
     * Vorgang beendet ist.
     *
     * @return Fehlversuch ohne Statuscode
     */
    public static PushAntwort ohneAntwort() {
        return new PushAntwort(OHNE_ANTWORT, PushErgebnis.FEHLVERSUCH);
    }

    /**
     * Die Nachricht wurde <b>nicht</b> versendet, weil sie sich nicht aufbereiten liess - zu
     * gross oder nicht serialisierbar.
     *
     * <p>Derselbe Zustand wie ein {@code 413} des Dienstes, nur eine Stufe frueher erkannt -
     * und deshalb dieselbe Reaktion: Das Abonnement bleibt unangetastet.
     *
     * @return Anwendungsfehler ohne Statuscode
     */
    public static PushAntwort anwendungsfehler() {
        return new PushAntwort(OHNE_ANTWORT, PushErgebnis.ANWENDUNGSFEHLER);
    }
}
