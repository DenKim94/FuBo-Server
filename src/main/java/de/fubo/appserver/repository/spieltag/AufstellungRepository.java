package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.Profileintrag;
import de.fubo.appserver.domain.spieltag.Aufstellungsspieler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Datengrundlage des Teamgenerators (S5, Abschnitt 2).
 *
 * <h2>Ein Repository fuer drei Tabellen - mit Absicht</h2>
 * Gelesen wird aus {@code spieltag.teilnahme}, {@code profil.spieler} und
 * {@code profil.gast_vorlage}. Aufgeteilt auf drei Repositories stuenden der
 * {@code aktiv}-Filter und der Ausschluss des Adminprofils an drei Stellen, und die Regel
 * "jede Abfrage, die Mitspieler aufzaehlt, filtert {@code rolle <> 'ADMIN'}" waere dreimal zu
 * pruefen. Hier ist es <b>eine</b> fachliche Frage: Wer wird eingeteilt?
 *
 * <h2>Ohne Entity</h2>
 * Es wird ausschliesslich gelesen und aggregiert - derselbe Fall wie bei
 * {@code SkillKategorieRepository}. Eine Entity gaebe es fuer das Ergebnis ohnehin nicht: Die
 * Zeilen dreier Tabellen laufen zu einem {@link Aufstellungsspieler} zusammen.
 */
@Repository
public class AufstellungRepository {

    /**
     * Die Aufstellung eines Termins: die ersten {@code maxTeilnehmer} Zusagen in
     * Meldereihenfolge, samt der Skillwerte, die jetzt gelten (2.2).
     *
     * <h2>{@code ORDER BY} und {@code LIMIT} gehoeren zusammen ins SQL</h2>
     * Wer alle Zusagen laedt und in Java abschneidet, hat dieselbe Reihenfolge zweimal
     * beschrieben - hier und in {@code TeilnahmeRepository#findeZusagen}. Laufen sie
     * auseinander, zeigt die Teilnehmerliste einen Wartenden, den der Generator eingeteilt
     * hat, und es faellt erst auf, wenn jemand vor Ort fehlt. Die Sortierung ist dieselbe wie
     * dort, {@code ix_teilnahme_reihenfolge} deckt sie ab.
     *
     * <h2>Die Warteschlange bleibt draussen</h2>
     * Entschieden am 31.08.2026 (0.4, Punkt 2): Eingeteilt wird, wer spielt. A15 traegt das -
     * sagt jemand ab, ruecken die Wartenden nach, {@code teilnehmer_version} steigt, die
     * Einteilung veraltet und die Kontingente stehen wieder offen. Die Warteschlange kann
     * also gar nicht "zu spaet" eingeteilt werden.
     *
     * <h2>{@code (tn.spieler_id IS NULL OR s.aktiv)}</h2>
     * Der Filter auf gesperrte Profile ist eine <b>Absicherung fuer Bestandsdaten</b>: Seit
     * dem Nachtrag aus 10.1 nimmt das Sperren die Zusagen selbst zurueck, ein gesperrtes
     * Profil steht also gar nicht mehr in der Liste. Ein Filter, der im Normalfall nichts
     * tut, kostet nichts - eine Einteilung mit einem gesperrten Spieler kostete einen Abend.
     * Die zusaetzliche Bedingung auf {@code spieler_id IS NULL} ist Pflicht: Beim Gast gibt es
     * keine Zeile in {@code profil.spieler}, {@code s.aktiv} waere {@code NULL} und der Gast
     * fiele lautlos aus der Aufstellung.
     *
     * <h2>Das Adminprofil braucht keinen eigenen Filter</h2>
     * Es kann seit S4 keine Teilnahme haben ({@code 409 PROFIL_GESCHUETZT} am
     * Rueckmeldeendpunkt) und steht deshalb in keiner Zusage. Der Ausschluss wird trotzdem
     * wiederholt, wo eine Id von aussen kommt - siehe {@link #SQL_PROFILE}.
     *
     * <h2>Die Skillwerte kommen aus derselben Abfrage</h2>
     * Ein zweiter Zugriff je Teilnehmer waere ein N+1-Problem, ein {@code JOIN} auf oberster
     * Ebene lieferte je Skillzeile eine Ergebniszeile. {@code jsonb_object_agg} in einer
     * Unterabfrage liefert je Teilnehmer genau eine Zeile - dasselbe Muster wie in
     * {@code SpielerRepositoryImpl}.
     *
     * <p><b>{@code COALESCE(tn.gast_stufe, 'MITTEL')} steht hier und nicht im Dienst</b>
     * (entschieden am 31.08.2026, 0.4 Punkt 3). Der Ersatzwert gilt der Aufstellung; stuende
     * er im Dienst, muesste jeder kuenftige Leser dieser Daten dieselbe Regel noch einmal
     * kennen. Beim Gast <i>ist</i> die Stufe optional (A17), das Fehlen also kein Datenfehler
     * - anders als ein fehlender Skillwert eines Profils, den der Dienst ablehnt.
     *
     * <p>Der {@code JOIN} auf {@code k.aktiv} haelt abgeschaltete Kategorien heraus: Sie gehen
     * nicht in die Zielfunktion ein, ihre Zeilen bleiben aber in der Datenbank stehen, falls
     * die Kategorie wieder aktiviert wird.
     */
    private static final String SQL_TERMIN = """
            SELECT tn.id                            AS teilnahme_id,
                   tn.spieler_id,
                   COALESCE(s.name, tn.gast_name)   AS anzeige_name,
                   (tn.spieler_id IS NULL)          AS gast,
                   tn.gemeldet_am,
                   COALESCE(
                       CASE WHEN tn.spieler_id IS NOT NULL THEN
                           (SELECT jsonb_object_agg(sk.kategorie, sk.wert)
                              FROM profil.spieler_skill sk
                              JOIN profil.skill_kategorie k ON k.schluessel = sk.kategorie
                             WHERE sk.spieler_id = tn.spieler_id
                               AND k.aktiv)
                       ELSE
                           (SELECT jsonb_object_agg(gv.kategorie, gv.wert)
                              FROM profil.gast_vorlage gv
                              JOIN profil.skill_kategorie k ON k.schluessel = gv.kategorie
                             WHERE gv.stufe = COALESCE(tn.gast_stufe, 'MITTEL')
                               AND k.aktiv)
                       END,
                       '{}'::jsonb)                 AS skills
              FROM spieltag.teilnahme tn
              LEFT JOIN profil.spieler s ON s.id = tn.spieler_id
             WHERE tn.termin_id = :terminId
               AND tn.zusage
               AND (tn.spieler_id IS NULL OR s.aktiv)
             ORDER BY tn.gemeldet_am, tn.id
             LIMIT :maxTeilnehmer
            """;

    /**
     * Die vom Admin benannten Profile fuer den manuellen Lauf (2.5, A24).
     *
     * <h2>Die Rolle wird mitgelesen und nicht weggefiltert</h2>
     * Sonst liesse sich "unbekannt oder gesperrt" nicht von "Adminprofil" unterscheiden - und
     * die beiden Faelle haben verschiedene Antworten ({@code 400 EINGABE_UNGUELTIG} gegen
     * {@code 409 PROFIL_GESCHUETZT}). Welche der genannten Ids gar nicht zurueckkam,
     * entscheidet der Dienst, indem er die Anfrage mit dem Ergebnis vergleicht.
     *
     * <p><b>{@code AND s.aktiv} filtert gesperrte Profile weg</b>, und das ist hier kein
     * stilles Aussortieren: Was nicht zurueckkommt, wird abgelehnt und namentlich genannt.
     *
     * <h2>{@code IN} statt {@code = ANY}</h2>
     * Die Anleitung schreibt {@code = ANY(:spielerIds)}. Dafuer muesste ein
     * {@code java.sql.Array} aus der Verbindung erzeugt und mitgegeben werden;
     * {@code JdbcClient} setzt dagegen eine Liste in einer {@code IN}-Klausel selbst in
     * Platzhalter um. Fachlich dasselbe, ein Handgriff weniger. <b>Preis:</b> Eine leere
     * Liste ergaebe {@code IN ()} und damit einen Syntaxfehler - der Dienst ruft die Abfrage
     * deshalb nur mit mindestens einer Id auf.
     */
    private static final String SQL_PROFILE = """
            SELECT s.id,
                   s.name,
                   s.rolle,
                   COALESCE(
                       (SELECT jsonb_object_agg(sk.kategorie, sk.wert)
                          FROM profil.spieler_skill sk
                          JOIN profil.skill_kategorie k ON k.schluessel = sk.kategorie
                         WHERE sk.spieler_id = s.id
                           AND k.aktiv),
                       '{}'::jsonb) AS skills
              FROM profil.spieler s
             WHERE s.id IN (:spielerIds)
               AND s.aktiv
            """;

    /**
     * Die Gast-Vorlagen je Stufe (A17), Grundlage der frei angelegten Gaeste im manuellen Lauf.
     *
     * <p><b>Alle Stufen in einem Zugriff</b>, nicht eine Abfrage je genanntem Gast: Die
     * Tabelle hat drei Stufen mal fuenf Kategorien und liegt dauerhaft im Puffer der
     * Datenbank. Eine Abfrage je Gast waere ein N+1-Problem fuer 15 Zeilen.
     *
     * <p>Der {@code JOIN} auf {@code k.aktiv} aus demselben Grund wie oben.
     */
    private static final String SQL_GAST_VORLAGE = """
            SELECT gv.stufe, gv.kategorie, gv.wert
              FROM profil.gast_vorlage gv
              JOIN profil.skill_kategorie k ON k.schluessel = gv.kategorie
             WHERE k.aktiv
            """;

    /** Zieltyp fuer das Auslesen der aggregierten Skillwerte. */
    private static final TypeReference<Map<String, Integer>> SKILL_TYP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AufstellungRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Liefert die Aufstellung eines Termins.
     *
     * @param terminId       betroffener Termin
     * @param maxTeilnehmer  Hoechstzahl aus {@code configs.app_config}; alles darueber ist
     *                       Warteschlange und wird nicht eingeteilt
     * @return Teilnehmer in Meldereihenfolge; leere Liste, wenn niemand zugesagt hat
     */
    public List<Aufstellungsspieler> fuerTermin(Long terminId, int maxTeilnehmer) {
        return jdbc.sql(SQL_TERMIN)
                .param("terminId", terminId)
                .param("maxTeilnehmer", maxTeilnehmer)
                .query((rs, zeile) -> new Aufstellungsspieler(
                        rs.getLong("teilnahme_id"),
                        rs.getObject("spieler_id", Long.class),
                        rs.getString("anzeige_name"),
                        rs.getBoolean("gast"),
                        rs.getObject("gemeldet_am", OffsetDateTime.class),
                        skillsLesen(rs.getString("skills"))))
                .list();
    }

    /**
     * Liefert die genannten, aktiven Profile samt Rolle und Skillwerten.
     *
     * <p><b>Der Rueckgabetyp ist {@code Profileintrag} aus {@code domain.profil}</b> und kein
     * eigener Record: Genau diese fuenf Angaben stehen dort, und ein zweiter, fast gleicher
     * Typ liefe frueher oder spaeter auseinander. Dass {@code aktiv} hier immer {@code true}
     * ist, ist eine Folge der Abfrage und kein Widerspruch.
     *
     * @param spielerIds genannte Ids, <b>mindestens eine</b> - eine leere Liste ergaebe
     *                   {@code IN ()} und damit einen Syntaxfehler
     * @return gefundene Profile; die Reihenfolge ist unbestimmt, der Aufrufer greift ueber
     *         die Id zu
     */
    public List<Profileintrag> findeProfile(List<Long> spielerIds) {
        return jdbc.sql(SQL_PROFILE)
                .param("spielerIds", spielerIds)
                .query((rs, zeile) -> new Profileintrag(
                        rs.getLong("id"),
                        rs.getString("name"),
                        Rolle.valueOf(rs.getString("rolle")),
                        true,
                        skillsLesen(rs.getString("skills"))))
                .list();
    }

    /**
     * Liefert die Skillwerte je Gast-Stufe.
     *
     * @return Karte Stufe -> (Kategorie -> Wert); enthaelt nur aktive Kategorien
     */
    public Map<GastStufe, Map<String, Integer>> findeGastVorlagen() {
        List<VorlagenZeile> zeilen = jdbc.sql(SQL_GAST_VORLAGE)
                .query((rs, nummer) -> new VorlagenZeile(
                        GastStufe.valueOf(rs.getString("stufe")),
                        rs.getString("kategorie"),
                        rs.getInt("wert")))
                .list();

        Map<GastStufe, Map<String, Integer>> vorlagen = new EnumMap<>(GastStufe.class);
        for (VorlagenZeile zeile : zeilen) {
            vorlagen.computeIfAbsent(zeile.stufe(), stufe -> new LinkedHashMap<>())
                    .put(zeile.kategorie(), zeile.wert());
        }
        return vorlagen;
    }

    /**
     * Eine Zeile aus {@code profil.gast_vorlage}.
     *
     * <p><b>Der Zwischentyp ist keine Umstaendlichkeit:</b> Die Abfrage liefert je Stufe
     * mehrere Zeilen, die Karte entsteht erst daraus. Ein {@code RowMapper}, der stattdessen
     * eine Karte von aussen befuellt, haette eine Nebenwirkung und ein Ergebnis, das niemand
     * braucht - beim Lesen faellt dann nicht auf, wo die Karte eigentlich gefuellt wird.
     */
    private record VorlagenZeile(GastStufe stufe, String kategorie, int wert) {
    }

    /**
     * Uebersetzt das aggregierte {@code jsonb} in eine Karte.
     *
     * <p>Wortgleich zu {@code SpielerRepositoryImpl#skillsLesen} und aus demselben Grund:
     * Die Abfrage laeuft ueber {@code JdbcClient}, es gibt keine Entity fuer das Ergebnis,
     * und eine eigene Typabbildung braeuchte genau diesen Mapper noch einmal.
     *
     * <p>Ein Fehler hier ist kein fachlicher Fall, sondern ein Bruch zwischen Abfrage und
     * Zieltyp - also eine {@code IllegalStateException}. <b>Die Meldung nennt den unlesbaren
     * Wert nicht:</b> Er enthaelt Skillwerte, und die gehoeren nicht ins Log (A12).
     */
    private Map<String, Integer> skillsLesen(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, SKILL_TYP);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Die aggregierten Skillwerte liessen sich nicht lesen.", e);
        }
    }
}
