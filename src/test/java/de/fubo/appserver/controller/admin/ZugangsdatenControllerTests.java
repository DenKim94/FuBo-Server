package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.service.auth.PinService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Abschnitte 6 und 7 aus {@code S2b_UMSETZUNG.md}: Passwortaenderung im
 * angemeldeten Zustand und Wechsel der zentralen PIN.
 *
 * <p><b>Der Unterschied zwischen beiden ist der Umfang des Sitzungswiderrufs</b> und damit
 * der eigentliche Pruefgegenstand: Das Adminpasswort betrifft nur den Adminzugang, die
 * zentrale PIN betrifft alle. Beides steht so in offenem Punkt 5 der Anleitung.
 *
 * <p>Anders als {@code PasswortResetControllerTests} laeuft diese Klasse transaktional -
 * hier ist kein {@code REQUIRES_NEW} beteiligt.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ZugangsdatenControllerTests {

    private static final String COOKIE = "FUBO_SESSION";
    private static final String ALTES_PASSWORT = "pruef-admin-0815";
    private static final String NEUES_PASSWORT = "ein-langes-neues-passwort";

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private PinService pinService;

    @Autowired
    private BruteForceService bruteForceService;

    @Autowired
    private PasswordEncoder passwortEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        bruteForceService.alleZuruecksetzen();
        jdbc.update("UPDATE profil.admin_konto SET passwort_hash = ? WHERE id = 1",
                passwortEncoder.encode(ALTES_PASSWORT));
    }

    // --------------------------------------------------------------- Passwortaenderung

    /**
     * Die Sitzung beweist, <i>wer</i> handelt; das alte Passwort beweist, dass es der
     * Berechtigte selbst ist. Ein unbeaufsichtigter Rechner mit offener Sitzung soll nicht
     * genuegen.
     */
    @Test
    void passwortaenderungVerlangtDasAltePasswort() throws Exception {
        String antwort = mockMvc.perform(post("/api/v1/admin/passwort/aendern")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.30")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altesPasswort\":\"falsch-0000\",\"neuesPasswort\":\"%s\"}"
                                .formatted(NEUES_PASSWORT)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"ADMIN_PASSWORT_FALSCH\"");
        assertThat(passwortEncoder.matches(ALTES_PASSWORT, adminHash()))
                .as("Das Passwort bleibt unveraendert")
                .isTrue();
    }

    /**
     * Nach der Aenderung sind die Adminsitzungen widerrufen - auch die aufrufende, damit ein
     * Tippfehler im neuen Passwort sofort auffaellt und nicht erst Wochen spaeter. Die
     * Spielersitzungen bleiben unberuehrt.
     */
    @Test
    void passwortaenderungWiderruftNurDieAdminsitzungen() throws Exception {
        String adminToken = adminSitzung();
        String spielerToken = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);

        String cookie = mockMvc.perform(post("/api/v1/admin/passwort/aendern")
                        .cookie(new Cookie(COOKIE, adminToken))
                        .header("CF-Connecting-IP", "198.51.100.31")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altesPasswort\":\"%s\",\"neuesPasswort\":\"%s\"}"
                                .formatted(ALTES_PASSWORT, NEUES_PASSWORT)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(passwortEncoder.matches(NEUES_PASSWORT, adminHash())).isTrue();
        assertThat(cookie).as("Das Session-Cookie wird geloescht").contains("Max-Age=0");

        assertThat(widerrufen(adminToken)).isTrue();
        assertThat(widerrufen(spielerToken)).as("Spielersitzungen bleiben bestehen").isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT details->>'weg' FROM profil.audit_log WHERE aktion = 'PASSWORT_GEAENDERT'",
                String.class)).isEqualTo("aendern");
    }

    /** Dieselbe Untergrenze wie beim Reset - eine zweite Regel waere eine Fehlerquelle. */
    @Test
    void zuKurzesNeuesPasswortLiefert400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/passwort/aendern")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.32")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altesPasswort\":\"%s\",\"neuesPasswort\":\"kurz\"}"
                                .formatted(ALTES_PASSWORT)))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------- PIN-Aenderung

    /**
     * A3: Nach dem Wechsel der zentralen PIN ist <b>jede</b> Sitzung widerrufen - sonst
     * blieben Nutzer angemeldet, die nur die alte PIN kannten, und der Wechsel waere fuer die
     * Dauer ihrer Sitzung wirkungslos.
     */
    @Test
    void pinAenderungWiderruftAlleSitzungen() throws Exception {
        String adminToken = adminSitzung();
        String spielerToken = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);
        String offeneStufeEins = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);

        mockMvc.perform(post("/api/v1/admin/pin/aendern")
                        .cookie(new Cookie(COOKIE, adminToken))
                        .header("CF-Connecting-IP", "198.51.100.33")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuePin\":\"4829\"}"))
                .andExpect(status().isNoContent());

        assertThat(pinService.stimmt("4829")).as("Die neue PIN gilt").isTrue();
        assertThat(widerrufen(adminToken)).isTrue();
        assertThat(widerrufen(spielerToken)).isTrue();
        assertThat(widerrufen(offeneStufeEins)).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT geaendert_von FROM profil.zugangsdaten WHERE id = 1", Short.class))
                .as("geaendert_von bekommt hier zum ersten Mal einen Wert")
                .isEqualTo((short) 1);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.audit_log WHERE aktion = 'PIN_GEAENDERT'",
                Integer.class)).isEqualTo(1);
    }

    /** Genau vier Ziffern - Festlegung vom 23.08.2026. */
    @Test
    void pinMitFalscherStellenzahlLiefert400() throws Exception {
        for (String ungueltig : new String[]{"482", "48291", "abcd"}) {
            mockMvc.perform(post("/api/v1/admin/pin/aendern")
                            .cookie(new Cookie(COOKIE, adminSitzung()))
                            .header("CF-Connecting-IP", "198.51.100.34")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"neuePin\":\"%s\"}".formatted(ungueltig)))
                    .andExpect(status().isBadRequest());
        }
    }

    /** Der Adminbereich ist fuer Spieler geschlossen - die Filterchain laesst sie nicht durch. */
    @Test
    void spielerDarfDieZentralePinNichtAendern() throws Exception {
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);

        mockMvc.perform(post("/api/v1/admin/pin/aendern")
                        .cookie(new Cookie(COOKIE, token))
                        .header("CF-Connecting-IP", "198.51.100.35")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuePin\":\"4829\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ Anmeldename (S3)

    /**
     * Der Anmeldename wird geaendert - und <b>die Sitzung bleibt bestehen</b>.
     *
     * <p>Das ist der Unterschied zu den beiden Endpunkten darueber und eine ausdrueckliche
     * Vorgabe vom 29.08.2026: Der Name ist kein Geheimnis, dessen Bekanntwerden allein Zugang
     * verschafft; ein Widerruf wuerfe den Admin unmittelbar nach seiner eigenen Umbenennung
     * aus der Sitzung. Geprueft wird beides - dass kein Cookie geloescht wird und dass die
     * Sitzung danach noch traegt.
     */
    @Test
    void namensaenderungLaesstDieSitzungBestehen() throws Exception {
        String token = adminSitzung();

        MvcResult ergebnis = mockMvc.perform(post("/api/v1/admin/name/aendern")
                        .cookie(new Cookie(COOKIE, token))
                        .header("CF-Connecting-IP", "198.51.100.60")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuerName\":\"Pruefadmin Neu\"}"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .as("Kein Cookie-Wechsel - anders als bei der Passwortaenderung")
                .isNull();
        assertThat(widerrufen(token)).isFalse();

        assertThat(jdbc.queryForObject("SELECT name FROM profil.spieler WHERE id = ?",
                String.class, adminSpielerId()))
                .isEqualTo("Pruefadmin Neu");
    }

    /**
     * Nach der Umbenennung traegt der neue Name die Anmeldung - und der alte nicht mehr.
     * Damit ist die Kette geschlossen: Der Profilname <i>ist</i> der Anmeldename.
     */
    @Test
    void nachDerAenderungGiltDerNeueNameBeimAnmelden() throws Exception {
        String alterName = jdbc.queryForObject("SELECT name FROM profil.spieler WHERE id = ?",
                String.class, adminSpielerId());

        mockMvc.perform(post("/api/v1/admin/name/aendern")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.61")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuerName\":\"Pruefadmin Zwei\"}"))
                .andExpect(status().isNoContent());

        anmelden("Pruefadmin Zwei", "198.51.100.62").andExpect(status().isNoContent());
        anmelden(alterName, "198.51.100.63").andExpect(status().isUnauthorized());
    }

    /**
     * Randleerzeichen werden entfernt - <b>und das ist hier wesentlich</b>. Der
     * Anmelde-Endpunkt trimmt seine Eingabe ebenfalls; ein mit Leerzeichen gespeicherter Name
     * liesse sich nie eingeben, und der Admin haette keinen Rueckweg.
     */
    @Test
    void randleerzeichenWerdenEntferntUndDerNameBleibtEingebbar() throws Exception {
        mockMvc.perform(post("/api/v1/admin/name/aendern")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.64")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuerName\":\"  Pruefadmin Drei  \"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT name FROM profil.spieler WHERE id = ?",
                String.class, adminSpielerId()))
                .as("gespeichert wird ohne Randleerzeichen")
                .isEqualTo("Pruefadmin Drei");

        anmelden("Pruefadmin Drei", "198.51.100.65").andExpect(status().isNoContent());
    }

    /** Der Name eines anderen Profils ist belegt - {@code uq_spieler_name} liesse ihn nicht zu. */
    @Test
    void belegterNameLiefert409() throws Exception {
        String fremd = jdbc.queryForObject("SELECT name FROM profil.spieler WHERE id = ?",
                String.class, ersterSpieler());

        String antwort = mockMvc.perform(post("/api/v1/admin/name/aendern")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.66")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuerName\":\"%s\"}".formatted(fremd)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"NAME_BELEGT\"");
    }

    /** Ein leerer Name wird von der Bean Validation abgefangen, vor jedem Schreibzugriff. */
    @Test
    void leererNameLiefert400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/name/aendern")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.67")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuerName\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    /** Der Endpunkt liegt unter {@code /admin/} und verlangt damit die Rolle. */
    @Test
    void spielerDarfDenAnmeldenamenNichtAendern() throws Exception {
        mockMvc.perform(post("/api/v1/admin/name/aendern")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(
                                Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neuerName\":\"Pruefadmin Vier\"}"))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------- Hilfsmittel

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }

    /**
     * Meldet sich ueber {@code /auth/admin/anmelden} an - mit dem Passwort, das
     * {@link #aufbauen()} gesetzt hat.
     *
     * <p>Jeder Aufruf braucht eine eigene Client-IP: Der Brute-Force-Zaehler ist mit dem
     * PIN-Endpunkt geteilt, und ein fehlgeschlagener Versuch belastete sonst den naechsten.
     */
    private ResultActions anmelden(String anmeldename, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/admin/anmelden")
                .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null)))
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anmeldename\":\"%s\",\"passwort\":\"%s\"}"
                        .formatted(anmeldename, ALTES_PASSWORT)));
    }

    private boolean widerrufen(String token) {
        Boolean istWiderrufen = jdbc.queryForObject(
                "SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE token_hash = ?",
                Boolean.class, TokenGenerator.hash(token));
        return Boolean.TRUE.equals(istWiderrufen);
    }

    private String adminHash() {
        return jdbc.queryForObject("SELECT passwort_hash FROM profil.admin_konto WHERE id = 1", String.class);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }

    private Long ersterSpieler() {
        return jdbc.queryForObject(
                "SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1",
                Long.class);
    }
}
