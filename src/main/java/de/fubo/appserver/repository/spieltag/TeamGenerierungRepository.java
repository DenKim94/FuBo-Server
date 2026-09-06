package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.team.Generierungskopf;
import de.fubo.appserver.domain.team.Zuteilungssatz;
import de.fubo.appserver.domain.team.Zuteilungszeile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Gespeicherte Teameinteilungen: {@code spieltag.team_generierung} und
 * {@code spieltag.team_zuteilung} (S5 Abschnitte 7 und 9.1).
 *
 * <h2>Ohne Entity</h2>
 * Geschrieben wird nur angehaengt, gelesen wird ueber drei Tabellen hinweg zu einem
 * Wertobjekt - derselbe Fall wie bei {@code AufstellungRepository}. Eine Entity gaebe es fuer
 * das Leseergebnis ohnehin nicht.
 *
 * <p>Keine der beiden Tabellen traegt eine {@code version}-Spalte, und das ist kein Versehen:
 * Ein Lauf wird nie geaendert. Ein neuer loest den alten ab; der alte bleibt als Historie
 * stehen.
 *
 * <h2>Der manuelle Lauf kommt hier nie an</h2>
 * {@code team_generierung.termin_id} ist {@code NOT NULL} und
 * {@code team_zuteilung.teilnahme_id} haengt am Fremdschluessel auf {@code spieltag.teilnahme} -
 * ein Teilnehmer ohne Teilnahmezeile passt nicht hinein, und genau das ist der manuelle Lauf
 * nach A24. <b>Fiele diese Eigenschaft, braeuchte S5 eine Migration</b> (0.2, 0.6).
 */
@Repository
public class TeamGenerierungRepository {

    /**
     * Loest den bestehenden Lauf eines Termins ab.
     *
     * <p><b>Vor dem {@code INSERT} des neuen.</b> {@code ix_team_generierung_aktuell} ist ein
     * partieller Index und <i>setzt voraus</i>, dass je Termin hoechstens ein Lauf unabgeloest
     * ist - erzwingen tut er es nicht. Wer diesen {@code UPDATE} vergisst, bekommt keinen
     * Fehler, sondern zwei "aktuelle" Einteilungen und eine Leseabfrage, die je nach Laune
     * eine davon liefert.
     *
     * <p><b>Geloescht wird nichts.</b> Alte Laeufe sind die Historie und kosten wenig;
     * {@code ON DELETE CASCADE} am Termin raeumt sie mit ihm ab.
     */
    private static final String SQL_ABLOESEN = """
            UPDATE spieltag.team_generierung
               SET abgeloest_am = now()
             WHERE termin_id = :terminId
               AND abgeloest_am IS NULL
            """;

    /**
     * Legt den Kopfsatz an.
     *
     * <h2>Die Bezeichnung entsteht in der Datenbank</h2>
     * {@code erzeugt_von_bezeichnung} ist {@code NOT NULL} und soll den Anzeigenamen tragen -
     * beim Spieler steht der in {@code profil.spieler}, beim Gast in der Sitzung. Die
     * Unterabfrage spart den zusaetzlichen Lesezugriff und haelt beide Faelle an einer Stelle;
     * {@code COALESCE} entscheidet, welcher greift. <b>Der Name wird kopiert, nicht
     * verwiesen</b> - ein spaeter geloeschtes Profil liesse die Auskunft sonst leer, obwohl
     * der Lauf stattgefunden hat.
     *
     * <p>Die Umwandlungen sind ausdruecklich notiert: {@code erzeugt_von_spieler_id} und der
     * Gastname duerfen {@code null} sein, und PostgreSQL kann den Typ eines solchen Parameters
     * sonst nicht bestimmen.
     */
    private static final String SQL_KOPF_EINFUEGEN = """
            INSERT INTO spieltag.team_generierung
                        (termin_id, teilnehmer_version, seed,
                         erzeugt_von_spieler_id, erzeugt_von_bezeichnung,
                         differenz_teamstaerke)
                 VALUES (:terminId,
                         :teilnehmerVersion,
                         :seed,
                         CAST(:spielerId AS bigint),
                         COALESCE((SELECT s.name FROM profil.spieler s
                                    WHERE s.id = CAST(:spielerId AS bigint)),
                                  CAST(:gastName AS varchar),
                                  'unbekannt'),
                         :kosten)
              RETURNING id
            """;

    /**
     * Legt eine Zuteilung an.
     *
     * <p><b>Warnung aus S3:</b> Eine Karte ueber {@code Map#toString} in eine
     * {@code jsonb}-Spalte zu schreiben, erzeugt JSON-<i>Text</i> statt eines JSON-Objekts,
     * ohne Fehlermeldung - {@code ->>'TORWART'} liefert darauf {@code null}. Hier laeuft die
     * Umsetzung deshalb ueber den {@code ObjectMapper}, und der Regressionstest prueft
     * {@code jsonb_typeof(skills_snapshot) = 'object'} statt eines herausgelesenen Werts.
     */
    private static final String SQL_ZUTEILUNG_EINFUEGEN = """
            INSERT INTO spieltag.team_zuteilung
                        (generierung_id, teilnahme_id, team, score_snapshot, skills_snapshot)
                 VALUES (:generierungId,
                         :teilnahmeId,
                         CAST(:team AS char(1)),
                         :score,
                         CAST(:skills AS jsonb))
            """;

    /**
     * Der aktuelle Lauf eines Termins samt Veraltet-Kennzeichen.
     *
     * <p><b>{@code veraltet} wird abgeleitet, nicht gespeichert</b> - dieselbe Regel wie bei
     * der Warteschlangenposition in S4. Eine Spalte muesste bei jeder Teilnehmeraenderung ueber
     * alle Laeufe nachgezogen werden, und ein vergessener Nachtrag zeigte eine Einteilung
     * unbegrenzt lange als aktuell an.
     *
     * <p><b>{@code ORDER BY ... LIMIT 1}, obwohl es hoechstens eine Zeile geben darf.</b> Die
     * Eindeutigkeit haengt am Dienst und nicht an einem Constraint; ohne die Sortierung
     * lieferte ein vergessenes Abloesen mal die eine, mal die andere Einteilung - ein Fehler,
     * der sich nicht reproduzieren laesst. So gewinnt wenigstens die juengste.
     */
    private static final String SQL_KOPF_LESEN = """
            SELECT tg.id,
                   tg.erzeugt_am,
                   tg.erzeugt_von_bezeichnung,
                   tg.seed,
                   (tg.teilnehmer_version <> t.teilnehmer_version) AS veraltet
              FROM spieltag.team_generierung tg
              JOIN spieltag.termin t ON t.id = tg.termin_id
             WHERE tg.termin_id = :terminId
               AND tg.abgeloest_am IS NULL
             ORDER BY tg.erzeugt_am DESC, tg.id DESC
             LIMIT 1
            """;

    /**
     * Die Zuteilungen eines Laufs, in der Reihenfolge, in der sie geschrieben wurden.
     *
     * <p><b>{@code ORDER BY tz.id} ist nicht Kosmetik.</b> Der Auswechselspieler wird beim
     * Lesen erneut bestimmt, und bei Gleichstand entscheidet der Seed ueber die Position in
     * der Kandidatenliste (8.3). Nur wenn die Reihenfolge dieselbe ist wie im Lauf, faellt die
     * Wahl genauso aus. {@code BIGSERIAL} vergibt innerhalb einer Transaktion aufsteigend;
     * der Dienst schreibt Team A und Team B je in Laufreihenfolge.
     *
     * <p>{@code score_snapshot} und {@code gemeldet_am} kommen mit, weil die Wahl sie braucht -
     * in die Antwort gehen sie nicht (A12).
     */
    private static final String SQL_ZUTEILUNGEN_LESEN = """
            SELECT COALESCE(s.name, tn.gast_name) AS anzeige_name,
                   (tn.spieler_id IS NULL)        AS gast,
                   tz.team,
                   tz.score_snapshot,
                   tn.gemeldet_am
              FROM spieltag.team_zuteilung tz
              JOIN spieltag.teilnahme tn ON tn.id = tz.teilnahme_id
              LEFT JOIN profil.spieler s ON s.id = tn.spieler_id
             WHERE tz.generierung_id = :generierungId
             ORDER BY tz.id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public TeamGenerierungRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Loest den bisherigen Lauf ab.
     *
     * @return Anzahl abgeloester Laeufe; {@code 0} beim ersten Lauf eines Termins
     */
    public int abloesen(Long terminId) {
        return jdbc.sql(SQL_ABLOESEN).param("terminId", terminId).update();
    }

    /**
     * Legt den Kopfsatz an und liefert seinen Schluessel.
     *
     * @param terminId          betroffener Termin
     * @param teilnehmerVersion Teilnehmerstand, gegen den gerechnet wurde
     * @param seed              Seed des Laufs
     * @param spielerId         Profil-Id des Aufrufers oder {@code null} bei einem Gast
     * @param gastName          Gastname des Aufrufers oder {@code null} bei einem Spieler
     * @param kosten            Wert der Zielfunktion in der Darstellung der Spalte
     *                          ({@code NUMERIC(6,2)}); <b>nicht der Tie-Break</b>
     * @return Id des angelegten Laufs
     */
    public Long einfuegen(Long terminId, int teilnehmerVersion, long seed,
                          Long spielerId, String gastName, BigDecimal kosten) {
        return jdbc.sql(SQL_KOPF_EINFUEGEN)
                .param("terminId", terminId)
                .param("teilnehmerVersion", teilnehmerVersion)
                .param("seed", seed)
                .param("spielerId", spielerId)
                .param("gastName", gastName)
                .param("kosten", kosten)
                .query(Long.class)
                .single();
    }

    /**
     * Schreibt die Zuteilungen eines Laufs.
     *
     * <p><b>Eine Anweisung je Zeile und kein Stapel.</b> {@code JdbcClient} kennt keine
     * Stapelverarbeitung, und es geht um hoechstens {@code max_teilnehmer} Zeilen - bei 22
     * Zeilen waere ein zweiter Zugriffsweg neben {@code JdbcClient} teurer als die Ersparnis.
     *
     * <p>Die Reihenfolge der Liste ist die Reihenfolge der Schluessel und damit die des
     * Lesens; der Aufrufer haelt darin die Laufreihenfolge ein.
     */
    public void zuteilungenSchreiben(Long generierungId, List<Zuteilungssatz> saetze) {
        for (Zuteilungssatz satz : saetze) {
            jdbc.sql(SQL_ZUTEILUNG_EINFUEGEN)
                    .param("generierungId", generierungId)
                    .param("teilnahmeId", satz.teilnahmeId())
                    .param("team", String.valueOf(satz.team()))
                    .param("score", satz.score())
                    .param("skills", objectMapper.writeValueAsString(satz.werte()))
                    .update();
        }
    }

    /**
     * Liefert den aktuellen, nicht abgeloesten Lauf eines Termins.
     *
     * @return der Lauf oder {@link Optional#empty()}, wenn fuer diesen Termin noch keiner
     *         gerechnet wurde - <b>kein Fehler, sondern der Normalzustand</b>
     */
    public Optional<Generierungskopf> findeAktuellen(Long terminId) {
        return jdbc.sql(SQL_KOPF_LESEN)
                .param("terminId", terminId)
                .query((rs, zeile) -> new Generierungskopf(
                        rs.getLong("id"),
                        rs.getObject("erzeugt_am", OffsetDateTime.class),
                        rs.getString("erzeugt_von_bezeichnung"),
                        rs.getLong("seed"),
                        rs.getBoolean("veraltet")))
                .optional();
    }

    /**
     * Liefert die Zuteilungen eines Laufs in Schreibreihenfolge.
     *
     * <p>{@code score_snapshot} wird in Hundertstel zurueckgerechnet - dieselbe Einheit, in der
     * der Lauf gerechnet hat. {@code movePointRight(2)} ist verlustfrei, weil die Spalte genau
     * zwei Nachkommastellen fuehrt.
     */
    public List<Zuteilungszeile> findeZuteilungen(Long generierungId) {
        return jdbc.sql(SQL_ZUTEILUNGEN_LESEN)
                .param("generierungId", generierungId)
                .query((rs, zeile) -> new Zuteilungszeile(
                        rs.getString("anzeige_name"),
                        rs.getBoolean("gast"),
                        rs.getString("team").charAt(0),
                        rs.getBigDecimal("score_snapshot").movePointRight(2).longValueExact(),
                        rs.getObject("gemeldet_am", OffsetDateTime.class)))
                .list();
    }
}
