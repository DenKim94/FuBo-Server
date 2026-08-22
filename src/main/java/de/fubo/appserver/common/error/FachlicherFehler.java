package de.fubo.appserver.common.error;

/**
 * Erwarteter fachlicher Fehlerfall, der als definierte Antwort beim Client landet.
 */
public class FachlicherFehler extends RuntimeException {

    private final Fehlercode code;

    /**
     * Restwartezeit in Sekunden oder {@code null}, wenn keine gilt.
     *
     * <p>Nur die Drosselung des PIN-Endpunkts (429) fuellt diesen Wert. Er wird vom
     * {@link GlobalExceptionHandler} in den {@code Retry-After}-Header und in das Feld
     * {@code wartesekunden} des Problem-Details uebersetzt. Vorher stand die Wartezeit
     * ausschliesslich im deutschen Meldungstext - das Frontend haette ihn parsen muessen,
     * und eine Aenderung am Wortlaut haette den Vertrag stillschweigend gebrochen.
     */
    private final Long wartesekunden;

    /** Verwendet die Standardmeldung des Codes. */
    public FachlicherFehler(Fehlercode code) {
        this(code, code.getStandardMeldung());
    }

    /** Fuer Faelle, die eine praezisere Meldung brauchen. */
    public FachlicherFehler(Fehlercode code, String meldung) {
        this(code, meldung, null);
    }

    /**
     * Fuer Faelle mit einer Restwartezeit.
     *
     * @param code          maschinenlesbarer Fehlercode
     * @param meldung       Anzeigetext fuer den Nutzer
     * @param wartesekunden Sekunden bis zum naechsten zulaessigen Versuch; {@code null},
     *                      wenn der Fehler nicht zeitgebunden ist
     */
    public FachlicherFehler(Fehlercode code, String meldung, Long wartesekunden) {
        super(meldung);
        this.code = code;
        this.wartesekunden = wartesekunden;
    }

    public Fehlercode getCode() {
        return code;
    }

    /** @return Restwartezeit in Sekunden oder {@code null} */
    public Long getWartesekunden() {
        return wartesekunden;
    }
}
