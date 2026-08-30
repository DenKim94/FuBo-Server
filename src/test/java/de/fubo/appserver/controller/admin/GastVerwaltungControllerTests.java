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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die beiden Gastverwaltungs-Endpunkte (A17, Vorgabe des Haupt-Entwicklers vom
 * 30.08.2026).
 *
 * <p>Die Klasse traegt {@code @Transactional}: Sie belegt und leert Gastplaetze und senkt in
 * einem Fall {@code anz_guests}. Beides ist anwendungsweit sichtbar und liefe sonst
 * {@code GastControllerTests} in die Quere, das sich auf vier freie Plaetze verlaesst.
 *
 * <p><b>Der Pruefgegenstand ist der verwaiste Platz</b> - belegt, aber ohne lebende Sitzung.
 * Er entsteht im Betrieb regelmaessig, weil eine ablaufende Gastsitzung ihren Platz nicht von
 * selbst freigibt, und ist der Grund, aus dem es diese Endpunkte gibt.
 *
 * <p>Gastnamen sind neutral ("Testgast ..."), Adressen liegen in {@code 203.0.113.0/24}
 * (RFC 5737). Keine realen Personennamen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GastVerwaltungControllerTests {

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

    // --------------------------------------------------------------------- Lesen

    /** Ohne angemeldete Gaeste sind alle vier Plaetze frei und wirksam. */
    @Test
    void lesenLiefertAlleVierPlaetzeFrei() throws Exception {
        List<Map<String, Object>> plaetze = lesen();

        assertThat(plaetze).hasSize(4);
        assertThat(plaetze).allSatisfy(platz -> {
            assertThat(platz.get("belegt")).isEqualTo(false);
            assertThat(platz.get("wirksam")).isEqualTo(true);
            assertThat(platz.get("gastName")).isNull();
            assertThat(platz.get("sitzungGueltig")).as("kein Gast, keine Aussage").isNull();
        });
        assertThat(plaetze.get(0).get("anzeigeName")).isEqualTo("Gast 1");
    }

    /** Ein angemeldeter Gast erscheint mit Name, Stufe und laufender Sitzung. */
    @Test
    void lesenZeigtDenAngemeldetenGast() throws Exception {
        gastAnmelden("Testgast Anton", "STARK");

        Map<String, Object> belegt = ersterBelegter();

        assertThat(belegt.get("gastName")).isEqualTo("Testgast Anton");
        assertThat(belegt.get("gastStufe")).isEqualTo("STARK");
        assertThat(belegt.get("sitzungGueltig")).isEqualTo(true);
        assertThat(belegt.get("belegtSeit")).isNotNull();
    }

    /**
     * Der eigentliche Zweck: ein belegter Platz, dessen Sitzung nicht mehr laeuft.
     *
     * <p>Eine ablaufende Gastsitzung gibt ihren Platz <b>nicht</b> von selbst frei - das tut erst
     * der naechtliche Aufraeumlauf. Bis dahin ist der Platz weg, ohne dass jemand dahintersitzt,
     * und genau das soll die Liste zeigen.
     */
    @Test
    void lesenZeigtDenVerwaistenPlatz() throws Exception {
        gastAnmelden("Testgast Berta", "MITTEL");
        sitzungenAblaufenLassen();

        Map<String, Object> belegt = ersterBelegter();

        assertThat(belegt.get("belegt")).as("Platz weiterhin besetzt").isEqualTo(true);
        assertThat(belegt.get("sitzungGueltig")).as("aber niemand mehr dahinter").isEqualTo(false);
        assertThat(belegt.get("gastName")).isEqualTo("Testgast Berta");
    }

    /**
     * Plaetze jenseits von {@code anzGuests} stehen in der Liste, sind aber unwirksam.
     *
     * <p>Sie entstehen, sobald der Admin die Zahl senkt; geloescht wird nie. Sie zu verschweigen
     * hiesse, den Bestand unvollstaendig zu zeigen.
     */
    @Test
    void lesenMarkiertUnwirksamePlaetze() throws Exception {
        jdbc.update("UPDATE configs.app_config SET anz_guests = 2 WHERE id = 1");

        List<Map<String, Object>> plaetze = lesen();

        assertThat(plaetze).hasSize(4);
        assertThat(plaetze.get(0).get("wirksam")).isEqualTo(true);
        assertThat(plaetze.get(1).get("wirksam")).isEqualTo(true);
        assertThat(plaetze.get(2).get("wirksam")).as("Nummer 3 ueber der Grenze").isEqualTo(false);
        assertThat(plaetze.get(3).get("wirksam")).isEqualTo(false);
    }

    // --------------------------------------------------------------------- Freigeben

    /** Ein genannter Platz wird frei, und die Sitzung des Gastes ist widerrufen. */
    @Test
    void freigebenEinesPlatzesMeldetDenGastAb() throws Exception {
        gastAnmelden("Testgast Cesar", "SCHWACH");
        int nummer = ((Number) ersterBelegter().get("nummer")).intValue();
        Long sessionId = sessionIdVon(nummer);

        freigeben("{\"slotIds\":[" + nummer + "]}").andExpect(status().isNoContent());

        assertThat(belegteSlots()).isZero();
        assertThat(widerrufen(sessionId)).as("Sitzung mit widerrufen").isTrue();
    }

    /** {@code alle: true} raeumt jeden belegten Platz. */
    @Test
    void freigebenAllerPlaetze() throws Exception {
        for (int nummer = 1; nummer <= 4; nummer++) {
            gastAnmelden("Testgast " + nummer, "MITTEL");
        }
        assertThat(belegteSlots()).isEqualTo(4);

        freigeben("{\"alle\":true}").andExpect(status().isNoContent());

        assertThat(belegteSlots()).isZero();
    }

    /**
     * Ein leerer Koerper wird abgelehnt, statt "alle" zu bedeuten.
     *
     * <p>Ein Sammelabbruch soll kein Versehen sein koennen - er wirft im Zweifel vier
     * angemeldete Gaeste heraus.
     */
    @Test
    void freigebenOhneAngabeLiefert400() throws Exception {
        gastAnmelden("Testgast Dora", "MITTEL");

        String antwort = freigeben("{}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"");
        assertThat(belegteSlots()).as("nichts angetastet").isEqualTo(1);
    }

    /** Beides zugleich ist ebenfalls keine gueltige Angabe. */
    @Test
    void freigebenMitBeidenFeldernLiefert400() throws Exception {
        freigeben("{\"slotIds\":[1],\"alle\":true}")
                .andExpect(status().isBadRequest());
    }

    /** Eine Nummer, die es nicht gibt, wird gemeldet - samt der vorhandenen. */
    @Test
    void unbekanntePlatznummerLiefert400() throws Exception {
        String antwort = freigeben("{\"slotIds\":[99]}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("99").contains("Vorhanden");
    }

    /** Ein bereits freier Platz wird uebersprungen; der Aufruf ist wiederholbar. */
    @Test
    void freigebenEinesFreienPlatzesIstFolgenlos() throws Exception {
        freigeben("{\"slotIds\":[1,2]}").andExpect(status().isNoContent());

        assertThat(belegteSlots()).isZero();
    }

    // --------------------------------------------------------------------- Audit

    /**
     * Der Eintrag nennt die betroffenen Plaetze als echtes JSON-Array.
     *
     * <p><b>{@code jsonb_typeof} gehoert dazu.</b> Der Serialisierer des {@code AuditService} ist
     * handgeschrieben und kannte bis zum 30.08.2026 keine Listen - eine Liste waere in ihrer
     * {@code toString}-Form gelandet: ein Text, der wie JSON aussieht und keines ist. Derselbe
     * Fallstrick, der in S3 schon einmal bei den Karten zugeschlagen hat.
     *
     * <p>{@code warAktiv} unterscheidet das Aufraeumen einer verwaisten Zeile vom Hinauswerfen
     * eines anwesenden Gastes - zwei sehr verschiedene Vorgaenge unter demselben Aufruf.
     */
    @Test
    void freigebenErzeugtAuditEintragMitPlatzliste() throws Exception {
        gastAnmelden("Testgast Emil", "MITTEL");

        freigeben("{\"alle\":true}").andExpect(status().isNoContent());

        Map<String, Object> eintrag = jdbc.queryForMap("""
                SELECT entitaet,
                       entitaet_id,
                       jsonb_typeof(details->'plaetze')        AS typ,
                       jsonb_array_length(details->'plaetze')  AS anzahl,
                       details->'plaetze'->0->>'gastName'      AS gast,
                       details->'plaetze'->0->>'warAktiv'      AS war_aktiv
                  FROM profil.audit_log
                 WHERE aktion = 'GAST_ABGEMELDET'
                 ORDER BY id DESC
                 LIMIT 1
                """);

        assertThat(eintrag.get("entitaet")).isEqualTo("gast_slot");
        assertThat(eintrag.get("entitaet_id")).as("mehrere Zeilen, keine einzelne Id").isNull();
        assertThat(eintrag.get("typ")).as("Array, nicht Text").isEqualTo("array");
        assertThat(((Number) eintrag.get("anzahl")).intValue()).isEqualTo(1);
        assertThat(eintrag.get("gast")).isEqualTo("Testgast Emil");
        assertThat(eintrag.get("war_aktiv")).isEqualTo("true");
    }

    // --------------------------------------------------------------------- Autorisierung

    /** Beide Endpunkte sind fuer eine Spielersitzung gesperrt. */
    @Test
    void mitUserSitzungLiefernBeideEndpunkte403() throws Exception {
        String token = userSitzung();

        mockMvc.perform(get("/api/v1/admin/gast/lesen").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/gast/freigeben")
                        .cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alle\":true}"))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private List<Map<String, Object>> lesen() throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/admin/gast/lesen")
                        .cookie(new Cookie(COOKIE, adminSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    private Map<String, Object> ersterBelegter() throws Exception {
        return lesen().stream()
                .filter(platz -> Boolean.TRUE.equals(platz.get("belegt")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein belegter Platz in der Antwort."));
    }

    private ResultActions freigeben(String koerper) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/gast/freigeben")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "203.0.113.42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(koerper));
    }

    /** Meldet einen Gast ueber den regulaeren Weg an - erst PIN-Stufe, dann Gast. */
    private void gastAnmelden(String name, String stufe) throws Exception {
        mockMvc.perform(post("/api/v1/auth/gast/anmelden")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gastName\":\"%s\",\"stufe\":\"%s\"}".formatted(name, stufe)))
                .andExpect(status().isNoContent());
    }

    /**
     * Laesst alle Gastsitzungen ablaufen, ohne die Plaetze anzufassen.
     *
     * <p>Genau der Zustand, den der Aufraeumlauf erst nachts aufloest - und ohne
     * {@code Thread.sleep}, weil die Ablaufzeitpunkte direkt gesetzt werden.
     */
    private void sitzungenAblaufenLassen() {
        jdbc.update("""
                UPDATE profil.session
                   SET gueltig_bis = now() - interval '1 minute'
                 WHERE gast_name IS NOT NULL
                """);
    }

    private Long sessionIdVon(int nummer) {
        return jdbc.queryForObject(
                "SELECT session_id FROM profil.gast_slot WHERE id = ?", Long.class, nummer);
    }

    private boolean widerrufen(Long sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE id = ?",
                Boolean.class, sessionId));
    }

    private int belegteSlots() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM profil.gast_slot WHERE belegt", Integer.class);
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
