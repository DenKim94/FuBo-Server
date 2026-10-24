package de.fubo.appserver.domain.spieltag;

/**
 * Ausgang der Absage eines Gastes durch den Admin (Ergaenzung vom 24.10.2026).
 *
 * <p>Drei Zustaende statt eines Wahrheitswerts, weil der Dienst drei verschiedene Dinge tun
 * muss: bei {@link #KEINE_TEILNAHME} ablehnen ({@code 404}), bei {@link #BEREITS_ABGESAGT}
 * nichts aendern und trotzdem {@code 204} melden, bei {@link #ABGESAGT} Zaehler und Protokoll
 * fortschreiben. Mit einem {@code boolean} liessen sich die ersten beiden nicht trennen.
 */
public enum GastAbsageErgebnis {

    /** Fuer diesen Termin gibt es keine Teilnahme mit diesem Gastnamen. */
    KEINE_TEILNAHME,

    /** Die Teilnahme war schon eine Absage; es wurde nichts geaendert. */
    BEREITS_ABGESAGT,

    /** Die Zusage wurde zurueckgenommen. */
    ABGESAGT
}
