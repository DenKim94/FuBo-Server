package de.fubo.appserver.controller.auth;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.BruteForceService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den PIN-Login aus {@code S2_UMSETZUNG.md}, Abschnitt 6: Hash-Vergleich,
 * Cookie-Attribute, Drosselung und Audit-Eintraege.
 *
 * <p><b>Zur Client-IP:</b> Jeder Test setzt einen eigenen Wert im Header
 * {@code CF-Connecting-IP}. Ohne das liefen alle Tests auf demselben Zaehler
 * ({@code 127.0.0.1}) und wuerden sich gegenseitig sperren. Nebenbei prueft das den
 * {@code ClientIpErmittler} mit.
 *
 * <p><b>Zum Zustand des {@code BruteForceService}:</b> Er ist ein Singleton und haelt seine
 * Zaehler im Arbeitsspeicher - eine Test-Transaktion rollt die nicht zurueck. Deshalb wird
 * er vor jedem Test geleert.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthControllerTests {

    private static final String COOKIE = "FUBO_SESSION";
    private static final String RICHTIGE_PIN = "pruef-pin-4711";
    private static final String FALSCHE_PIN = "falsch-0000";

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwortEncoder;

    @Autowired
    private BruteForceService bruteForceService;

    private MockMvc mockMvc;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        bruteForceService.alleZuruecksetzen();

        // UPSERT statt INSERT: Der PinBootstrap hat die Zeile beim Start des Kontexts
        // bereits angelegt. Die Aenderung wird mit der Test-Transaktion zurueckgerollt.
        jdbc.update("""
                INSERT INTO profil.zugangsdaten (id, pin_hash) VALUES (1, ?)
                ON CONFLICT (id) DO UPDATE SET pin_hash = EXCLUDED.pin_hash
                """, passwortEncoder.encode(RICHTIGE_PIN));
    }

    // --------------------------------------------------------------------- Grundfaelle

    /**
     * Der Erfolgsfall. Geprueft werden alle Cookie-Attribute an einer Stelle - sie
     * entstehen in der {@code SessionCookieFactory} und wirken erst im Browser, sind also
     * sonst nirgends sichtbar.
     */
    @Test
    void richtigePinLiefertSitzungscookieInStufeEins() throws Exception {
        MvcResult ergebnis = mockMvc.perform(pinAnfrage(RICHTIGE_PIN, "203.0.113.1"))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull()
                .contains(COOKIE + "=")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                // Kein Ablaufdatum -> Sitzungscookie. Massgeblich sind die Zeitstempel in
                // der Datenbank, nicht eine Angabe, die der Browser setzt.
                .doesNotContain("Max-Age")
                .doesNotContain("Expires");

        String token = tokenAus(setCookie);
        String stufe = jdbc.queryForObject(
                "SELECT stage FROM profil.session WHERE token_hash = ?",
                String.class, TokenGenerator.hash(token));

        assertThat(stufe).isEqualTo(Stage.PIN_VERIFIED.name());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?", Integer.class, token))
                .as("Der Klartext-Token darf nirgends in der Datenbank stehen")
                .isZero();
    }

    /** Falsche PIN: 401 mit maschinenlesbarem Code, kein Cookie. */
    @Test
    void falschePinLiefert401MitCodePinFalsch() throws Exception {
        MvcResult ergebnis = mockMvc.perform(pinAnfrage(FALSCHE_PIN, "203.0.113.2"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(ergebnis.getResponse().getContentAsString()).contains("\"code\":\"PIN_FALSCH\"");
        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    /** Bean Validation greift vor jeder BCrypt-Berechnung. */
    @Test
    void leerePinLiefert400() throws Exception {
        MvcResult ergebnis = mockMvc.perform(post("/api/v1/auth/pin/pruefen")
                        .header("CF-Connecting-IP", "203.0.113.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(ergebnis.getResponse().getContentAsString())
                .contains("\"code\":\"EINGABE_UNGUELTIG\"")
                .contains("pin");
    }

    // --------------------------------------------------------------- Brute-Force-Schutz

    /**
     * Fuenf Fehlversuche loesen die Sperre aus, der sechste laeuft hinein.
     *
     * <p>Der fuenfte Aufruf antwortet weiterhin mit {@code 401}: Die Sperre wirkt ab dem
     * naechsten Aufruf. Andernfalls verriete der Statuscode, an welcher Stelle die Zaehlung
     * genau steht.
     */
    @Test
    void sechsterFehlversuchLiefert429MitCodePinGesperrt() throws Exception {
        String ip = "203.0.113.4";

        for (int versuch = 1; versuch <= 5; versuch++) {
            mockMvc.perform(pinAnfrage(FALSCHE_PIN, ip)).andExpect(status().isUnauthorized());
        }

        MvcResult ergebnis = mockMvc.perform(pinAnfrage(FALSCHE_PIN, ip))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(ergebnis.getResponse().getContentAsString())
                .contains("\"code\":\"PIN_GESPERRT\"")
                .contains("Sekunden");
    }

    /** Die Sperre trifft nur die betroffene Adresse - sonst waere sie selbst der Angriff. */
    @Test
    void dieSperreTrifftNichtDieUebrigenNutzer() throws Exception {
        for (int versuch = 1; versuch <= 5; versuch++) {
            mockMvc.perform(pinAnfrage(FALSCHE_PIN, "203.0.113.5"));
        }

        mockMvc.perform(pinAnfrage(RICHTIGE_PIN, "203.0.113.6"))
                .andExpect(status().isNoContent());
    }

    /** Nach erfolgreicher Anmeldung darf ein frueherer Vertipper nicht nachwirken. */
    @Test
    void erfolgLeertDenFehlversuchszaehler() throws Exception {
        String ip = "203.0.113.7";

        for (int versuch = 1; versuch <= 4; versuch++) {
            mockMvc.perform(pinAnfrage(FALSCHE_PIN, ip)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(pinAnfrage(RICHTIGE_PIN, ip)).andExpect(status().isNoContent());

        for (int versuch = 1; versuch <= 4; versuch++) {
            mockMvc.perform(pinAnfrage(FALSCHE_PIN, ip)).andExpect(status().isUnauthorized());
        }
    }

    // --------------------------------------------------------------------- Audit-Log

    @Test
    void fehlversuchWirdImAuditLogVermerkt() throws Exception {
        String ip = "203.0.113.8";

        mockMvc.perform(pinAnfrage(FALSCHE_PIN, ip)).andExpect(status().isUnauthorized());

        Integer eintraege = jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log
                 WHERE aktion = 'PIN_FEHLVERSUCH' AND akteur_bezeichnung = ?
                """, Integer.class, ip);

        assertThat(eintraege).isEqualTo(1);
    }

    /** Die ausgeloeste Sperre wird gesondert vermerkt - sie ist der auswertbare Vorgang. */
    @Test
    void ausgeloesteSperreWirdImAuditLogVermerkt() throws Exception {
        String ip = "203.0.113.9";

        for (int versuch = 1; versuch <= 5; versuch++) {
            mockMvc.perform(pinAnfrage(FALSCHE_PIN, ip));
        }

        Integer sperren = jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log
                 WHERE aktion = 'PIN_GESPERRT' AND akteur_bezeichnung = ?
                """, Integer.class, ip);

        assertThat(sperren).isEqualTo(1);
    }

    // ------------------------------------------------------------- Bestehende Sitzung

    /**
     * Meldet sich jemand erneut an, obwohl noch ein gueltiges Cookie vorliegt, wird die
     * alte Sitzung widerrufen. Ohne das bliebe ein belegter Name unnoetig lange blockiert.
     */
    @Test
    void erneuterLoginWiderruftDieBestehendeSitzung() throws Exception {
        MvcResult erster = mockMvc.perform(pinAnfrage(RICHTIGE_PIN, "203.0.113.11"))
                .andExpect(status().isNoContent())
                .andReturn();
        String alterToken = tokenAus(erster.getResponse().getHeader(HttpHeaders.SET_COOKIE));

        mockMvc.perform(pinAnfrage(RICHTIGE_PIN, "203.0.113.11")
                        .cookie(new Cookie(COOKIE, alterToken)))
                .andExpect(status().isNoContent());

        Integer offen = jdbc.queryForObject("""
                SELECT count(*) FROM profil.session
                 WHERE token_hash = ? AND widerrufen_am IS NULL
                """, Integer.class, TokenGenerator.hash(alterToken));

        assertThat(offen).isZero();
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /** Baut eine PIN-Anfrage mit gesetzter Client-IP. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            pinAnfrage(String pin, String clientIp) {
        return post("/api/v1/auth/pin/pruefen")
                .header("CF-Connecting-IP", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"%s\"}".formatted(pin));
    }

    /** Schneidet den Tokenwert aus dem Set-Cookie-Header. */
    private static String tokenAus(String setCookieHeader) {
        assertThat(setCookieHeader).isNotNull();
        String ohneName = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);
        int ende = ohneName.indexOf(';');
        return ende < 0 ? ohneName : ohneName.substring(0, ende);
    }
}
