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
    TERMIN_ABGESAGT,

    /**
     * Testbenachrichtigung des Probeversands ({@code POST /admin/push/test}).
     *
     * <h2>Kein dritter Versandanlass</h2>
     * A25b schliesst weitere <i>Anlaesse</i> aus, und daran aendert dieser Wert nichts: Der
     * Probeversand feuert nicht von selbst, er geht ausschliesslich an die <b>eigenen</b>
     * Geraete des Admins, und er entsteht nur, weil der Admin ihn ausdruecklich anfordert.
     *
     * <h2>Warum ein eigener Wert und nicht {@link #ERINNERUNG}</h2>
     * <b>Weil die Nachricht sonst luegt.</b> Eine Testnachricht mit dem Titel "Training am
     * Donnerstag, 19:45 Uhr" und dem Text "Bitte um Rückmeldung" waere auf dem Sperrbildschirm
     * von einer echten Erinnerung nicht zu unterscheiden - fuer einen Termin, den es nicht
     * gibt. Der Service Worker koennte sie ausserdem zu Recht als Erinnerung behandeln und
     * beim Klick auf eine Terminseite fuehren, die zu {@code null} gehoert.
     *
     * <p><b>Die Felder {@code terminId}, {@code datum} und {@code uhrzeit} bleiben deshalb
     * leer</b> - der einzige Fall, in dem sie es sind. Ein Service Worker, der diesen Wert
     * nicht kennt, zeigt den Rueckfalltext; genau dafuer ist er da.
     */
    PROBE
}
