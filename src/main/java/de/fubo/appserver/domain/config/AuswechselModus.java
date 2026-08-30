package de.fubo.appserver.domain.config;

/**
 * Wer bei ungerader Teilnehmerzahl als Auswechselspieler gefuehrt wird (A20b).
 *
 * <p><b>Die Einstellung aendert die Einteilung nicht.</b> A20a bleibt unberuehrt: Die
 * Teamgroessen duerfen sich weiterhin um hoechstens eins unterscheiden, und der Ausgleich
 * laeuft ueber die Teamstaerke. Gewaehlt wird hier allein, wen das groessere Team als
 * Auswechselspieler ausweist - eine Anzeige im Userdashboard, keine zweite Zielfunktion.
 *
 * <p><b>Noch ohne Abnehmer.</b> Der Wert laesst sich seit dem 30.08.2026 setzen und lesen;
 * ausgewertet wird er erst vom Teamgenerator in S5. Das Feld entsteht trotzdem jetzt, weil es
 * anwendungsweite Konfiguration ist und der Vertrag fuer den Client-Track sonst ein zweites Mal
 * brechend waechst.
 *
 * <p>Die zulaessigen Werte sind zusaetzlich per CHECK-Constraint
 * {@code ck_app_config_auswechsel} in der Datenbank festgeschrieben - dieselbe Aufteilung wie
 * bei {@link AlgorithmType}.
 */
public enum AuswechselModus {

    /**
     * Vorgabe nach A20b: der schwaechste Spieler des groesseren Teams.
     *
     * <p>"Schwaechster" meint die Gesamtstaerke aus dem Skill-Snapshot des Laufs, nicht den
     * aktuellen Stand der Profile - sonst aenderte sich der Auswechselspieler nachtraeglich,
     * wenn der Admin einen Skillwert korrigiert.
     */
    SCHWAECHSTER_UEBERZAHL,

    /**
     * Der Teilnehmer mit der spaetesten Zusage.
     *
     * <p>Bezugspunkt ist der Zeitpunkt der Rueckmeldung, nicht die Warteschlangenposition:
     * Wer aus der Warteschlange nachrueckt, hat frueher zugesagt als jemand, der sich danach
     * direkt angemeldet hat.
     */
    ZULETZT_ANGEMELDET
}
