package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.spieltag.Teilnehmereintrag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Zugriff auf {@code spieltag.teilnahme} (S4, Abschnitte 5 bis 7).
 *
 * <h2>Ein Repository ohne Entity</h2>
 * Die Tabelle wird ausschliesslich angehaengt, bedingt aktualisiert und aggregiert gelesen -
 * genau die Faelle, fuer die {@code AGENT_SERVER.md} ein Repository ohne Entity erlaubt
 * ({@code AuditLogRepository}, {@code GastSlotRepository}, {@code SessionRepositoryImpl}).
 *
 * <p><b>Eine Entity mit {@code @Version} waere hier sogar nachteilig.</b> Optimistic Locking
 * meldet den Konflikt erst beim Schreiben und verlangt eine Wiederholung; die Rueckmeldung
 * dagegen soll ihn gar nicht erst haben. Zwei gleichzeitige Meldungen desselben Spielers -
 * doppeltes Tippen auf dem Mobilgeraet, der Fall aus dem Datenmodell - entscheidet
 * {@code ON CONFLICT} ohne Wiederholung. <b>Die Spalte {@code version} wird deshalb von Hand
 * fortgeschrieben</b>, wie es die Regel fuer per SQL geaenderte Versionsspalten verlangt.
 */
@Repository
public class TeilnahmeRepository {

    /**
     * Zusage oder Absage eines <b>Spielers</b>.
     *
     * <h2>Ein Upsert, kein Einfuegen</h2>
     * {@code uq_teilnahme_spieler UNIQUE (termin_id, spieler_id)} laesst je Termin genau eine
     * Zeile zu. Wer von Absage auf Zusage wechselt, aendert seine Zeile - er legt keine
     * zweite an.
     *
     * <h2>{@code gemeldet_am} wird bei jeder Zusage neu gesetzt</h2>
     * Weggabelung B. Das entscheidet die Warteschlange: Wer absagt und spaeter wieder zusagt,
     * reiht sich hinten ein. Die Alternative - die urspruengliche Zeit behalten - erlaubte
     * es, sich einen vorderen Platz freizuhalten: absagen, wenn man unsicher ist, und kurz
     * vor dem Termin wieder zusagen, vorbei an allen, die inzwischen fest zugesagt haben.
     * <b>Das ist kein theoretischer Fall</b>, sondern genau das Verhalten, das eine
     * Warteschlange verhindern soll.
     *
     * <p><b>Eine Absage laesst {@code gemeldet_am} unberuehrt</b> - sie steht ohnehin nicht
     * in der Reihenfolge. Sonst verloere jemand, der versehentlich absagt und sofort
     * korrigiert, seinen Platz an alle, die in der Zwischenzeit nichts getan haben.
     */
    private static final String SQL_RUECKMELDUNG_SPIELER = """
            INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage, gemeldet_am)
            VALUES (:terminId, :spielerId, :zusage, now())
            ON CONFLICT ON CONSTRAINT uq_teilnahme_spieler DO UPDATE
               SET zusage      = EXCLUDED.zusage,
                   gemeldet_am = CASE WHEN EXCLUDED.zusage THEN now()
                                      ELSE spieltag.teilnahme.gemeldet_am END,
                   version     = spieltag.teilnahme.version + 1
            """;

    /**
     * Zusage oder Absage eines <b>Gastes</b>.
     *
     * <h2>Warum die Konfliktangabe hier anders aussieht</h2>
     * {@code uq_teilnahme_gast} ist ein <b>partieller</b> Index
     * ({@code WHERE gast_name IS NOT NULL}) und kein benannter Constraint - er laesst sich
     * deshalb nicht ueber {@code ON CONFLICT ON CONSTRAINT} ansprechen. PostgreSQL verlangt
     * die Spaltenliste <i>samt der Bedingung</i>, sonst findet es den Index nicht und meldet
     * "no unique or exclusion constraint matching the ON CONFLICT specification". Der Index
     * ist partiell, weil {@code spieler_id} bei Gaesten {@code NULL} ist und {@code NULL}
     * -Werte in einem gewoehnlichen Unique-Index als verschieden gelten.
     *
     * <h2>Die Stufe wird kopiert, nicht verwiesen</h2>
     * {@code gast_stufe} kommt aus der Sitzung und landet in der Zeile. Die Sitzung endet,
     * die Teilnahme bleibt - und der Teamgenerator in S5 braucht die Stufe zum Zeitpunkt des
     * Spiels. <b>Eine erneute Rueckmeldung ueberschreibt sie</b>: Meldet sich ein Gast neu
     * an und stuft sich anders ein, gilt die neue Stufe ab seiner naechsten Rueckmeldung -
     * und damit auch dann, wenn der Admin sie zwischenzeitlich korrigiert hatte.
     */
    private static final String SQL_RUECKMELDUNG_GAST = """
            INSERT INTO spieltag.teilnahme (termin_id, gast_name, gast_stufe, zusage, gemeldet_am)
            VALUES (:terminId, :gastName, :gastStufe, :zusage, now())
            ON CONFLICT (termin_id, gast_name) WHERE gast_name IS NOT NULL DO UPDATE
               SET zusage      = EXCLUDED.zusage,
                   gast_stufe  = EXCLUDED.gast_stufe,
                   gemeldet_am = CASE WHEN EXCLUDED.zusage THEN now()
                                      ELSE spieltag.teilnahme.gemeldet_am END,
                   version     = spieltag.teilnahme.version + 1
            """;

    /**
     * Die Zusagen eines Termins in Warteschlangenreihenfolge.
     *
     * <h2>{@code ORDER BY gemeldet_am, id} und nicht nur {@code gemeldet_am}</h2>
     * Zwei Zusagen in derselben Mikrosekunde sind unwahrscheinlich, aber {@code now()} ist
     * <b>innerhalb einer Transaktion konstant</b> - zwei Zeilen aus einem Serienimport oder
     * aus einem Testaufbau truegen denselben Zeitstempel. Die {@code id} macht die
     * Reihenfolge eindeutig, und der Index {@code ix_teilnahme_reihenfolge
     * (termin_id, gemeldet_am, id)} deckt genau diese Sortierung ab.
     *
     * <p>Dieselbe Sortierung steht im {@code row_number()} und im {@code ORDER BY}. Ohne die
     * zweite bliebe die Reihenfolge der <i>Zeilen</i> unbestimmt, waehrend die Position
     * stimmte - die Liste kaeme dann durcheinander an.
     *
     * <p><b>Nur Zusagen</b> ({@code AND tn.zusage}). Wer abgesagt hat, gehoert nicht in die
     * Teilnehmerliste; seine Absage sieht er an der eigenen Rueckmeldung des Termins.
     *
     * <p>{@code LEFT JOIN} auf {@code profil.spieler}: Bei einem Gast gibt es keine Zeile
     * dort, {@code s.name} ist dann {@code NULL} und {@code COALESCE} nimmt den Gastnamen.
     */
    private static final String SQL_UEBERSICHT = """
            SELECT tn.id,
                   tn.spieler_id,
                   tn.gast_name,
                   COALESCE(s.name, tn.gast_name) AS anzeige_name,
                   row_number() OVER (ORDER BY tn.gemeldet_am, tn.id) AS position
              FROM spieltag.teilnahme tn
              LEFT JOIN profil.spieler s ON s.id = tn.spieler_id
             WHERE tn.termin_id = :terminId
               AND tn.zusage
             ORDER BY tn.gemeldet_am, tn.id
            """;

    /**
     * Setzt die Skill-Stufe einer bestehenden Gast-Teilnahme (A17, Abschnitt 6.3) und
     * liefert dabei den <b>bisherigen</b> Wert zurueck.
     *
     * <h2>Der Selbstverbund ist der Kniff</h2>
     * {@code RETURNING} liefert in PostgreSQL die Zeile <i>nach</i> der Aenderung - der alte
     * Wert waere damit verloren. Ein {@code UPDATE ... FROM derselben Tabelle} sieht dagegen
     * den Stand <i>vor</i> der Aenderung: {@code alt} ist ein zweiter Verweis auf dieselbe
     * Zeile, aus dem Schnappschuss der Anweisung gelesen. So kommen Existenzpruefung und
     * Vorher-Wert aus einem einzigen Zugriff, ohne Fenster zwischen Lesen und Schreiben.
     *
     * <h2>Nur {@code gast_stufe}</h2>
     * Weder Zusage noch Name. Damit korrigiert der Admin eine Selbsteinschaetzung, die er
     * fuer falsch haelt - und genau das verlangt A17, nicht mehr. Eine Rueckmeldung
     * stellvertretend zu setzen erzeugte Zusagen, die niemand gegeben hat, und die
     * Warteschlange verteilte darauf Plaetze.
     *
     * <p>{@code gemeldet_am} bleibt unberuehrt: Die Aenderung ist keine neue Meldung und darf
     * die Position in der Warteschlange nicht verschieben.
     *
     * <p>Es wird <b>nicht</b> auf {@code zusage} eingeschraenkt. Auch die Stufe eines
     * abgesagten Gastes laesst sich korrigieren - sagt er wieder zu, ohne sich neu
     * anzumelden, gilt sie dann bereits.
     */
    private static final String SQL_GAST_STUFE = """
            UPDATE spieltag.teilnahme t
               SET gast_stufe = :gastStufe,
                   version    = t.version + 1
              FROM spieltag.teilnahme alt
             WHERE t.id = alt.id
               AND t.termin_id = :terminId
               AND t.gast_name = :gastName
            RETURNING alt.gast_stufe
            """;

    private final JdbcClient jdbc;

    public TeilnahmeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Traegt die Rueckmeldung eines Spielers ein oder aendert sie.
     *
     * @param terminId  betroffener Termin
     * @param spielerId Profil des Meldenden
     * @param zusage    {@code true} Zusage, {@code false} Absage
     * @return Anzahl geschriebener Zeilen; immer 1
     */
    public int rueckmeldungSpieler(Long terminId, Long spielerId, boolean zusage) {
        return jdbc.sql(SQL_RUECKMELDUNG_SPIELER)
                .param("terminId", terminId)
                .param("spielerId", spielerId)
                .param("zusage", zusage)
                .update();
    }

    /**
     * Traegt die Rueckmeldung eines Gastes ein oder aendert sie.
     *
     * @param terminId  betroffener Termin
     * @param gastName  Name aus der Sitzung, nie aus dem Anfragekoerper
     * @param gastStufe Selbsteinschaetzung aus der Sitzung; wird in die Zeile kopiert
     * @param zusage    {@code true} Zusage, {@code false} Absage
     * @return Anzahl geschriebener Zeilen; immer 1
     */
    public int rueckmeldungGast(Long terminId, String gastName, GastStufe gastStufe, boolean zusage) {
        return jdbc.sql(SQL_RUECKMELDUNG_GAST)
                .param("terminId", terminId)
                .param("gastName", gastName)
                .param("gastStufe", gastStufe.name())
                .param("zusage", zusage)
                .update();
    }

    /**
     * Liefert die Zusagen eines Termins in Warteschlangenreihenfolge.
     *
     * @param terminId betroffener Termin
     * @return Eintraege ab Position 1; leere Liste, wenn niemand zugesagt hat
     */
    public List<Teilnehmereintrag> findeZusagen(Long terminId) {
        return jdbc.sql(SQL_UEBERSICHT)
                .param("terminId", terminId)
                .query((rs, zeile) -> new Teilnehmereintrag(
                        rs.getLong("id"),
                        rs.getObject("spieler_id", Long.class),
                        rs.getString("gast_name"),
                        rs.getString("anzeige_name"),
                        rs.getInt("position")))
                .list();
    }

    /**
     * Setzt die Skill-Stufe einer Gast-Teilnahme.
     *
     * @param terminId  betroffener Termin
     * @param gastName  Gastname, zeichengenau wie in der Zeile
     * @param gastStufe neue Stufe
     * @return die bisherige Stufe; {@link Optional#empty()}, wenn es die Teilnahme nicht
     *         gibt. Ein leeres Optional bei vorhandener Zeile ist ausgeschlossen - die
     *         Rueckmeldung setzt die Stufe immer mit
     */
    public Optional<String> gastStufeSetzen(Long terminId, String gastName, GastStufe gastStufe) {
        // list() statt optional(): Eine gefundene Zeile mit leerer Stufe kaeme sonst als
        // Optional.empty() zurueck und liefe in ein 404, obwohl es die Teilnahme gibt.
        List<String> vorher = jdbc.sql(SQL_GAST_STUFE)
                .param("gastStufe", gastStufe.name())
                .param("terminId", terminId)
                .param("gastName", gastName)
                .query(String.class)
                .list();

        return vorher.isEmpty() ? Optional.empty() : Optional.ofNullable(vorher.getFirst());
    }
}
