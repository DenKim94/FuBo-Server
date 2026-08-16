package de.fubo.appserver.controller.auth;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft Namensliste, Belegtstatus und Namensauswahl aus {@code S2_UMSETZUNG.md},
 * Abschnitt 7.
 *
 * <p>Die Testdaten stammen aus {@code R__seed_beispielprofile.sql} und enthalten keine
 * realen Personennamen. Jeder Test laeuft in einer eigenen Transaktion, die anschliessend
 * zurueckgerollt wird - auch die hier vorgenommenen Aenderungen an {@code profil.spieler}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class NamenControllerTests {

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

    // --------------------------------------------------------------------- Namensliste

    /**
     * Der Endpunkt ist bereits in der Stufe {@code PIN_VERIFIED} erreichbar und damit die
     * am weitesten geoeffnete Stelle der API. Er darf deshalb unter keinen Umstaenden
     * Skillwerte enthalten (A12).
     */
    @Test
    void namenslisteLiefertAktiveProfileOhneSkillwerte() throws Exception {
        Map<String, Object> spieler = ersterAktiverSpieler();

        String antwort = mockMvc.perform(get("/api/v1/auth/users/lesen")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains((String) spieler.get("name"));
        assertThat(antwort.toLowerCase())
                .doesNotContain("skill")
                .doesNotContain("angriff")
                .doesNotContain("torwart");
    }

    /** Der Belegtstatus wird aus den aktiven Sitzungen abgeleitet, nicht gespeichert (A6). */
    @Test
    void aktiveSitzungMachtDenNamenBelegt() throws Exception {
        Map<String, Object> angemeldet = ersterAktiverSpieler();
        Map<String, Object> frei = zweiterAktiverSpieler();

        sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, (Long) angemeldet.get("id"), Rolle.USER);

        String antwort = mockMvc.perform(get("/api/v1/auth/users/lesen")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(eintragFuer(antwort, (String) angemeldet.get("name"))).contains("\"belegt\":true");
        assertThat(eintragFuer(antwort, (String) frei.get("name"))).contains("\"belegt\":false");
    }

    /** Deaktivierte Profile stehen nicht zur Auswahl. */
    @Test
    void inaktivesProfilErscheintNichtInDerListe() throws Exception {
        Map<String, Object> spieler = ersterAktiverSpieler();
        jdbc.update("UPDATE profil.spieler SET aktiv = FALSE WHERE id = ?", spieler.get("id"));

        String antwort = mockMvc.perform(get("/api/v1/auth/users/lesen")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).doesNotContain((String) spieler.get("name"));
    }

    // ------------------------------------------------------------------ Namensauswahl

    /**
     * Der Kernfall des Stufenwechsels: Die Sitzung behaelt ihre {@code id} - und damit
     * spaeter die Verknuepfung zum Gast-Slot -, bekommt aber einen neuen Token. Der alte
     * ist danach wertlos (Schutz vor Session Fixation).
     */
    @Test
    void namensauswahlSetztDieStufeUndRotiertDenToken() throws Exception {
        String alterToken = pinVerifiedSitzung();
        Long sitzungsId = sitzungsIdZu(alterToken);
        Map<String, Object> spieler = ersterAktiverSpieler();

        MvcResult ergebnis = mockMvc.perform(nameAuswahl(alterToken, (Long) spieler.get("id")))
                .andExpect(status().isNoContent())
                .andReturn();

        String neuerToken = tokenAus(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(neuerToken).isNotEqualTo(alterToken);

        Map<String, Object> zeile = jdbc.queryForMap("""
                SELECT id, stage, rolle, spieler_id FROM profil.session WHERE token_hash = ?
                """, TokenGenerator.hash(neuerToken));

        assertThat(zeile.get("id")).isEqualTo(sitzungsId);
        assertThat(zeile.get("stage")).isEqualTo(Stage.PROFILE_AUTHENTICATED.name());
        assertThat(zeile.get("rolle")).isEqualTo(Rolle.USER.name());
        assertThat(zeile.get("spieler_id")).isEqualTo(spieler.get("id"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash(alterToken)))
                .as("Der alte Token darf zu keiner Sitzung mehr fuehren")
                .isZero();
    }

    /** Zwei Personen duerfen nicht denselben Namen belegen. */
    @Test
    void belegterNameLiefert409() throws Exception {
        Map<String, Object> spieler = ersterAktiverSpieler();
        sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, (Long) spieler.get("id"), Rolle.USER);

        String antwort = mockMvc.perform(nameAuswahl(pinVerifiedSitzung(), (Long) spieler.get("id")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"NAME_BELEGT\"");
    }

    /**
     * Eine unbekannte Id ist ein Fehler des Aufrufers, kein fachlicher Konflikt - deshalb
     * {@code 404} und nicht {@code 409}. Dass sich damit "Id existiert" von "Id existiert
     * nicht" unterscheiden laesst, ist unkritisch: Die gueltigen Ids stehen jeder Sitzung
     * in {@code PIN_VERIFIED} ohnehin in der Namensliste zur Verfuegung.
     */
    @Test
    void unbekanntesProfilLiefert404() throws Exception {
        String antwort = mockMvc.perform(nameAuswahl(pinVerifiedSitzung(), 999_999_999L))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"INHALT_NICHT_GEFUNDEN\"");
    }

    /** Ein deaktiviertes Profil ist fuer die Anmeldung dasselbe wie ein nicht vorhandenes. */
    @Test
    void inaktivesProfilLiefert404() throws Exception {
        Map<String, Object> spieler = ersterAktiverSpieler();
        jdbc.update("UPDATE profil.spieler SET aktiv = FALSE WHERE id = ?", spieler.get("id"));

        mockMvc.perform(nameAuswahl(pinVerifiedSitzung(), (Long) spieler.get("id")))
                .andExpect(status().isNotFound());
    }

    /** Fehlende Profil-Id: Bean Validation antwortet mit 400 und benennt das Feld. */
    @Test
    void fehlendeProfilIdLiefert400() throws Exception {
        String antwort = mockMvc.perform(post("/api/v1/auth/user/waehlen")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("spielerId");
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            nameAuswahl(String token, Long spielerId) {
        return post("/api/v1/auth/user/waehlen")
                .cookie(new Cookie(COOKIE, token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spielerId\":%d}".formatted(spielerId));
    }

    private String pinVerifiedSitzung() {
        return sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
    }

    private Long sitzungsIdZu(String token) {
        return jdbc.queryForObject("SELECT id FROM profil.session WHERE token_hash = ?",
                Long.class, TokenGenerator.hash(token));
    }

    private Map<String, Object> ersterAktiverSpieler() {
        return jdbc.queryForMap(
                "SELECT id, name FROM profil.spieler WHERE aktiv ORDER BY name LIMIT 1");
    }

    private Map<String, Object> zweiterAktiverSpieler() {
        return jdbc.queryForMap(
                "SELECT id, name FROM profil.spieler WHERE aktiv ORDER BY name OFFSET 1 LIMIT 1");
    }

    /**
     * Schneidet das JSON-Objekt zu einem Namen aus der Antwort.
     *
     * <p>Bewusst ohne JSON-Bibliothek und ohne Annahme ueber die Feldreihenfolge: geprueft
     * wird nur, dass genau der Abschnitt zwischen zwei Objektgrenzen den Namen enthaelt.
     */
    private static String eintragFuer(String json, String name) {
        for (String teil : json.split("\\},\\{")) {
            if (teil.contains("\"" + name + "\"")) {
                return teil;
            }
        }
        throw new AssertionError("Kein Eintrag fuer '" + name + "' in: " + json);
    }

    /** Schneidet den Tokenwert aus dem Set-Cookie-Header. */
    private static String tokenAus(String setCookieHeader) {
        assertThat(setCookieHeader).isNotNull();
        String ohneName = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);
        int ende = ohneName.indexOf(';');
        return ende < 0 ? ohneName : ohneName.substring(0, ende);
    }
}
