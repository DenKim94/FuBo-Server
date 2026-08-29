package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.profil.NamensEintrag;
import de.fubo.appserver.domain.profil.Profileintrag;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC-Implementierung des handgeschriebenen Repository-Teils.
 *
 * <p><b>Der Klassenname ist nicht frei waehlbar:</b> Spring Data findet die Implementierung
 * ausschliesslich ueber die Konvention "Name des Repository-Interface + Impl". Eine Klasse
 * mit anderem Namen wird stillschweigend ignoriert - das Repository laesst sich dann zwar
 * erzeugen, der Aufruf scheitert aber zur Laufzeit.
 */
class SpielerRepositoryImpl implements SpielerRepositoryCustom {

    /**
     * Der Belegtstatus wird nicht gespeichert, sondern aus den aktiven Sitzungen abgeleitet
     * (A6). Damit kann er nicht veralten: Laeuft eine Sitzung ab oder wird sie widerrufen,
     * ist der Name im naechsten Abruf wieder frei, ganz ohne Aufraeumschritt.
     *
     * <p>{@code EXISTS} statt {@code JOIN} oder {@code count(*)}: PostgreSQL bricht die
     * Unterabfrage beim ersten Treffer ab, und ein {@code JOIN} lieferte je aktiver Sitzung
     * eine Zeile - der Name erschiene dann mehrfach.
     *
     * <p>Die drei Bedingungen der Unterabfrage sind dieselben wie in der Sitzungspruefung
     * ({@code SessionRepositoryImpl}). Der partielle Index {@code ix_session_aktiv}
     * ({@code WHERE widerrufen_am IS NULL}) haelt sie klein, auch wenn die Tabelle mit
     * abgelaufenen Sitzungen waechst.
     *
     * <p>{@code now()} ist die Datenbankuhr - dieselbe, gegen die auch die Sitzung geprueft
     * wird. Eine abweichende JVM-Uhr kann den Belegtstatus damit nicht verfaelschen.
     *
     * <p><b>{@code rolle <> 'ADMIN'} schliesst das Adminprofil aus</b> (Entscheidung des
     * Haupt-Entwicklers vom 22.08.2026). Es ist ein technisches Konto und kein Mitspieler:
     * Es nimmt weder an Terminen noch an der Teamgenerierung teil und hat deshalb in der
     * Auswahlliste nichts zu suchen. Der Admin meldet sich ueber
     * {@code POST /auth/admin/anmelden} mit seinem Passwort an, nicht ueber die Namenswahl.
     *
     * <p>Der Filter allein genuegt nicht: {@code NamenService#waehleName} nimmt eine Id
     * entgegen und muss dieselbe Bedingung erneut pruefen. Sonst bliebe der Ausschluss reine
     * Anzeige - wer die Id kennt, koennte das Profil weiterhin waehlen.
     */
    private static final String SQL_NAMENSLISTE = """
            SELECT s.id,
                   s.name,
                   EXISTS (SELECT 1
                             FROM profil.session se
                            WHERE se.spieler_id = s.id
                              AND se.widerrufen_am IS NULL
                              AND se.gueltig_bis > now()
                              AND se.absolut_gueltig_bis > now()) AS belegt
              FROM profil.spieler s
             WHERE s.aktiv
               AND s.rolle <> 'ADMIN'
             ORDER BY s.name
            """;

    /**
     * Alle Profile mit ihren Skillwerten - die Datengrundlage der Adminuebersicht (S3,
     * Abschnitt 2.3).
     *
     * <p><b>Kein {@code WHERE s.aktiv} und kein {@code rolle <> 'ADMIN'}.</b> Das ist der
     * Unterschied zur Namensliste und der ganze Zweck des Endpunkts: Gesperrte Profile muessen
     * sich wiederfinden lassen, sonst liesse sich eine versehentliche Sperre nicht
     * zuruecknehmen; und eine Profilverwaltung zaehlt keine Mitspieler auf, sondern den
     * Datenbestand. Die Architekturregel verlangt den Adminfilter fuer Abfragen, die
     * <i>Mitspieler</i> aufzaehlen - Namensliste, Teilnehmerliste, Datengrundlage des
     * Teamgenerators. Diese hier gehoert nicht dazu.
     *
     * <p><b>{@code jsonb_object_agg} statt eines {@code JOIN} auf oberster Ebene.</b> Ein
     * {@code JOIN} lieferte je Skillzeile eine Ergebniszeile, das Profil erschiene fuenffach
     * und muesste in Java wieder zusammengefasst werden. Ein zweiter Aufruf je Profil waere
     * ein N+1-Problem. Die Aggregation in der Datenbank liefert je Profil genau eine Zeile.
     *
     * <p><b>Der {@code JOIN} auf {@code k.aktiv}</b> filtert abgeschaltete Kategorien heraus.
     * Eine deaktivierte Kategorie soll weder im Formular erscheinen noch in die Zielfunktion
     * des Teamgenerators eingehen; die Skillzeile bleibt in der Datenbank erhalten, falls die
     * Kategorie wieder aktiviert wird.
     *
     * <p><b>{@code COALESCE(..., '{}')}</b> ist noetig, weil {@code jsonb_object_agg} ueber
     * einer leeren Menge {@code NULL} liefert, nicht das leere Objekt - ein Profil ganz ohne
     * Skillzeilen braechte sonst einen Nullwert bis in die Antwort.
     *
     * <p><b>{@code ORDER BY s.rolle DESC}</b> stellt das Adminprofil ans Ende: {@code 'USER'}
     * steht alphabetisch nach {@code 'ADMIN'}, absteigend sortiert stehen die Spielerprofile
     * also vorn und das technische Konto hinten, wo es hingehoert.
     */
    private static final String SQL_PROFILSTAMMDATEN = """
            SELECT s.id,
                   s.name,
                   s.rolle,
                   s.aktiv,
                   COALESCE(
                       (SELECT jsonb_object_agg(sk.kategorie, sk.wert)
                          FROM profil.spieler_skill sk
                          JOIN profil.skill_kategorie k ON k.schluessel = sk.kategorie
                         WHERE sk.spieler_id = s.id
                           AND k.aktiv),
                       '{}'::jsonb) AS skills
              FROM profil.spieler s
             ORDER BY s.rolle DESC, s.name
            """;

    /**
     * Die Ids der Profile mit laufender Sitzung (A6).
     *
     * <p>Dieselben drei Bedingungen wie in der Sitzungspruefung und in
     * {@link #SQL_NAMENSLISTE} - {@code now()} ist dabei die Datenbankuhr, dieselbe, gegen die
     * auch die Sitzung geprueft wird. Eine abweichende JVM-Uhr kann den Belegtstatus damit
     * nicht verfaelschen.
     *
     * <p>{@code spieler_id IS NOT NULL} schliesst Gast- und {@code PIN_VERIFIED}-Sitzungen
     * aus: Beide haben kein Profil, und {@code DISTINCT} ueber einer Spalte voller Nullwerte
     * lieferte einen Eintrag, der zu keiner Profil-Id passt.
     */
    private static final String SQL_BELEGTE_IDS = """
            SELECT DISTINCT se.spieler_id
              FROM profil.session se
             WHERE se.spieler_id IS NOT NULL
               AND se.widerrufen_am IS NULL
               AND se.gueltig_bis > now()
               AND se.absolut_gueltig_bis > now()
            """;

    /** Zieltyp fuer das Auslesen der aggregierten Skillwerte. */
    private static final TypeReference<Map<String, Integer>> SKILL_TYP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    SpielerRepositoryImpl(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<NamensEintrag> findeNamensliste() {
        return jdbc.sql(SQL_NAMENSLISTE)
                .query((rs, zeile) -> new NamensEintrag(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBoolean("belegt")))
                .list();
    }

    @Override
    public List<Profileintrag> findeProfilstammdaten() {
        return jdbc.sql(SQL_PROFILSTAMMDATEN)
                .query((rs, zeile) -> new Profileintrag(
                        rs.getLong("id"),
                        rs.getString("name"),
                        Rolle.valueOf(rs.getString("rolle")),
                        rs.getBoolean("aktiv"),
                        skillsLesen(rs.getString("skills"))))
                .list();
    }

    @Override
    public Set<Long> findeBelegteProfilIds() {
        return new LinkedHashSet<>(jdbc.sql(SQL_BELEGTE_IDS).query(Long.class).list());
    }

    /**
     * Uebersetzt das aggregierte {@code jsonb} in eine Karte.
     *
     * <p><b>Ueber den vorhandenen Jackson-{@code ObjectMapper}, nicht ueber eine
     * JPA-Typabbildung:</b> Die Abfrage laeuft wie {@link #findeNamensliste()} ueber
     * {@code JdbcClient}, es gibt keine Entity fuer dieses Ergebnis, und eine eigene
     * Typabbildung braeuchte genau diesen Mapper noch einmal.
     *
     * <p>Ein Fehler hier ist kein fachlicher Fall, sondern ein Bruch zwischen Abfrage und
     * Zieltyp - also eine {@code IllegalStateException} und kein {@code FachlicherFehler}.
     * Die Meldung nennt den unlesbaren Wert nicht: Er enthaelt Skillwerte, und die gehoeren
     * nicht ins Log.
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
