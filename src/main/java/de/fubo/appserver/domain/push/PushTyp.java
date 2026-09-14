package de.fubo.appserver.domain.push;

/**
 * Anlass einer Push-Benachrichtigung (A25b, S8).
 *
 * <h2>Zwei Werte, und es werden bewusst keine weiteren</h2>
 * A25b nennt genau zwei Versandanlaesse und schliesst weitere ausdruecklich aus: "Die
 * Teameinteilung liegt vor" und "die Mindestanzahl ist erreicht" feuern mehrfach je Termin,
 * und der Empfaenger entzieht dann die Berechtigung im Browser. <b>Danach erreicht ihn auch
 * die Absage nicht mehr</b> - ein zusaetzlicher Anlass kostet also die beiden, die es gibt.
 *
 * <p><b>Der Wert reist in der Nutzlast mit und wird vom Service Worker ausgewertet</b>, aber
 * er erscheint <i>nicht</i> im Vertrag {@code fubo-api.json}: Die Nutzlast verlaesst den
 * Server nicht ueber die REST-Schnittstelle, sondern als verschluesselter Rumpf an einen
 * fremden Push-Dienst.
 *
 * <p><b>Ein Service Worker kann einen spaeter eingefuehrten Wert nicht kennen.</b> Der Client
 * meldet sich mit {@code registerType: 'prompt'} an, ein Nutzer kann das Update also tagelang
 * aufschieben. Deshalb traegt {@link PushNutzlast} immer auch einen fertigen Rueckfalltext -
 * ein unbekannter {@code typ} darf nie dazu fuehren, dass nichts angezeigt wird.
 */
public enum PushTyp {

    /** Erinnerung an eine offene Rueckmeldung (Anlass 1). */
    ERINNERUNG,

    /** Der Termin wurde abgesagt (Anlass 2). */
    TERMIN_ABGESAGT
}
