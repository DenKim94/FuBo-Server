package de.fubo.appserver.common.error;

import org.springframework.http.HttpStatus;

public enum Fehlercode {
    PIN_FALSCH(HttpStatus.UNAUTHORIZED, "Die PIN ist nicht korrekt."),
    PIN_GESPERRT(HttpStatus.TOO_MANY_REQUESTS, "Zu viele Fehlversuche. Bitte später erneut versuchen."),
    ADMIN_PASSWORT_FALSCH(HttpStatus.UNAUTHORIZED, "Das Admin-Passwort ist nicht korrekt."),

    /**
     * Die eingegebene Bestaetigungs-PIN stimmt nicht (S2b). Der Versuch ist verbraucht;
     * nach der letzten Wiederholung antwortet der Endpunkt mit {@link #RESET_UNGUELTIG}.
     */
    RESET_PIN_FALSCH(HttpStatus.UNAUTHORIZED, "Die Bestätigungs-PIN ist nicht korrekt."),

    /**
     * Es gibt keinen brauchbaren Reset-Vorgang: nie angefordert, abgelaufen, bereits
     * verbraucht oder die Versuche sind erschoepft.
     *
     * <p><b>Warum {@code 409} und nicht {@code 401}:</b> Fuer das Frontend ist das der
     * Unterschied zwischen "nochmal versuchen" und "neu anfordern". Beides mit {@code 401}
     * zu beantworten zwaenge es zum Raten oder zum Auswerten des Meldungstexts - und der
     * ist Anzeigetext.
     *
     * <p>Die vier Faelle werden bewusst nicht unterschieden: Fuer den Aufrufer ist die
     * Folge dieselbe, und eine feinere Auskunft verriete den Zustand des Vorgangs.
     */
    RESET_UNGUELTIG(HttpStatus.CONFLICT,
            "Es liegt kein gültiger Vorgang zum Zurücksetzen vor. Bitte neu anfordern."),

    /**
     * Zu viele Reset-Anforderungen von derselben Adresse (S2b).
     *
     * <p>Ein eigener Code neben {@link #PIN_GESPERRT}, weil dahinter ein anderer Mechanismus
     * steht: Der Brute-Force-Zaehler zaehlt <i>Fehlversuche</i>, hier wird eine
     * <i>erfolgreiche</i> Handlung begrenzt.
     */
    RESET_GEDROSSELT(HttpStatus.TOO_MANY_REQUESTS,
            "Zu viele Anforderungen. Bitte später erneut versuchen."),

    /**
     * Der Versand der Bestaetigungs-PIN ist fehlgeschlagen (S2b).
     *
     * <p>{@code 503} und nicht {@code 500}: Die Anwendung ist in Ordnung, ein nachgelagerter
     * Dienst ist es nicht - der Aufruf laesst sich unveraendert wiederholen. Es bleibt kein
     * Vorgang zurueck; der Versand laeuft innerhalb der Transaktion und rollt mit ihr zurueck.
     */
    VERSAND_FEHLGESCHLAGEN(HttpStatus.SERVICE_UNAVAILABLE,
            "Die Nachricht konnte nicht versendet werden. Bitte später erneut versuchen."),

    SESSION_UNGUELTIG(HttpStatus.UNAUTHORIZED, "Die Sitzung ist abgelaufen."),
    KEINE_BERECHTIGUNG(HttpStatus.FORBIDDEN, "Für diese Aktion fehlt die Berechtigung."),
    NAME_BELEGT(HttpStatus.CONFLICT, "Dieser Name ist bereits angemeldet."),

    /**
     * Das Adminprofil laesst sich weder entfernen noch sperren (S2b, Abschnitt 8).
     *
     * <p>{@code admin_konto.spieler_id} verweist darauf, und ohne Adminprofil kaeme niemand
     * mehr in den Adminbereich - der Admin wuerde sich mit einem einzigen Aufruf selbst
     * aussperren.
     */
    PROFIL_GESCHUETZT(HttpStatus.CONFLICT,
            "Das Adminprofil ist geschützt und kann weder entfernt noch gesperrt werden."),

    /**
     * Das Profil ist noch in fachlichen Daten in Gebrauch und kann deshalb nicht geloescht
     * werden (S2b, Abschnitt 8).
     *
     * <p>Teilnahmen, Terminserien, Generierungslaeufe, Ergebnisse und Audit-Eintraege
     * verweisen ohne {@code ON DELETE} auf {@code profil.spieler}. Ein Loeschen vernichtete
     * Belege, auf die sich andere Datensaetze berufen; {@code blockieren} ist dann der
     * richtige Weg.
     */
    PROFIL_IN_VERWENDUNG(HttpStatus.CONFLICT,
            "Das Profil wird bereits verwendet und kann nicht entfernt werden. Es lässt sich stattdessen blockieren."),
    KEIN_GAST_SLOT_FREI(HttpStatus.CONFLICT, "Es sind bereits alle Gastplätze belegt."),
    EINGABE_UNGUELTIG(HttpStatus.BAD_REQUEST, "Ungültige Eingabedaten."),
    INTERNER_FEHLER(HttpStatus.INTERNAL_SERVER_ERROR, "Ein unerwarteter Fehler ist aufgetreten."),
    INHALT_NICHT_GEFUNDEN(HttpStatus.NOT_FOUND, "Der gesuchte Inhalt wurde nicht gefunden.");

    private final HttpStatus status;
    private final String standardMeldung;

    Fehlercode(HttpStatus status, String standardMeldung) {
        this.status = status;
        this.standardMeldung = standardMeldung;
    }

    public HttpStatus getStatus() { return status; }
    public String getStandardMeldung() { return standardMeldung; }
}
