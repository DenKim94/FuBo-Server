package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.utils.TokenGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Spielerverwaltung aus {@code S2b_UMSETZUNG.md}, Abschnitt 8: anlegen, entfernen,
 * sperren und wieder freigeben.
 *
 * <p>Der Pruefgegenstand ist weniger der Datenbankzugriff als die Abgrenzung: was das
 * Entfernen darf und wo es abzulehnen hat, und dass eine Sperre <i>sofort</i> wirkt und nicht
 * erst mit dem Ablauf der Sitzung.
 *
 * <p>Die angelegten Profile tragen einen eigenen Namensraum ("Pruefspieler"), damit sie nicht
 * mit den Demodaten kollidieren - deren Profile heissen "Beispielspieler n".
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SpielerControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ----------------------------------------------------------------------- Anlegen

    /**
     * Ohne Angabe gelten die Vorgaben der Stufe {@code MITTEL} aus {@code profil.gast_vorlage}
     * - nicht Nullen. Ein Profil mit lauter Nullen bekaeme in der Teamgenerierung ein Team
     * ohne jede Staerke zugeteilt, ohne dass jemand den Grund saehe.
     */
    @Test
    void anlegenOhneSkillsSetztDieVorgabenAusStufeMittel() throws Exception {
        String antwort = anlegen("{\"name\":\"Pruefspieler A\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"name\":\"Pruefspieler A\"").contains("\"spielerId\":");

        Long id = spielerId("Pruefspieler A");
        Map<String, Object> skills = jdbc.queryForMap(
                "SELECT count(*) AS anzahl, sum(wert) AS summe FROM profil.spieler_skill WHERE spieler_id = ?",
                id);

        assertThat(skills.get("anzahl")).as("fuenf aktive Kategorien").isEqualTo(5L);
        assertThat(((Number) skills.get("summe")).intValue())
                .as("vier Kategorien auf 3, Torwart auf 0")
                .isEqualTo(12);

        Map<String, Object> profil = jdbc.queryForMap(
                "SELECT rolle, aktiv FROM profil.spieler WHERE id = ?", id);
        assertThat(profil.get("rolle")).isEqualTo(Rolle.USER.name());
        assertThat(profil.get("aktiv")).isEqualTo(true);
    }

    /**
     * Eine teilweise Angabe genuegt: Genannte Kategorien gewinnen, der Rest bleibt auf der
     * Vorgabe. Sonst muesste der Aufrufer alle Kategorien kennen.
     */
    @Test
    void angegebeneSkillwerteUeberschreibenDieVorgabe() throws Exception {
        anlegen("{\"name\":\"Pruefspieler B\",\"skills\":{\"ANGRIFF\":5,\"TORWART\":2}}")
                .andExpect(status().isCreated());

        Long id = spielerId("Pruefspieler B");
        assertThat(skillwert(id, "ANGRIFF")).isEqualTo(5);
        assertThat(skillwert(id, "TORWART")).isEqualTo(2);
        assertThat(skillwert(id, "LAUFSTAERKE")).as("nicht genannt, also Vorgabe").isEqualTo(3);
    }

    /** Kleinschreibung ist zulaessig - ein 400 dafuer waere Schikane ohne Sicherheitsgewinn. */
    @Test
    void kategorienDuerfenKleingeschriebenWerden() throws Exception {
        anlegen("{\"name\":\"Pruefspieler C\",\"skills\":{\"angriff\":6}}")
                .andExpect(status().isCreated());

        assertThat(skillwert(spielerId("Pruefspieler C"), "ANGRIFF")).isEqualTo(6);
    }

    /** Der Service prueft gegen profil.skill_kategorie und nennt die betroffene Kategorie. */
    @Test
    void unbekannteKategorieLiefert400() throws Exception {
        String antwort = anlegen("{\"name\":\"Pruefspieler D\",\"skills\":{\"KOPFBALL\":4}}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("KOPFBALL");
        assertThat(existiert("Pruefspieler D")).as("Erst pruefen, dann schreiben").isFalse();
    }

    /**
     * Jede Kategorie hat ihren eigenen Bereich: Torwart geht nur bis 3. Der Trigger in der
     * Datenbank bliebe die letzte Instanz, braechte aber einen 500 statt einer Meldung, die
     * die Kategorie nennt.
     */
    @Test
    void torwartWertUeberDreiLiefert400() throws Exception {
        String antwort = anlegen("{\"name\":\"Pruefspieler E\",\"skills\":{\"TORWART\":4}}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("TORWART").contains("0 bis 3");
        assertThat(existiert("Pruefspieler E")).isFalse();
    }

    /** Der Name ist eindeutig - unabhaengig von Gross- und Kleinschreibung. */
    @Test
    void belegterNameLiefert409() throws Exception {
        anlegen("{\"name\":\"Pruefspieler F\"}").andExpect(status().isCreated());

        String antwort = anlegen("{\"name\":\"pruefspieler f\"}")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"NAME_BELEGT\"");
    }

    // ---------------------------------------------------------------------- Entfernen

    /** Das Profil verschwindet, die Skillwerte gehen per ON DELETE CASCADE mit. */
    @Test
    void entfernenLoeschtProfilUndSkillwerte() throws Exception {
        anlegen("{\"name\":\"Pruefspieler G\"}").andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler G");

        entfernen(id).andExpect(status().isNoContent());

        assertThat(existiert("Pruefspieler G")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler_skill WHERE spieler_id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.audit_log WHERE aktion = 'PROFIL_ENTFERNT' AND entitaet_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    /**
     * Offene Sitzungen sind fluechtig und werden mit abgeraeumt - sonst scheiterte das
     * {@code DELETE} an {@code fk_session_spieler}.
     */
    @Test
    void entfernenRaeumtDieSitzungenDesProfilsMitAb() throws Exception {
        anlegen("{\"name\":\"Pruefspieler H\"}").andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler H");
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, id, Rolle.USER);

        entfernen(id).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash(token))).isZero();
    }

    /**
     * Sobald ein Beleg auf das Profil verweist, bleibt es bestehen. Das Audit-Log genuegt
     * dafuer - ein Loeschen vernichtete Belege, auf die sich andere Datensaetze berufen.
     */
    @Test
    void entfernenEinesVerwendetenProfilsLiefert409() throws Exception {
        anlegen("{\"name\":\"Pruefspieler I\"}").andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler I");

        jdbc.update("""
                INSERT INTO profil.audit_log (akteur_spieler_id, akteur_bezeichnung, aktion)
                     VALUES (?, 'pruef', 'ADMIN_ANGEMELDET')
                """, id);

        String antwort = entfernen(id)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"PROFIL_IN_VERWENDUNG\"");
        assertThat(existiert("Pruefspieler I")).isTrue();
    }

    /** Ohne Adminprofil kaeme niemand mehr in den Adminbereich. */
    @Test
    void adminprofilLaesstSichWederEntfernenNochSperren() throws Exception {
        String beimEntfernen = entfernen(adminSpielerId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(beimEntfernen).contains("\"code\":\"PROFIL_GESCHUETZT\"");

        String beimSperren = blockieren(adminSpielerId(), true)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(beimSperren).contains("\"code\":\"PROFIL_GESCHUETZT\"");
    }

    /** Unbekannte Id: 404, nicht 400 - die Eingabeform war ja in Ordnung. */
    @Test
    void unbekanntesProfilLiefert404() throws Exception {
        entfernen(999_999L).andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------- Blockieren

    /**
     * Die Sperre wirkt sofort: Die offenen Sitzungen sind widerrufen, und der Name
     * verschwindet aus der Auswahl. Ohne den Widerruf bliebe der Gesperrte bis zum Ablauf
     * seiner Sitzung angemeldet - die Sperre wirkte gerade dann nicht, wenn sie gebraucht wird.
     */
    @Test
    void blockierenWiderruftDieSitzungenUndVerstecktDenNamen() throws Exception {
        anlegen("{\"name\":\"Pruefspieler J\"}").andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler J");
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, id, Rolle.USER);

        blockieren(id, true).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT aktiv FROM profil.spieler WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE token_hash = ?",
                Boolean.class, TokenGenerator.hash(token))).isTrue();

        assertThat(namensliste()).doesNotContain("Pruefspieler J");
    }

    /**
     * Die Gegenrichtung. Ohne sie kaeme der Admin an ein versehentlich gesperrtes Profil bis
     * S3 nicht mehr heran.
     */
    @Test
    void freigebenMachtDasProfilWiederWaehlbar() throws Exception {
        anlegen("{\"name\":\"Pruefspieler K\"}").andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler K");

        blockieren(id, true).andExpect(status().isNoContent());
        blockieren(id, false).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT aktiv FROM profil.spieler WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(namensliste()).contains("Pruefspieler K");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log
                 WHERE aktion = 'PROFIL_FREIGEGEBEN' AND entitaet_id = ?
                """, Integer.class, id)).isEqualTo(1);
    }

    /** Wiederholbar: Zweimal sperren aendert nichts und ist trotzdem kein Fehler. */
    @Test
    void zweimalSperrenIstFolgenlos() throws Exception {
        anlegen("{\"name\":\"Pruefspieler L\"}").andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler L");

        blockieren(id, true).andExpect(status().isNoContent());
        blockieren(id, true).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT aktiv FROM profil.spieler WHERE id = ?", Boolean.class, id))
                .isFalse();
    }

    /**
     * Ein fehlendes Feld darf nicht stillschweigend als "freigeben" gelten - deshalb ist
     * {@code blockieren} ein {@code Boolean} und kein {@code boolean}.
     */
    @Test
    void fehlendeRichtungLiefert400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/user/blockieren")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.45")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spielerId\":1}"))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------- Berechtigung

    /** Der Adminbereich ist fuer Spieler geschlossen. */
    @Test
    void spielerDarfKeineProfileAnlegen() throws Exception {
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);

        mockMvc.perform(post("/api/v1/admin/user/anlegen")
                        .cookie(new Cookie(COOKIE, token))
                        .header("CF-Connecting-IP", "198.51.100.46")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pruefspieler M\"}"))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private ResultActions anlegen(String koerper) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/user/anlegen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.47")
                .contentType(MediaType.APPLICATION_JSON)
                .content(koerper));
    }

    private ResultActions entfernen(Long id) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/user/entfernen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.48")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spielerId\":%d}".formatted(id)));
    }

    private ResultActions blockieren(Long id, boolean sperren) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/user/blockieren")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.49")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spielerId\":%d,\"blockieren\":%s}".formatted(id, sperren)));
    }

    private String namensliste() throws Exception {
        return mockMvc.perform(get("/api/v1/auth/users/lesen")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }

    private Long ersterSpieler() {
        return jdbc.queryForObject(
                "SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1",
                Long.class);
    }

    private Long spielerId(String name) {
        return jdbc.queryForObject("SELECT id FROM profil.spieler WHERE name = ?", Long.class, name);
    }

    private boolean existiert(String name) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler WHERE name = ?", Integer.class, name) > 0;
    }

    private int skillwert(Long spielerId, String kategorie) {
        return jdbc.queryForObject(
                "SELECT wert FROM profil.spieler_skill WHERE spieler_id = ? AND kategorie = ?",
                Integer.class, spielerId, kategorie);
    }
}
