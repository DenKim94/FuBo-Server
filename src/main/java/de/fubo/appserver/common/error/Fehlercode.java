package de.fubo.appserver.common.error;

import org.springframework.http.HttpStatus;

public enum Fehlercode {
    PIN_FALSCH(HttpStatus.UNAUTHORIZED, "Die PIN ist nicht korrekt."),
    PIN_GESPERRT(HttpStatus.TOO_MANY_REQUESTS, "Zu viele Fehlversuche. Bitte später erneut versuchen."),
    SESSION_UNGUELTIG(HttpStatus.UNAUTHORIZED, "Die Sitzung ist abgelaufen."),
    KEINE_BERECHTIGUNG(HttpStatus.FORBIDDEN, "Für diese Aktion fehlt die Berechtigung."),
    NAME_BELEGT(HttpStatus.CONFLICT, "Dieser Name ist bereits angemeldet."),
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
