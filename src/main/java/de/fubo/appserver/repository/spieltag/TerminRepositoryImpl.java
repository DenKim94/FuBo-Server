package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.spieltag.TerminEintrag;
import de.fubo.appserver.domain.spieltag.TerminStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-Teil des {@link TerminRepository} (S4, Abschnitte 2 bis 4).
 *
 * <p><b>Der Klassenname ist nicht frei waehlbar:</b> Spring Data findet die Implementierung
 * ausschliesslich ueber die Konvention "Name des Repository-Interface + Impl". Eine Klasse
 * mit anderem Namen wird stillschweigend ignoriert - das Repository laesst sich dann zwar
 * erzeugen, der Aufruf scheitert aber zur Laufzeit.
 */
class TerminRepositoryImpl implements TerminRepositoryCustom {

    /**
     * Gemeinsamer Rumpf beider Leseabfragen; die {@code WHERE}-Bedingung kommt je Aufruf dazu.
     *
     * <h2>Warum eine Aggregation und kein zweiter Aufruf je Termin</h2>
     * Die Liste braucht je Termin drei Dinge aus zwei Tabellen: die Stammdaten, die Zahl der
     * Zusagen und die eigene Rueckmeldung. Ein {@code JOIN} ohne Aggregation lieferte je
     * Teilnahme eine Zeile - der Termin erschiene dann so oft, wie er Rueckmeldungen hat.
     * Ein zweiter Aufruf je Termin waere ein N+1-Problem.
     *
     * <h2>{@code count(*) FILTER (WHERE tn.zusage)} statt {@code count(tn.id)}</h2>
     * Sonst zaehlte die Abfrage die Absagen mit. Der {@code FILTER}-Zusatz ist Standard-SQL
     * und hier lesbarer als ein {@code sum(CASE ...)}.
     *
     * <h2>{@code bool_or(...) FILTER (...)} fuer die eigene Rueckmeldung</h2>
     * <b>Abweichung von der Anleitung</b> (Abschnitt 2.3), die {@code max(CASE ... THEN
     * tn.zusage::int END)} vorschlaegt. Die Ueberlegung dahinter ist dieselbe und traegt
     * beides: Je Termin passt hoechstens eine Zeile auf den Aufrufer
     * ({@code uq_teilnahme_spieler} beziehungsweise {@code uq_teilnahme_gast}), die
     * Aggregation waehlt also aus null oder einer Zeile. {@code bool_or} spart dabei die
     * Umwandlung nach {@code int} und zurueck - der Wert kommt als {@code Boolean} an, und
     * {@code null} bedeutet weiterhin "noch nicht gemeldet". Genau diese Unterscheidung ist
     * der Zweck: {@code null} ist nicht {@code false}, das Frontend zeigt drei Zustaende.
     *
     * <h2>Der Vergleich mit {@code null} ist beabsichtigt</h2>
     * {@code :gastName} ist nur bei einer Gastsitzung gesetzt, {@code :spielerId} nur bei
     * einer Spielersitzung. Der jeweils andere Vergleich lautet dann {@code spalte = NULL}
     * und ergibt {@code UNKNOWN} - die Bedingung trifft also nie. Das spart einen zweiten
     * Abfragezweig. <b>Wer {@code NULL} hier fuer "egal" haelt, dreht die Bedingung spaeter
     * versehentlich um</b> und liefert jedem Aufrufer die Rueckmeldung eines Fremden.
     *
     * <h2>{@code GROUP BY t.id}</h2>
     * Genuegt, weil {@code t.id} der Primaerschluessel ist: PostgreSQL leitet die uebrigen
     * Spalten von {@code termin} daraus funktional ab.
     */
    private static final String SQL_RUMPF = """
            SELECT t.id,
                   t.serie_id,
                   t.datum,
                   t.uhrzeit,
                   t.ort,
                   t.status,
                   t.teilnehmer_version,
                   t.version,
                   count(*) FILTER (WHERE tn.zusage)              AS zusagen,
                   bool_or(tn.zusage) FILTER (WHERE tn.spieler_id = :spielerId
                                                 OR tn.gast_name  = :gastName)
                                                                  AS eigene_rueckmeldung
              FROM spieltag.termin t
              LEFT JOIN spieltag.teilnahme tn ON tn.termin_id = t.id
            """;

    /**
     * Die Uebersicht: alles ab dem Stichtag, in zeitlicher Reihenfolge.
     *
     * <p><b>Abgesagte Termine bleiben enthalten.</b> Der Spieler soll sehen, dass sein
     * Training ausfaellt, statt einen Termin vorzufinden, der spurlos verschwunden ist;
     * {@code status} unterscheidet sie. Eine Filterung waere ausserdem eine Entscheidung
     * ueber die Anzeige, und die gehoert ins Frontend.
     */
    private static final String SQL_UEBERSICHT = SQL_RUMPF + """
             WHERE t.datum >= :ab
             GROUP BY t.id
             ORDER BY t.datum, t.uhrzeit
            """;

    /** Die Einzelansicht: derselbe Rumpf, nur auf eine Id eingegrenzt. */
    private static final String SQL_EINZELN = SQL_RUMPF + """
             WHERE t.id = :terminId
             GROUP BY t.id
            """;

    /**
     * Legt einen Termin an - oder nichts, wenn der Zeitpunkt belegt ist.
     *
     * <p>{@code ON CONFLICT ON CONSTRAINT} und nicht {@code ON CONFLICT (datum, uhrzeit)}:
     * Der benannte Constraint macht sichtbar, welche Bedingung gemeint ist, und bricht
     * auffaellig, falls sie einmal umbenannt wird. Dasselbe Muster wie bei
     * {@code SpielerRepository#nullwerteAnlegen}.
     *
     * <p>{@code status}, {@code teilnehmer_version}, {@code teams_fixiert} und {@code version}
     * bleiben ungenannt und ziehen ihre Vorgabewerte aus {@code V005}. Das ist hier richtig
     * und nicht zufaellig: Ein neuer Termin ist immer {@code GEPLANT}, hat noch keine
     * Teilnehmer und wurde noch nie geschrieben.
     */
    private static final String SQL_EINFUEGEN = """
            INSERT INTO spieltag.termin (serie_id, datum, uhrzeit, ort)
            VALUES (:serieId, :datum, :uhrzeit, :ort)
            ON CONFLICT ON CONSTRAINT uq_termin_zeit DO NOTHING
            RETURNING id
            """;

    /**
     * Erhoeht beide Zaehler eines Termins.
     *
     * <p><b>{@code version} wird mit hochgezaehlt</b>, obwohl es die Spalte des Optimistic
     * Locking ist: Die Regel aus {@code AGENT_SERVER.md} verlangt das fuer jede
     * {@code version}-Spalte, die per SQL geaendert wird. Wer den Termin gerade zum
     * Bearbeiten offen hat, laeuft danach in {@code 409 DATEN_VERALTET} - und das ist
     * richtig, denn der Teilnehmerkreis ist ein anderer geworden.
     *
     * <p><b>Deshalb darf im selben Vorgang keine verwaltete {@code Termin}-Entity geladen
     * sein.</b> Ihre Version im Speicher waere danach veraltet, und der naechste Flush
     * scheiterte an einem Sperrkonflikt, den niemand verursacht hat. Die Rueckmeldung liest
     * den Termin deshalb ueber {@link #SQL_EINZELN} und nicht ueber {@code findById}.
     */
    private static final String SQL_TEILNEHMER_VERSION = """
            UPDATE spieltag.termin
               SET teilnehmer_version = teilnehmer_version + 1,
                   version            = version + 1
             WHERE id = :terminId
            """;

    /**
     * Der Nachtrag aus S3: Eine Skillaenderung zaehlt als Teilnehmeraenderung (A15).
     *
     * <p><b>Der Zeitpunkt kommt als Parameter, nicht aus {@code current_date} und
     * {@code current_time}.</b> Die Anleitung schlaegt die beiden Datenbankfunktionen vor;
     * sie richten sich nach der Zeitzone der <i>Datenbanksitzung</i>, und die steht in einem
     * Container auf UTC. Damit haetten Anwendung und Datenbank zwei verschiedene
     * Vorstellungen davon, was "kuenftig" heisst - im Sommer zwei Stunden auseinander. Der
     * Parameter kommt aus derselben {@code Clock}-Bean wie alle uebrigen Zeitvergleiche.
     *
     * <p>{@code datum + uhrzeit} ergibt in PostgreSQL einen {@code timestamp} ohne Zeitzone -
     * genau der Typ, als den der Treiber ein {@code LocalDateTime} bindet. Die Spalten sind
     * Ortszeit, der Parameter ist es auch.
     *
     * <p>{@code EXISTS} statt {@code JOIN}: Ein Join lieferte je Teilnahme eine Zeile und
     * zaehlte den Termin mehrfach hoch, sobald ein Spieler dort mehr als einmal steht - was
     * {@code uq_teilnahme_spieler} zwar ausschliesst, aber ein Join macht die Abfrage von
     * dieser Zusicherung abhaengig, ohne dass es jemand sieht.
     */
    private static final String SQL_TEILNEHMER_VERSION_SPIELER = """
            UPDATE spieltag.termin t
               SET teilnehmer_version = t.teilnehmer_version + 1,
                   version            = t.version + 1
             WHERE t.status = 'GEPLANT'
               AND (t.datum + t.uhrzeit) > :jetzt
               AND EXISTS (SELECT 1
                             FROM spieltag.teilnahme tn
                            WHERE tn.termin_id  = t.id
                              AND tn.spieler_id = :spielerId
                              AND tn.zusage)
            """;

    /**
     * Der automatische Abschluss aus A18.
     *
     * <p><b>Nur aus {@code GEPLANT} heraus.</b> Ein abgesagter Termin bleibt abgesagt - er
     * hat nicht stattgefunden, und ein Abschluss behauptete das Gegenteil. Ein bereits
     * abgeschlossener wird nicht erneut angefasst; die Abfrage ist damit wiederholbar, und
     * der Zaehler {@code version} steigt nur beim tatsaechlichen Uebergang.
     *
     * <p><b>{@code teilnehmer_version} bleibt unberuehrt.</b> Der Abschluss aendert den
     * Teilnehmerkreis nicht - er stellt nur fest, dass gespielt wurde. Ein Ausschlag des
     * Zaehlers setzte ab S5 grundlos Generierungskontingente zurueck.
     */
    private static final String SQL_ABSCHLIESSEN = """
            UPDATE spieltag.termin
               SET status  = 'ABGESCHLOSSEN',
                   version = version + 1
             WHERE status = 'GEPLANT'
               AND (datum + uhrzeit) <= :grenze
            """;

    /**
     * Die Verwendungspruefung vor dem Entfernen (A19).
     *
     * <p><b>Vier Unterabfragen statt eines Joins:</b> {@code EXISTS} bricht beim ersten
     * Treffer ab, und {@code OR} wertet nur so weit aus, bis eine Bedingung wahr ist. Die
     * Reihenfolge ist nach Wahrscheinlichkeit sortiert - eine Teilnahme gibt es weit
     * haeufiger als ein Ergebnis.
     *
     * <p>{@code team_zuteilung} fehlt mit Absicht: Sie haengt ueber {@code generierung_id} an
     * {@code team_generierung} und kann ohne diese nicht existieren.
     */
    private static final String SQL_REFERENZIERT = """
            SELECT EXISTS (SELECT 1 FROM spieltag.teilnahme WHERE termin_id = :terminId)
                OR EXISTS (SELECT 1 FROM spieltag.team_generierung WHERE termin_id = :terminId)
                OR EXISTS (SELECT 1 FROM spieltag.generierung_kontingent WHERE termin_id = :terminId)
                OR EXISTS (SELECT 1 FROM spieltag.ergebnis WHERE termin_id = :terminId)
            """;

    private final JdbcClient jdbc;

    TerminRepositoryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TerminEintrag> findeUebersicht(LocalDate ab, Long spielerId, String gastName) {
        return jdbc.sql(SQL_UEBERSICHT)
                .param("ab", ab)
                .param("spielerId", spielerId)
                .param("gastName", gastName)
                .query(TerminRepositoryImpl::alsEintrag)
                .list();
    }

    @Override
    public Optional<TerminEintrag> findeEintrag(Long terminId, Long spielerId, String gastName) {
        return jdbc.sql(SQL_EINZELN)
                .param("terminId", terminId)
                .param("spielerId", spielerId)
                .param("gastName", gastName)
                .query(TerminRepositoryImpl::alsEintrag)
                .optional();
    }

    @Override
    public Optional<Long> einfuegenWennFrei(Long serieId, LocalDate datum, LocalTime uhrzeit, String ort) {
        return jdbc.sql(SQL_EINFUEGEN)
                .param("serieId", serieId)
                .param("datum", datum)
                .param("uhrzeit", uhrzeit)
                .param("ort", ort)
                .query(Long.class)
                .optional();
    }

    @Override
    public int teilnehmerVersionErhoehen(Long terminId) {
        return jdbc.sql(SQL_TEILNEHMER_VERSION).param("terminId", terminId).update();
    }

    @Override
    public int teilnehmerVersionErhoehenFuerSpieler(Long spielerId, LocalDateTime jetzt) {
        return jdbc.sql(SQL_TEILNEHMER_VERSION_SPIELER)
                .param("spielerId", spielerId)
                .param("jetzt", jetzt)
                .update();
    }

    @Override
    public int abgelaufeneAbschliessen(LocalDateTime grenze) {
        return jdbc.sql(SQL_ABSCHLIESSEN).param("grenze", grenze).update();
    }

    @Override
    public boolean istReferenziert(Long terminId) {
        return Boolean.TRUE.equals(jdbc.sql(SQL_REFERENZIERT)
                .param("terminId", terminId)
                .query(Boolean.class)
                .single());
    }

    /**
     * Baut eine Ergebniszeile.
     *
     * <p>{@code getObject(spalte, Typ)} und nicht {@code getLong}/{@code getBoolean}:
     * Die Kurzformen liefern fuer {@code NULL} eine {@code 0} beziehungsweise {@code false}
     * und verlangen ein anschliessendes {@code wasNull()}. Genau die drei Spalten, die hier
     * {@code null} sein duerfen - {@code serie_id}, {@code ort} und
     * {@code eigene_rueckmeldung} -, verloeren dabei ihre Bedeutung: Aus "noch nicht
     * gemeldet" wuerde "abgesagt".
     */
    private static TerminEintrag alsEintrag(ResultSet rs, int zeile) throws SQLException {
        return new TerminEintrag(
                rs.getLong("id"),
                rs.getObject("serie_id", Long.class),
                rs.getObject("datum", LocalDate.class),
                rs.getObject("uhrzeit", LocalTime.class),
                rs.getString("ort"),
                TerminStatus.valueOf(rs.getString("status")),
                rs.getInt("teilnehmer_version"),
                rs.getLong("version"),
                rs.getInt("zusagen"),
                rs.getObject("eigene_rueckmeldung", Boolean.class));
    }
}
