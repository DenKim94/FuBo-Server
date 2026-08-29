package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den Kategorien-Endpunkt aus {@code S3_UMSETZUNG.md}, Abschnitt 4.
 *
 * <p>Der Pruefgegenstand ist schmal, aber nicht trivial: Das Adminformular baut seine
 * Eingabefelder aus dieser Antwort. Stimmen Reihenfolge oder Wertebereich nicht, entsteht ein
 * Formular, das Eingaben zulaesst, die der Server anschliessend ablehnt.
 *
 * <p><b>Die Erwartungen kommen aus der Datenbank, nicht aus einer Liste im Test.</b> Die
 * Kategorien sind datengetrieben; ein Test, der sie fest verdrahtet, muesste bei jeder
 * Datenaenderung mitgepflegt werden und pruefte dann nur noch sich selbst. Fest steht hier
 * ausschliesslich, was die Anforderung festlegt: der Torwart-Bereich 0 bis 3 und sein Gewicht
 * von 0.30 (A12, Zielfunktion des Teamgenerators).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SkillKategorieControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /**
     * Geliefert werden genau die aktiven Kategorien, sortiert nach {@code reihenfolge}.
     *
     * <p>Die Sortierung ist kein Beiwerk: Ohne sie entscheidet die Laune der Antwort ueber die
     * Reihenfolge der Formularfelder.
     */
    @Test
    void liefertAlleAktivenKategorienInReihenfolge() throws Exception {
        List<Map<String, Object>> kategorien = lesen();

        List<String> erwartet = jdbc.queryForList("""
                SELECT schluessel FROM profil.skill_kategorie WHERE aktiv ORDER BY reihenfolge
                """, String.class);

        assertThat(kategorien).extracting(k -> k.get("schluessel"))
                .containsExactlyElementsOf(erwartet);
    }

    /**
     * Der Torwart-Bereich ist 0 bis 3, sein Gewicht 0.30 - beides aus der Datenbank, kein
     * Sonderfall im Code.
     *
     * <p>Das Gewicht wird mitgeliefert, obwohl das Formular es nicht braucht: Es erklaert dem
     * Admin, warum Torwart anders zaehlt, und ist die einzige Stelle, an der diese Zahl je
     * sichtbar wird.
     */
    @Test
    void torwartTraegtBereichNullBisDreiUndGewichtNullDrei() throws Exception {
        Map<String, Object> torwart = lesen().stream()
                .filter(k -> "TORWART".equals(k.get("schluessel")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Keine Kategorie TORWART in der Antwort."));

        assertThat(((Number) torwart.get("minWert")).intValue()).isZero();
        assertThat(((Number) torwart.get("maxWert")).intValue()).isEqualTo(3);
        assertThat(((Number) torwart.get("gewicht")).doubleValue()).isEqualTo(0.30);
        assertThat(torwart.get("bezeichnung")).isNotNull();
    }

    /**
     * Eine abgeschaltete Kategorie erscheint nicht - weder im Formular noch spaeter in der
     * Zielfunktion. Die zugehoerigen Skillzeilen bleiben in der Datenbank erhalten.
     */
    @Test
    void abgeschalteteKategorieErscheintNicht() throws Exception {
        jdbc.update("UPDATE profil.skill_kategorie SET aktiv = false WHERE schluessel = 'TORWART'");

        assertThat(lesen()).extracting(k -> k.get("schluessel")).doesNotContain("TORWART");
    }

    /**
     * Der Endpunkt verraet den Wertebereich und damit den Aufbau der Bewertung. Ein normaler
     * Nutzer sieht nie Skills (A12); ihm den Rahmen dieser Skills zu zeigen waere die halbe
     * Auskunft.
     */
    @Test
    void mitUserSitzungLiefert403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/skills/lesen")
                        .cookie(new Cookie(COOKIE, userSitzung())))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private List<Map<String, Object>> lesen() throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/admin/skills/lesen")
                        .cookie(new Cookie(COOKIE, adminSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    private String adminSitzung() {
        Long adminId = jdbc.queryForObject(
                "SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminId, Rolle.ADMIN);
    }

    private String userSitzung() {
        Long spielerId = jdbc.queryForObject(
                "SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1",
                Long.class);
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId, Rolle.USER);
    }
}
