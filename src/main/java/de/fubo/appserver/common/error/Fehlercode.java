package de.fubo.appserver.common.error;

import org.springframework.http.HttpStatus;

public enum Fehlercode {
    PIN_FALSCH(HttpStatus.UNAUTHORIZED, "Die PIN ist nicht korrekt."),
    PIN_GESPERRT(HttpStatus.TOO_MANY_REQUESTS, "Zu viele Fehlversuche. Bitte später erneut versuchen."),
    /**
     * Die Adminanmeldung wurde abgelehnt.
     *
     * <p><b>Der Code deckt zwei Endpunkte mit unterschiedlichem Anzeigetext ab.</b> Bei
     * {@code /admin/passwort/aendern} ist nur das alte Passwort im Spiel, dort gilt die
     * Standardmeldung. Bei {@code /auth/admin/anmelden} gehoert seit dem 29.08.2026 auch der
     * Anmeldename dazu; der Controller setzt deshalb eine eigene Meldung, die beide Angaben
     * nennt. Der Code bleibt derselbe - er soll nicht verraten, welche der beiden Angaben
     * nicht stimmte, sonst waere der Anmeldename ueber die Fehlermeldung erratbar.
     */
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

    /**
     * Der Datensatz wurde zwischenzeitlich von jemand anderem geaendert (S3, Abschnitt 5.2).
     *
     * <p><b>{@code 409} und nicht {@code 412}:</b> {@code 412 Precondition Failed} gehoert nach
     * RFC 9110 zu den bedingten Anfragen mit {@code If-Match} und einem ETag. Hier steht die
     * Version im Koerper, nicht in einem Header - ein {@code 412} behauptete eine Semantik, die
     * der Endpunkt nicht umsetzt.
     *
     * <p><b>Bewusst allgemein benannt</b> und nicht {@code KONFIG_VERALTET}: Termine (S4) und
     * Ergebnisse (S6) tragen dieselbe {@code version}-Spalte und werden ihn wiederverwenden.
     */
    DATEN_VERALTET(HttpStatus.CONFLICT,
            "Die Daten wurden zwischenzeitlich geändert. Bitte neu laden und erneut speichern."),
    /**
     * Zu diesem Zeitpunkt gibt es bereits einen Termin (S4, Abschnitt 3.3).
     *
     * <p><b>Die Bedingung ist global.</b> {@code uq_termin_zeit UNIQUE (datum, uhrzeit)}
     * laesst je Zeitpunkt genau einen Termin zu - auch an verschiedenen Orten. Der
     * bestehende Termin ist deshalb nicht unbedingt der, den der Admin gerade im Blick hat.
     *
     * <p>Ohne eigene Behandlung braechte der Constraint eine
     * {@code DataIntegrityViolationException} und daraus einen {@code 500}. Der Code steht
     * fuer den gleichen Fall an zwei Stellen: beim Anlegen entscheidet ihn die
     * {@code ON CONFLICT}-Klausel, beim Aendern eine vorgelagerte Abfrage - ein
     * {@code UPDATE} kennt kein {@code ON CONFLICT}.
     */
    TERMIN_BELEGT(HttpStatus.CONFLICT,
            "Zu diesem Zeitpunkt existiert bereits ein Termin."),

    /**
     * Der Termin laesst sich nicht entfernen, weil fachliche Daten auf ihn verweisen
     * (A19, S4 Paket 3).
     *
     * <p>Geprueft werden Teilnahmen, Generierungslaeufe, Kontingente und Ergebnisse. Alle
     * vier haengen mit {@code ON DELETE CASCADE} am Termin - ein {@code DELETE} raeumte sie
     * lautlos mit ab, und die Rueckmeldungen sind der einzige Beleg dafuer, wer zugesagt
     * hatte. Dasselbe Muster wie bei {@link #PROFIL_IN_VERWENDUNG}: Wo geloescht nicht mehr
     * geht, ist Absagen der richtige Weg.
     */
    TERMIN_IN_VERWENDUNG(HttpStatus.CONFLICT,
            "Für diesen Termin liegen bereits Rückmeldungen oder Ergebnisse vor. "
                    + "Er lässt sich stattdessen absagen."),

    /**
     * Der Termin ist nicht mehr offen (S4, Abschnitte 3.4 und 5.2).
     *
     * <p><b>Bewusst allgemein benannt und allgemein formuliert.</b> Der Code deckt jeden
     * Fall ab, in dem ein Termin keine Aenderung mehr annimmt: Er ist abgesagt, er ist
     * abgeschlossen, oder - ab S4-Paket 5 - sein Beginn liegt zurueck. Fuer den Aufrufer ist
     * die Wirkung dieselbe, und der Status steht ohnehin in der Terminliste. Dieselbe
     * Ueberlegung wie bei {@link #RESET_UNGUELTIG}, das vier Faelle buendelt.
     *
     * <p>Wo eine genauere Meldung hilft, setzt der Aufrufer sie ueber den zweiten
     * Konstruktor von {@code FachlicherFehler} - so wie {@link #ADMIN_PASSWORT_FALSCH} an
     * zwei Endpunkten mit unterschiedlichem Anzeigetext auftritt. Der Code bleibt derselbe;
     * {@code detail} ist Anzeigetext und darf sich ohne Vertragsaenderung aendern.
     */
    TERMIN_GESCHLOSSEN(HttpStatus.CONFLICT,
            "Dieser Termin ist nicht mehr offen."),

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
