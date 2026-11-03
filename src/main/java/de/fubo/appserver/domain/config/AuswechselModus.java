package de.fubo.appserver.domain.config;

/**
 * Wer bei ungerader Teilnehmerzahl <b>Springer</b> ist (A20b, Aenderung vom 03.11.2026).
 *
 * <h2>Was ein Springer ist</h2>
 * Er gehoert keinem Team fest an. Er wird <b>vor</b> der Einteilung bestimmt; die uebrigen
 * Teilnehmer werden zu zwei gleich grossen Teams ausgeglichen. Er beginnt beim schwaecheren
 * der beiden Teams und wechselt zum Gegner, sobald sein aktuelles Team ein Tor erzielt; trifft
 * das Team in Unterzahl, bleibt er. Den Wechsel auf dem Platz verfolgt der Server nicht - er
 * weist nur Springer und Startteam aus.
 *
 * <h2>Die Einstellung aendert die Einteilung</h2>
 * Anders als beim frueheren Auswechselspieler: Wer Springer ist, geht in keine Balance ein, und
 * die Teams werden ohne ihn gerechnet. Deshalb wird der Springer mit dem Lauf gespeichert
 * ({@code team_zuteilung.springer}, {@code V017}) und beim Lesen nicht neu bestimmt - ein neu
 * bestimmter passte nach einem Moduswechsel nicht mehr zu der Einteilung, die ohne ihn
 * entstanden ist.
 *
 * <p><b>Der Klassenname bleibt {@code AuswechselModus}</b>, weil er im Vertrag so heisst
 * ({@code fubo-api.json}, Schema {@code AuswechselModus}) und in {@code configs.app_config}
 * als {@code auswechsel_modus}. Ein Umbenennen waere fuer den Client brechend, ohne dass sich
 * an seiner Bedeutung etwas aenderte (Entscheidung vom 03.11.2026).
 *
 * <p>Die zulaessigen Werte sind zusaetzlich per CHECK-Constraint
 * {@code ck_app_config_auswechsel} in der Datenbank festgeschrieben - dieselbe Aufteilung wie
 * bei {@link AlgorithmType}.
 */
public enum AuswechselModus {

    /**
     * Vorgabe: ein zufaellig gezogener Teilnehmer der Aufstellung, Spieler wie Gast.
     *
     * <p>Gezogen wird aus der <b>Aufstellung</b> und nicht aus allen Zusagen: Wer in der
     * Warteschlange steht, spielt nicht und kann nicht springen. Der Seed des Laufs entscheidet;
     * gleich wahrscheinlich ist jeder Teilnehmer, auch der staerkste.
     */
    ZUFAELLIG,

    /**
     * Der Teilnehmer mit der spaetesten Zusage.
     *
     * <p>Bezugspunkt ist der Zeitpunkt der Rueckmeldung, nicht die Warteschlangenposition:
     * Wer aus der Warteschlange nachrueckt, hat frueher zugesagt als jemand, der sich danach
     * direkt angemeldet hat. Im manuellen Lauf (A24) gibt es keine Meldezeit; dort faellt die
     * Wahl auf {@link #ZUFAELLIG} zurueck, und die Antwort sagt es.
     */
    ZULETZT_ANGEMELDET
}
