package de.fubo.appserver.repository.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Zugriff auf {@code profil.gast_slot} - die feste Obergrenze gleichzeitig angemeldeter
 * Gaeste (A17).
 *
 * <p><b>Warum kein Spring-Data-Repository und keine Entity?</b> Auf der Tabelle finden
 * ausschliesslich bedingte Massen-Updates statt; ein einzelner Datensatz wird nie geladen,
 * geaendert und zurueckgeschrieben. Eine Entity mit {@code @Version} braechte hier sogar
 * einen Nachteil: Optimistic Locking meldet den Konflikt erst beim Schreiben und
 * verlangt eine Wiederholung, waehrend das bedingte {@code UPDATE} den Wettlauf ohne
 * Wiederholung entscheidet. Vorbild ist {@code AuditLogRepository}, das aus demselben
 * Grund direkt {@code JdbcClient} nutzt.
 */
@Repository
public class GastSlotRepository {

    /**
     * Belegt den ersten freien Platz.
     *
     * <p><b>Die Unterabfrage und die Bedingung {@code AND NOT belegt} sind beide
     * notwendig.</b> Die Unterabfrage waehlt den Kandidaten aus, die Bedingung entscheidet
     * den Wettlauf: Belegt ein zweiter Aufruf denselben Platz zwischen Auswahl und
     * Schreibzugriff, wertet PostgreSQL die WHERE-Klausel unter der Zeilensperre erneut aus
     * und aendert null Zeilen. Eine vorherige Zaehlabfrage ({@code SELECT count(*) …})
     * waere genau die klassische Wettlaufsituation.
     *
     * <p>{@code id <= :maxGaeste} setzt die Admin-Konfiguration
     * ({@code configs.app_config.anz_guests}) um, ohne Datensaetze anzulegen oder zu
     * loeschen: Die Plaetze sind fest, wirksam sind nur die ersten {@code anz_guests}.
     * Fehlende Zeilen legt seit S3 {@link #plaetzeSicherstellen(int)} an, in derselben
     * Transaktion wie die Konfigurationsaenderung - vorher blieb eine Erhoehung ueber die
     * Zahl der vorhandenen Zeilen hinaus wirkungslos.
     *
     * <p>{@code version = version + 1} wird von Hand fortgeschrieben, weil kein Hibernate
     * beteiligt ist. Ohne das bliebe die Spalte stehen und eine spaetere Entity auf
     * derselben Tabelle saehe unveraenderte Versionen.
     */
    private static final String SQL_BELEGEN = """
            UPDATE profil.gast_slot
               SET belegt      = TRUE,
                   session_id  = :sessionId,
                   belegt_seit = now(),
                   version     = version + 1
             WHERE id = (SELECT id
                           FROM profil.gast_slot
                          WHERE NOT belegt
                            AND id <= :maxGaeste
                          ORDER BY id
                          LIMIT 1)
               AND NOT belegt
            """;

    /**
     * Gibt den Platz einer bestimmten Sitzung frei (Logout).
     *
     * <p>Ohne diesen Schritt liefen die Plaetze ueber die Zeit voll: Eine abgemeldete
     * Gastsitzung haelt ihren Platz sonst bis zum Aufraeumlauf.
     */
    private static final String SQL_FREIGEBEN_FUER_SITZUNG = """
            UPDATE profil.gast_slot
               SET belegt      = FALSE,
                   session_id  = NULL,
                   belegt_seit = NULL,
                   version     = version + 1
             WHERE session_id = :sessionId
            """;

    /**
     * Gibt Plaetze frei, deren Sitzung abgelaufen oder widerrufen ist.
     *
     * <p>Zwei Aufgaben in einem Statement. Erstens die Korrektur des Falls, dass eine
     * Gastsitzung ohne Logout einfach ablaeuft. Zweitens - und das ist die harte
     * Voraussetzung - muss dieser Schritt <b>vor</b> dem Loeschen abgelaufener Sitzungen
     * laufen: {@code fk_gast_slot_session} hat kein {@code ON DELETE}, ein {@code DELETE}
     * auf einer noch referenzierten Sitzung scheitert also mit einer
     * Fremdschluesselverletzung und der gesamte Aufraeumlauf bricht ab.
     */
    private static final String SQL_FREIGEBEN_ABGELAUFENE = """
            UPDATE profil.gast_slot g
               SET belegt      = FALSE,
                   session_id  = NULL,
                   belegt_seit = NULL,
                   version     = version + 1
             WHERE g.session_id IS NOT NULL
               AND EXISTS (SELECT 1
                             FROM profil.session s
                            WHERE s.id = g.session_id
                              AND (s.widerrufen_am IS NOT NULL
                                   OR s.gueltig_bis <= now()
                                   OR s.absolut_gueltig_bis <= now()))
            """;

    /** Zaehlt die wirksamen Plaetze; nur fuer Protokollausgaben und Tests gedacht. */
    private static final String SQL_FREIE_ZAEHLEN = """
            SELECT count(*)
              FROM profil.gast_slot
             WHERE NOT belegt
               AND id <= :maxGaeste
            """;

    /**
     * Legt die Plaetze 1 bis {@code anzahl} an, soweit sie fehlen (S3, Abschnitt 6).
     *
     * <p><b>Warum es das braucht:</b> {@code configs.app_config.anz_guests} wirkt ueber
     * {@code id <= :maxGaeste}. Das erledigt das <i>Senken</i> vollstaendig - fuers <i>Erhoehen</i>
     * fehlte bis S3 der Rest: {@code V007} legt genau vier Zeilen an, eine Einstellung auf 6 blieb
     * deshalb wirkungslos. Der Admin stellte 6 ein, das Formular meldete Erfolg, und der fuenfte
     * Gast bekam {@code 409 KEIN_GAST_SLOT_FREI}.
     *
     * <p>{@code generate_series} statt einer Schleife in Java: eine Anweisung, eine Transaktion,
     * kein Wettlauf zwischen Pruefen und Anlegen. {@code ON CONFLICT (id) DO NOTHING} macht den
     * Aufruf wiederholbar und laesst bestehende Plaetze - auch belegte - unangetastet; der
     * Konfigurations-Endpunkt ruft ihn deshalb bei jedem Speichern auf, ohne vorher zu vergleichen.
     *
     * <p>{@code anzeige_name} wird mitgeschrieben, weil die Spalte {@code NOT NULL} ist. Das Muster
     * "Gast n" setzt {@code V007} fort; der Name ist ein Vorschlag, den der Gast beim Anmelden
     * ueberschreibt.
     */
    private static final String SQL_PLAETZE_SICHERSTELLEN = """
            INSERT INTO profil.gast_slot (id, anzeige_name)
            SELECT n::smallint, 'Gast ' || n
              FROM generate_series(1, :anzahl) AS n
            ON CONFLICT (id) DO NOTHING
            """;

    /**
     * Zaehlt belegte Plaetze <b>oberhalb</b> der wirksamen Grenze.
     *
     * <p>Nur fuer Protokollausgaben. Senkt der Admin die Zahl, waehrend hoehere Plaetze besetzt
     * sind, bleiben diese Gaeste angemeldet, bis ihre Sitzung endet - die Zaehlung geht dann
     * voruebergehend nicht auf, und ohne Log-Zeile raetselt der Betrieb daran.
     */
    private static final String SQL_BELEGTE_OBERHALB = """
            SELECT count(*)
              FROM profil.gast_slot
             WHERE belegt
               AND id > :maxGaeste
            """;

    private final JdbcClient jdbc;

    public GastSlotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Belegt den ersten freien Gastplatz per bedingtem UPDATE.
     *
     * @param sessionId Sitzung, die den Platz belegt
     * @param maxGaeste hoechste wirksame Platznummer aus {@code configs.app_config.anz_guests}
     * @return Anzahl belegter Plaetze: 1 bei Erfolg, 0 wenn alle belegt sind
     */
    public int freienSlotBelegen(Long sessionId, int maxGaeste) {
        return jdbc.sql(SQL_BELEGEN)
                .param("sessionId", sessionId)
                .param("maxGaeste", maxGaeste)
                .update();
    }

    /**
     * Gibt den Platz einer Sitzung frei.
     *
     * @return Anzahl freigegebener Plaetze; 0, wenn die Sitzung keinen Platz hielt
     */
    public int freigebenFuerSitzung(Long sessionId) {
        return jdbc.sql(SQL_FREIGEBEN_FUER_SITZUNG)
                .param("sessionId", sessionId)
                .update();
    }

    /**
     * Gibt alle Plaetze abgelaufener oder widerrufener Sitzungen frei.
     *
     * @return Anzahl freigegebener Plaetze
     */
    public int freigebenFuerAbgelaufeneSitzungen() {
        return jdbc.sql(SQL_FREIGEBEN_ABGELAUFENE).update();
    }

    /**
     * Zaehlt die freien Plaetze innerhalb der konfigurierten Obergrenze.
     *
     * <p><b>Nicht zur Belegungsentscheidung verwenden</b> - dafuer ist ausschliesslich
     * {@link #freienSlotBelegen(Long, int)} zustaendig. Zwischen Zaehlen und Schreiben
     * liegt sonst ein Fenster, in dem ein zweiter Aufruf denselben Platz belegt.
     */
    public int freieSlotsZaehlen(int maxGaeste) {
        return jdbc.sql(SQL_FREIE_ZAEHLEN)
                .param("maxGaeste", maxGaeste)
                .query(Integer.class)
                .single();
    }

    /**
     * Stellt sicher, dass die Plaetze 1 bis {@code anzahl} existieren.
     *
     * <p><b>Gehoert in dieselbe Transaktion wie die Konfigurationsaenderung.</b> Scheitert das
     * Anlegen, darf auch {@code anz_guests} nicht steigen - sonst stuende in der Konfiguration
     * eine Zahl, die die Anwendung nicht einloesen kann.
     *
     * <p>Bestehende Plaetze bleiben unveraendert, auch belegte. {@code anzahl = 0} legt nichts an:
     * {@code generate_series(1, 0)} liefert keine Zeile.
     *
     * @param anzahl hoechste Platznummer, die es geben soll (aus {@code anz_guests})
     * @return Anzahl neu angelegter Plaetze; 0, wenn schon alle vorhanden waren
     */
    public int plaetzeSicherstellen(int anzahl) {
        return jdbc.sql(SQL_PLAETZE_SICHERSTELLEN)
                .param("anzahl", anzahl)
                .update();
    }

    /**
     * Zaehlt die belegten Plaetze jenseits der wirksamen Obergrenze.
     *
     * <p><b>Nur zur Protokollierung.</b> Die Zahl beschreibt einen Uebergangszustand nach einer
     * Senkung von {@code anz_guests} und ist bereits ueberholt, wenn eine dieser Sitzungen endet.
     * Fuer eine Belegungsentscheidung ist ausschliesslich {@link #freienSlotBelegen(Long, int)}
     * zustaendig.
     *
     * @param maxGaeste neue wirksame Obergrenze
     * @return Anzahl belegter Plaetze mit einer Nummer groesser als {@code maxGaeste}
     */
    public int belegteOberhalbZaehlen(int maxGaeste) {
        return jdbc.sql(SQL_BELEGTE_OBERHALB)
                .param("maxGaeste", maxGaeste)
                .query(Integer.class)
                .single();
    }
}
