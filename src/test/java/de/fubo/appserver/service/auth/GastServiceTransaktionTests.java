package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.utils.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft das Transaktionsverhalten des Gast-Logins.
 *
 * <p><b>Diese Klasse traegt bewusst kein {@code @Transactional}</b> - genau wie
 * {@code AuditServiceTests} und aus demselben Grund: Der gepruefte Punkt <i>ist</i> der
 * Rollback. Mit einer umgebenden Test-Transaktion schloesse sich der Service ihr ueber
 * {@code REQUIRED} an; sein Rollback faende dann nicht statt, sondern verschoebe sich ans
 * Testende, und die Pruefung waere vorbestimmt gruen, ohne etwas zu belegen.
 *
 * <p>Die Zeilen werden dafuer von Hand aufgeraeumt: Die Ids der angelegten Sitzungen werden
 * mitgeschrieben, die Gastnamen tragen zusaetzlich ein erkennbares Praefix.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GastServiceTransaktionTests {

    /** Erkennungsmerkmal der von diesem Test erzeugten Zeilen. */
    private static final String PRAEFIX = "Rollbacktest ";

    private static final String SQL_SLOT_FREIGEBEN = """
            UPDATE profil.gast_slot
               SET belegt = FALSE, session_id = NULL, belegt_seit = NULL
             WHERE session_id = ?
            """;

    private static final String SQL_FREIE_SLOTS = """
            SELECT count(*)
              FROM profil.gast_slot
             WHERE NOT belegt
               AND id <= (SELECT anz_guests FROM configs.app_config WHERE id = 1)
            """;

    @Autowired
    private GastService gastService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> angelegteSitzungen = new ArrayList<>();

    @AfterEach
    void aufraeumen() {
        for (Long id : angelegteSitzungen) {
            // Erst den Platz freigeben: fk_gast_slot_session hat kein ON DELETE, ein DELETE
            // auf einer noch referenzierten Sitzung scheiterte sonst.
            jdbc.update(SQL_SLOT_FREIGEBEN, id);
            jdbc.update("DELETE FROM profil.session WHERE id = ?", id);
        }
        angelegteSitzungen.clear();
    }

    /**
     * Sind alle Plaetze belegt, darf auch der Stufenwechsel nicht bestehen bleiben.
     *
     * <p>Ohne die gemeinsame Transaktion bliebe eine halb angemeldete Sitzung zurueck: in
     * der Stufe {@code PROFILE_AUTHENTICATED} mit der Rolle {@code GAST}, aber ohne Platz.
     * Sie zaehlte damit als angemeldeter Gast, ohne einen der vier Plaetze zu belegen - die
     * Obergrenze aus A17 waere ausgehebelt.
     */
    @Test
    void ohneFreienPlatzBleibtDieSitzungInStufeEins() {
        int frei = jdbc.queryForObject(SQL_FREIE_SLOTS, Integer.class);

        for (int nummer = 1; nummer <= frei; nummer++) {
            gastService.alsGastAnmelden(neueSitzung(), PRAEFIX + nummer, GastStufe.MITTEL);
        }

        Long sitzungsId = neueSitzung();

        assertThatThrownBy(() ->
                gastService.alsGastAnmelden(sitzungsId, PRAEFIX + "ohne Platz", GastStufe.MITTEL))
                .isInstanceOf(FachlicherFehler.class);

        Map<String, Object> zeile = jdbc.queryForMap(
                "SELECT stage, rolle, gast_name FROM profil.session WHERE id = ?", sitzungsId);

        assertThat(zeile.get("stage")).isEqualTo(Stage.PIN_VERIFIED.name());
        assertThat(zeile.get("rolle")).isNull();
        assertThat(zeile.get("gast_name")).isNull();
    }

    /**
     * Das Abmelden gibt den Platz wirklich frei - hier ohne Test-Transaktion und damit
     * gegen den festgeschriebenen Stand geprueft.
     */
    @Test
    void abmeldenGibtDenPlatzFestgeschriebenFrei() {
        Long sitzungsId = neueSitzung();
        gastService.alsGastAnmelden(sitzungsId, PRAEFIX + "Abmeldung", GastStufe.MITTEL);

        assertThat(belegtDurch(sitzungsId)).isEqualTo(1);

        sessionService.abmelden(sitzungsId);

        assertThat(belegtDurch(sitzungsId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE id = ?",
                Boolean.class, sitzungsId)).isTrue();
    }

    /** Legt eine Sitzung in Stufe 1 an und merkt sie sich fuer das Aufraeumen. */
    private Long neueSitzung() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        Long id = jdbc.queryForObject("SELECT id FROM profil.session WHERE token_hash = ?",
                Long.class, TokenGenerator.hash(token));
        angelegteSitzungen.add(id);
        return id;
    }

    private int belegtDurch(Long sitzungsId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM profil.gast_slot WHERE session_id = ?",
                Integer.class, sitzungsId);
    }
}
