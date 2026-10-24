package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.spieltag.GastAbsageErgebnis;
import de.fubo.appserver.domain.spieltag.Teilnehmereintrag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
     *
     * <h2>Die Antwort sagt, ob es eine <i>neue</i> Zusage war</h2>
     * Ergaenzung vom 22.10.2026 (A15): Nur eine neue Zusage oeffnet das Generierungskontingent
     * wieder, eine Absage und eine wiederholte Zusage nicht. {@code vorher} liest den Stand
     * <b>vor</b> der Anweisung - alle Teile einer {@code WITH}-Anweisung sehen denselben
     * Schnappschuss -, {@code neu} schreibt. So kommen Schreiben und Vorher-Wert aus einem
     * einzigen Zugriff, ohne Fenster dazwischen; dieselbe Idee wie beim Selbstverbund in
     * {@link #SQL_GAST_STUFE}. <b>Neu heisst:</b> jetzt zugesagt und vorher nicht - ohne Zeile
     * oder mit Absage.
     *
     * <p><b>Bekannte Grenze:</b> Druecken zwei Aufrufe desselben Spielers gleichzeitig auf
     * "Zusage", sehen beide den Stand ohne Zeile, und beide melden "neu". Folge ist eine
     * ueberzaehlige Oeffnung des Kontingents durch genau diesen Spieler - ein Zaehler, der
     * einmal zu viel steigt, kein Datenverlust.
     */
    private static final String SQL_RUECKMELDUNG_SPIELER = """
            WITH vorher AS (
                SELECT zusage
                  FROM spieltag.teilnahme
                 WHERE termin_id  = :terminId
                   AND spieler_id = :spielerId
            ), neu AS (
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage, gemeldet_am)
                VALUES (:terminId, :spielerId, :zusage, now())
                ON CONFLICT ON CONSTRAINT uq_teilnahme_spieler DO UPDATE
                   SET zusage      = EXCLUDED.zusage,
                       gemeldet_am = CASE WHEN EXCLUDED.zusage THEN now()
                                          ELSE spieltag.teilnahme.gemeldet_am END,
                       version     = spieltag.teilnahme.version + 1
                RETURNING zusage
            )
            SELECT (SELECT zusage FROM neu)
                   AND NOT COALESCE((SELECT zusage FROM vorher), false) AS neue_zusage
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
     *
     * <p><b>Die Antwort sagt wie beim Spieler, ob es eine neue Zusage war</b> - Begruendung
     * und bekannte Grenze stehen an {@link #SQL_RUECKMELDUNG_SPIELER}.
     */
    private static final String SQL_RUECKMELDUNG_GAST = """
            WITH vorher AS (
                SELECT zusage
                  FROM spieltag.teilnahme
                 WHERE termin_id = :terminId
                   AND gast_name = :gastName
            ), neu AS (
                INSERT INTO spieltag.teilnahme (termin_id, gast_name, gast_stufe, zusage, gemeldet_am)
                VALUES (:terminId, :gastName, :gastStufe, :zusage, now())
                ON CONFLICT (termin_id, gast_name) WHERE gast_name IS NOT NULL DO UPDATE
                   SET zusage      = EXCLUDED.zusage,
                       gast_stufe  = EXCLUDED.gast_stufe,
                       gemeldet_am = CASE WHEN EXCLUDED.zusage THEN now()
                                          ELSE spieltag.teilnahme.gemeldet_am END,
                       version     = spieltag.teilnahme.version + 1
                RETURNING zusage
            )
            SELECT (SELECT zusage FROM neu)
                   AND NOT COALESCE((SELECT zusage FROM vorher), false) AS neue_zusage
            """;

    /**
     * Hat dieser Aufrufer fuer den Termin zugesagt? (A15, Ergaenzung vom 22.10.2026)
     *
     * <p><b>Gezaehlt wird die Zusage, nicht die Aufstellung.</b> Wer in der Warteschlange steht,
     * hat zugesagt und rueckt nach - er steht in der Teilnehmerliste und darf generieren. Eine
     * Absage zaehlt nicht, eine fehlende Zeile ebenso wenig.
     *
     * <p><b>{@code NULL} heisst hier "trifft nie"</b>, wie im Rumpf der Terminabfrage: Bei einer
     * Spielersitzung ist {@code :gastName} {@code NULL}, bei einer Gastsitzung
     * {@code :spielerId}, und der jeweils andere Vergleich ergibt {@code UNKNOWN}. Wer
     * {@code NULL} hier fuer "egal" haelt, gibt jedem Gast die Zusage eines Spielers.
     */
    private static final String SQL_HAT_ZUGESAGT = """
            SELECT EXISTS (
                SELECT 1
                  FROM spieltag.teilnahme tn
                 WHERE tn.termin_id = :terminId
                   AND tn.zusage
                   AND (tn.spieler_id = :spielerId OR tn.gast_name = :gastName)
            )
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
     *
     * <h2>Die Gast-Stufe kommt nur auf Verlangen aus der Datenbank (24.10.2026)</h2>
     * {@code :mitGastStufe} ist {@code true} nur fuer eine Sitzung mit der Rolle
     * {@code ADMIN}. Sonst ist die Spalte {@code NULL} - die Bewertung wird nicht gelesen und
     * nicht erst spaeter herausgefiltert. Die Entscheidung faellt an genau einer Stelle
     * ({@code TerminService#einzelnLesen}); hier steht nur der Mechanismus.
     *
     * <p><b>{@code COALESCE(tn.gast_stufe, 'MITTEL')}</b> wie in der Datengrundlage des
     * Generators ({@code AufstellungRepository}): Der Admin soll die Stufe sehen, mit der
     * gerechnet wird, nicht den rohen Spaltenwert. Beim Gast ist die Stufe optional (A17); ein
     * Spieler hat keine, dort bleibt es {@code NULL} ({@code spieler_id IS NULL} schliesst ihn
     * aus). Nach einer Korrektur durch {@code gast-stufe} steht hier die korrigierte Stufe,
     * denn die Teilnahme wird dort ueberschrieben.
     */
    private static final String SQL_UEBERSICHT = """
            SELECT tn.id,
                   tn.spieler_id,
                   tn.gast_name,
                   COALESCE(s.name, tn.gast_name) AS anzeige_name,
                   row_number() OVER (ORDER BY tn.gemeldet_am, tn.id) AS position,
                   CASE WHEN :mitGastStufe AND tn.spieler_id IS NULL
                        THEN COALESCE(tn.gast_stufe, 'MITTEL')
                   END AS gast_stufe
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

    /**
     * Nimmt die Zusage eines <b>Gastes</b> zurueck, ohne seine Stufe anzufassen (Ergaenzung vom
     * 24.10.2026: Der Admin sagt fuer einen Gast ab, dessen Sitzung abgelaufen ist).
     *
     * <h2>Ein gezielter {@code UPDATE}, nicht {@link #SQL_RUECKMELDUNG_GAST}</h2>
     * Die Rueckmeldung ist ein Upsert und schriebe {@code gast_stufe} aus der Sitzung in die
     * Zeile - hier gibt es keine Sitzung, und eine vom Admin korrigierte Stufe ginge verloren.
     * Ausserdem darf diese Anweisung <b>nie eine Zeile anlegen</b>: Eine Absage fuer einen Gast
     * ohne Teilnahme ist ein {@code 404}, kein neuer Eintrag.
     *
     * <h2>Was die Antwort unterscheidet</h2>
     * {@code ziel} liest, ob es die Teilnahme gibt; {@code geaendert} schreibt nur, wenn sie
     * noch eine Zusage ist. <b>Die Bedingung {@code tn.zusage} steht am Ziel des {@code UPDATE}
     * und nicht an {@code ziel}</b>: Unter READ COMMITTED wertet PostgreSQL die Bedingung nach
     * dem Warten auf eine gleichzeitige Aenderung gegen die neue Zeilenfassung neu aus. Sagt
     * der Gast im selben Augenblick selbst ab, aendert diese Anweisung nichts mehr - kein
     * zweiter Versionssprung, kein zweiter Protokolleintrag. Gaebe es die Bedingung nur an
     * {@code ziel}, stuende dort der Stand von vor dem Warten.
     *
     * <p>Aus beiden Zaehlern ergibt sich der Ausgang: nichts gefunden, gefunden aber schon
     * abgesagt, oder zurueckgenommen. Alles in einem Zugriff, ohne Fenster zwischen Lesen und
     * Schreiben.
     *
     * <p>{@code gemeldet_am} bleibt unberuehrt - wie bei der eigenen Absage; sie steht ohnehin
     * nicht in der Reihenfolge. {@code version} steigt mit, wie bei jedem per SQL
     * geaenderten Stand.
     */
    private static final String SQL_GAST_ABSAGEN = """
            WITH ziel AS (
                SELECT id
                  FROM spieltag.teilnahme
                 WHERE termin_id = :terminId
                   AND gast_name = :gastName
            ), geaendert AS (
                UPDATE spieltag.teilnahme tn
                   SET zusage  = false,
                       version = tn.version + 1
                  FROM ziel
                 WHERE tn.id = ziel.id
                   AND tn.zusage
                RETURNING tn.id
            )
            SELECT (SELECT count(*) FROM ziel)      AS vorhanden,
                   (SELECT count(*) FROM geaendert) AS geaendert
            """;

    /**
     * Nimmt die Zusagen eines gesperrten Profils fuer kuenftige, geplante Termine zurueck
     * (S5 Abschnitt 10.1, Nachtrag aus S4).
     *
     * <h2>Nur kuenftige und nur geplante Termine</h2>
     * Dieselbe Abgrenzung wie beim Zaehler-Nachtrag aus S4: Ein gespieltes Training laesst
     * sich nicht nachtraeglich absagen, und ein abgesagter Termin hat keine Teilnehmer mehr,
     * um die es ginge.
     *
     * <p><b>{@code version} steigt mit</b> - die Regel gilt fuer jede {@code version}-Spalte,
     * die per SQL geaendert wird.
     *
     * <p><b>{@code AND tn.zusage} macht die Anweisung wiederholbar</b> und haelt die
     * Rueckgabe ehrlich: Gezaehlt werden die Zusagen, die es wirklich gab, nicht alle
     * Teilnahmezeilen des Profils.
     *
     * <p>Der Zeitpunkt kommt als Parameter aus der {@code Clock}-Bean und nicht aus
     * {@code current_date}/{@code current_time}: Die richten sich nach der Zeitzone der
     * Datenbanksitzung, und die steht im Container auf UTC.
     */
    private static final String SQL_ZUSAGEN_ZURUECKNEHMEN = """
            UPDATE spieltag.teilnahme tn
               SET zusage  = false,
                   version = tn.version + 1
              FROM spieltag.termin t
             WHERE tn.termin_id  = t.id
               AND tn.spieler_id = :spielerId
               AND tn.zusage
               AND t.status = 'GEPLANT'
               AND (t.datum + t.uhrzeit) > :jetzt
            """;

    private final JdbcClient jdbc;

    public TeilnahmeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Setzt die Zusagen eines Profils fuer kuenftige, geplante Termine auf Absage.
     *
     * <p><b>Der Aufrufer erhoeht vorher {@code teilnehmer_version}</b> - danach findet die
     * Suche dort nichts mehr, weil sie ueber {@code EXISTS (... AND tn.zusage)} laeuft.
     *
     * @param spielerId gesperrtes Profil
     * @param jetzt     Vergleichszeitpunkt aus der {@code Clock}-Bean
     * @return Anzahl zurueckgenommener Zusagen
     */
    public int zusagenZuruecknehmen(Long spielerId, LocalDateTime jetzt) {
        return jdbc.sql(SQL_ZUSAGEN_ZURUECKNEHMEN)
                .param("spielerId", spielerId)
                .param("jetzt", jetzt)
                .update();
    }

    /**
     * Traegt die Rueckmeldung eines Spielers ein oder aendert sie.
     *
     * @param terminId  betroffener Termin
     * @param spielerId Profil des Meldenden
     * @param zusage    {@code true} Zusage, {@code false} Absage
     * @return {@code true}, wenn das eine <b>neue</b> Zusage war: jetzt zugesagt, vorher nicht
     *         (keine Zeile oder Absage). {@code false} bei einer Absage und bei einer
     *         wiederholten Zusage
     */
    public boolean rueckmeldungSpieler(Long terminId, Long spielerId, boolean zusage) {
        Boolean neueZusage = jdbc.sql(SQL_RUECKMELDUNG_SPIELER)
                .param("terminId", terminId)
                .param("spielerId", spielerId)
                .param("zusage", zusage)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(neueZusage);
    }

    /**
     * Traegt die Rueckmeldung eines Gastes ein oder aendert sie.
     *
     * @param terminId  betroffener Termin
     * @param gastName  Name aus der Sitzung, nie aus dem Anfragekoerper
     * @param gastStufe Selbsteinschaetzung aus der Sitzung; wird in die Zeile kopiert
     * @param zusage    {@code true} Zusage, {@code false} Absage
     * @return {@code true}, wenn das eine <b>neue</b> Zusage war - wie bei
     *         {@link #rueckmeldungSpieler}
     */
    public boolean rueckmeldungGast(Long terminId, String gastName, GastStufe gastStufe,
                                    boolean zusage) {
        Boolean neueZusage = jdbc.sql(SQL_RUECKMELDUNG_GAST)
                .param("terminId", terminId)
                .param("gastName", gastName)
                .param("gastStufe", gastStufe.name())
                .param("zusage", zusage)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(neueZusage);
    }

    /**
     * Prueft, ob ein Spieler oder Gast fuer den Termin zugesagt hat.
     *
     * <p>Genau eines der beiden Merkmale ist gesetzt - je nach Sitzung; das andere ist
     * {@code null} und trifft nie (siehe {@link #SQL_HAT_ZUGESAGT}).
     *
     * @param terminId  betroffener Termin
     * @param spielerId Profil-Id aus der Sitzung oder {@code null} bei einem Gast
     * @param gastName  Gastname aus der Sitzung oder {@code null} bei einem Spieler
     * @return {@code true}, wenn eine Teilnahme mit {@code zusage = true} existiert
     */
    public boolean hatZugesagt(Long terminId, Long spielerId, String gastName) {
        Boolean zugesagt = jdbc.sql(SQL_HAT_ZUGESAGT)
                .param("terminId", terminId)
                .param("spielerId", spielerId)
                .param("gastName", gastName)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(zugesagt);
    }

    /**
     * Liefert die Zusagen eines Termins in Warteschlangenreihenfolge.
     *
     * @param terminId     betroffener Termin
     * @param mitGastStufe {@code true} nur fuer eine Adminsitzung: Dann tragen die Gaeste ihre
     *                     Stufe; sonst bleibt das Feld {@code null} und die Spalte wird nicht
     *                     gelesen (A12)
     * @return Eintraege ab Position 1; leere Liste, wenn niemand zugesagt hat
     */
    public List<Teilnehmereintrag> findeZusagen(Long terminId, boolean mitGastStufe) {
        return jdbc.sql(SQL_UEBERSICHT)
                .param("terminId", terminId)
                .param("mitGastStufe", mitGastStufe)
                .query((rs, zeile) -> {
                    String stufe = rs.getString("gast_stufe");
                    return new Teilnehmereintrag(
                            rs.getLong("id"),
                            rs.getObject("spieler_id", Long.class),
                            rs.getString("gast_name"),
                            rs.getString("anzeige_name"),
                            rs.getInt("position"),
                            stufe == null ? null : GastStufe.valueOf(stufe));
                })
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

    /**
     * Nimmt die Zusage eines Gastes zurueck und laesst Stufe, Name und Meldezeit stehen.
     *
     * <p>Der Aufrufer prueft vorher, ob der Termin noch Rueckmeldungen annimmt, und erhoeht
     * danach nur bei {@link GastAbsageErgebnis#ABGESAGT} die Zaehler und schreibt das Protokoll.
     *
     * @param terminId betroffener Termin
     * @param gastName Gastname, zeichengenau wie in der Zeile
     * @return {@link GastAbsageErgebnis#KEINE_TEILNAHME}, wenn es die Zeile nicht gibt;
     *         {@link GastAbsageErgebnis#BEREITS_ABGESAGT}, wenn sie schon eine Absage war;
     *         sonst {@link GastAbsageErgebnis#ABGESAGT}
     */
    public GastAbsageErgebnis gastAbsagen(Long terminId, String gastName) {
        return jdbc.sql(SQL_GAST_ABSAGEN)
                .param("terminId", terminId)
                .param("gastName", gastName)
                .query((rs, zeile) -> {
                    if (rs.getLong("vorhanden") == 0) {
                        return GastAbsageErgebnis.KEINE_TEILNAHME;
                    }
                    return rs.getLong("geaendert") > 0
                            ? GastAbsageErgebnis.ABGESAGT
                            : GastAbsageErgebnis.BEREITS_ABGESAGT;
                })
                .single();
    }
}
