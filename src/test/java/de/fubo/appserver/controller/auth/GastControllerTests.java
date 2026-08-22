package de.fubo.appserver.controller.auth;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.GastStufe;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den Gast-Login aus {@code S2_UMSETZUNG.md}, Abschnitt 8: Stufenwechsel,
 * Token-Rotation, Belegung der festen Gastplaetze und deren Freigabe.
 *
 * <p>Die Gastnamen in diesem Test sind bewusst neutral ("Testgast ...") und enthalten
 * keine realen Personennamen.
 *
 * <p><b>Was hier bewusst nicht geprueft wird:</b> die Ruecknahme des Stufenwechsels, wenn
 * die Platzbelegung scheitert. Jeder Fall dieser Klasse laeuft in einer Test-Transaktion,
 * der sich der Service ueber {@code REQUIRED} anschliesst - ein Rollback im Service faende
 * hier gar nicht statt, sondern erst am Ende des Tests. Der Fall steht deshalb in
 * {@code GastServiceTransaktionTests}, die ohne {@code @Transactional} auskommt.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GastControllerTests {

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

    // --------------------------------------------------------------------- Erfolgsfall

    /**
     * Der Kernfall: Die Sitzung behaelt ihre {@code id} - daran haengt
     * {@code gast_slot.session_id} -, wechselt in die zweite Stufe und bekommt einen neuen
     * Token. Der alte ist danach wertlos.
     */
    @Test
    void gastAnmeldungSetztStufeRolleUndBelegtEinenSlot() throws Exception {
        String alterToken = pinVerifiedSitzung();
        Long sitzungsId = sitzungsIdZu(alterToken);

        MvcResult ergebnis = mockMvc.perform(gastAnmeldung(alterToken, "Testgast Anton", "STARK"))
                .andExpect(status().isNoContent())
                .andReturn();

        String neuerToken = tokenAus(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(neuerToken).isNotEqualTo(alterToken);

        Map<String, Object> zeile = jdbc.queryForMap("""
                SELECT id, stage, rolle, gast_name, gast_stufe, spieler_id
                  FROM profil.session WHERE token_hash = ?
                """, TokenGenerator.hash(neuerToken));

        assertThat(zeile.get("id")).isEqualTo(sitzungsId);
        assertThat(zeile.get("stage")).isEqualTo(Stage.PROFILE_AUTHENTICATED.name());
        assertThat(zeile.get("rolle")).isEqualTo(Rolle.GAST.name());
        assertThat(zeile.get("gast_name")).isEqualTo("Testgast Anton");
        assertThat(zeile.get("gast_stufe")).isEqualTo(GastStufe.STARK.name());
        assertThat(zeile.get("spieler_id")).as("Ein Gast hat kein Profil").isNull();

        assertThat(belegteSlots()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT session_id FROM profil.gast_slot WHERE belegt", Long.class))
                .isEqualTo(sitzungsId);
    }

    /**
     * Ohne Angabe gilt {@link GastStufe#MITTEL} (A17: die Zuweisung ist optional). Die
     * Ableitung steht im DTO und nicht im Service - sie gehoert zur Auslegung des
     * Anfragekoerpers.
     */
    @Test
    void ohneSelbsteinschaetzungGiltMittel() throws Exception {
        String token = pinVerifiedSitzung();
        // Die Id vor dem Aufruf merken: Die Anmeldung rotiert den Token, danach fuehrt
        // sitzungsIdZu(token) ins Leere - siehe Hinweis am Hilfsmittel.
        Long sitzungsId = sitzungsIdZu(token);

        mockMvc.perform(post("/api/v1/auth/gast/anmelden")
                        .cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gastName\":\"Testgast Berta\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT gast_stufe FROM profil.session WHERE id = ?", String.class, sitzungsId))
                .isEqualTo(GastStufe.MITTEL.name());
    }

    // --------------------------------------------------------------------- Slot-Grenze

    /**
     * Der fuenfte Gast wird abgewiesen. Die Grenze ergibt sich aus
     * {@code configs.app_config.anz_guests} (Vorgabe 4) und den festen Datensaetzen in
     * {@code profil.gast_slot} - nicht aus einer Zaehlabfrage, die bei gleichzeitigem
     * Login nicht sicher waere.
     */
    @Test
    void fuenfterGastLiefert409MitCodeKeinGastSlotFrei() throws Exception {
        for (int nummer = 1; nummer <= 4; nummer++) {
            mockMvc.perform(gastAnmeldung(pinVerifiedSitzung(), "Testgast " + nummer, "MITTEL"))
                    .andExpect(status().isNoContent());
        }
        assertThat(belegteSlots()).isEqualTo(4);

        String antwort = mockMvc.perform(gastAnmeldung(pinVerifiedSitzung(), "Testgast 5", "MITTEL"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"KEIN_GAST_SLOT_FREI\"");
    }

    /**
     * Der Platz wird beim Abmelden wieder frei. Ohne das liefen die vier Plaetze ueber die
     * Zeit voll - der Aufraeumjob greift erst nachts.
     */
    @Test
    void abmeldenGibtDenSlotWiederFrei() throws Exception {
        String alterToken = pinVerifiedSitzung();
        MvcResult anmeldung = mockMvc.perform(gastAnmeldung(alterToken, "Testgast Cesar", "SCHWACH"))
                .andExpect(status().isNoContent())
                .andReturn();

        String gastToken = tokenAus(anmeldung.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(belegteSlots()).isEqualTo(1);

        mockMvc.perform(post("/api/v1/auth/session/beenden")
                        .cookie(new Cookie(COOKIE, gastToken)))
                .andExpect(status().isNoContent());

        assertThat(belegteSlots()).isZero();
    }

    // --------------------------------------------------------------------- Namenspruefung

    /** Zwei Gaeste duerfen nicht denselben Namen tragen. */
    @Test
    void belegterGastnameLiefert409() throws Exception {
        mockMvc.perform(gastAnmeldung(pinVerifiedSitzung(), "Testgast Dora", "MITTEL"))
                .andExpect(status().isNoContent());

        String antwort = mockMvc.perform(gastAnmeldung(pinVerifiedSitzung(), "testgast dora", "MITTEL"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort)
                .as("Der Vergleich ignoriert Gross- und Kleinschreibung")
                .contains("\"code\":\"NAME_BELEGT\"");
    }

    /**
     * Ein Gast darf sich nicht wie ein angelegtes Profil nennen - sonst waeren die beiden
     * in Teilnehmerliste und Teameinteilung nicht mehr auseinanderzuhalten.
     */
    @Test
    void gastnameEinesProfilsLiefert409() throws Exception {
        String profilname = jdbc.queryForObject(
                "SELECT name FROM profil.spieler ORDER BY name LIMIT 1", String.class);

        String antwort = mockMvc.perform(gastAnmeldung(pinVerifiedSitzung(), profilname, "MITTEL"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"NAME_BELEGT\"");
    }

    /** Bean Validation vor jedem Datenbankzugriff: zu kurzer Name mit Feldangabe. */
    @Test
    void zuKurzerGastnameLiefert400() throws Exception {
        String antwort = mockMvc.perform(gastAnmeldung(pinVerifiedSitzung(), "A", "MITTEL"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("gastName");
    }

    /** Eine unbekannte Stufe ist ein Eingabefehler, kein Serverfehler. */
    @Test
    void unbekannteStufeLiefert400() throws Exception {
        mockMvc.perform(gastAnmeldung(pinVerifiedSitzung(), "Testgast Emil", "SEHR_STARK"))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private static MockHttpServletRequestBuilder gastAnmeldung(String token, String name, String stufe) {
        return post("/api/v1/auth/gast/anmelden")
                .cookie(new Cookie(COOKIE, token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gastName\":\"%s\",\"stufe\":\"%s\"}".formatted(name, stufe));
    }

    private String pinVerifiedSitzung() {
        return sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
    }

    /**
     * Liefert die Id der zum Token gehoerenden Sitzung.
     *
     * <p><b>Nur mit einem noch gueltigen Token aufrufen.</b> Die Suche laeuft ueber den
     * Token-Hash, und jeder Stufenwechsel rotiert den Token: Nach der Gast-Anmeldung findet
     * der alte Hash keine Zeile mehr, und die Abfrage endet in einer
     * {@code EmptyResultDataAccessException}. Wer die Id nach dem Aufruf braucht, merkt sie
     * sich vorher - die {@code session.id} bleibt bei der Rotation erhalten.
     */
    private Long sitzungsIdZu(String token) {
        return jdbc.queryForObject("SELECT id FROM profil.session WHERE token_hash = ?",
                Long.class, TokenGenerator.hash(token));
    }

    private int belegteSlots() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM profil.gast_slot WHERE belegt", Integer.class);
    }

    /** Schneidet den Tokenwert aus dem Set-Cookie-Header. */
    private static String tokenAus(String setCookieHeader) {
        assertThat(setCookieHeader).isNotNull();
        String ohneName = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);
        int ende = ohneName.indexOf(';');
        return ende < 0 ? ohneName : ohneName.substring(0, ende);
    }
}
