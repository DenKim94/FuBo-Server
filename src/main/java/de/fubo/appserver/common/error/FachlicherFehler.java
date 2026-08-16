package de.fubo.appserver.common.error;

/**
 * Erwarteter fachlicher Fehlerfall, der als definierte Antwort beim Client landet.
 */
public class FachlicherFehler extends RuntimeException {
    private final Fehlercode code;

    /** Verwendet die Standardmeldung des Codes. */
    public FachlicherFehler(Fehlercode code) {
        this(code, code.getStandardMeldung());
    }

    /** Fuer Faelle, die eine praezisere Meldung brauchen (etwa eine Restwartezeit). */
    public FachlicherFehler(Fehlercode code, String meldung) {
        super(meldung);
        this.code = code;
    }

    public Fehlercode getCode() { return code; }
}
