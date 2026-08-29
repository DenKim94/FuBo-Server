package de.fubo.appserver.controller.auth;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.BruteForceService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den Adminzugang aus {@code S2_UMSETZUNG.md}, Abschnitt 9.2: Passwortvergleich,
 * Stufenwechsel mit Token-Rotation, Drosselung und Audit-Eintraege.
 *
 * <p><b>Zur Client-IP:</b> Jeder Test setzt einen eigenen Wert im Header
 * {@code CF-Connecting-IP}. Der Brute-Force-Zaehler ist derselbe wie am PIN-Endpunkt; ohne
 * eigene Adressen wuerden die Faelle sich gegenseitig sperren.
 *
 * <p><b>Zum Anmeldenamen (ergaenzt am 29.08.2026):</b> Die Anmeldung verlangt zusaetzlich
 * den Profilnamen des Adminprofils, zeichengenau. Die Tests lesen ihn ueber
 * {@link #adminName()} aus der
 * Datenbank statt ihn festzuschreiben - er stammt aus {@code ADMIN_NAME} in
 * {@code src/test/resources/application.yml}, und ein Test, der den Wert doppelt fuehrt,
 * bricht bei jeder Aenderung dort mit.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminControllerTests {

    private static final String COOKIE = "FUBO_SESSION";
    private static final String RICHTIGES_PASSWORT = "pruef-admin-4711";
    private static final String FALSCHES_PASSWORT = "falsch-0000";
    private static final String FALSCHER_NAME = "Kein Adminprofil 9999";

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private PasswordEncoder passwortEncoder;

    @Autowired
    private BruteForceService bruteForceService;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Der Dienst ist ein Singleton und haelt seine Zaehler im Arbeitsspeicher - eine
        // Test-Transaktion rollt die nicht zurueck.
        bruteForceService.alleZuruecksetzen();

        // Die Zeile hat der AdminBootstrap beim Kontextstart angelegt; hier wird nur der
        // Hash gegen ein bekanntes Passwort getauscht. Die Aenderung wird mit der
        // Test-Transaktion zurueckgerollt.
        jdbc.update("UPDATE profil.admin_konto SET passwort_hash = ? WHERE id = 1",
                passwortEncoder.encode(RICHTIGES_PASSWORT));
    }

    // --------------------------------------------------------------------- Erfolgsfall

    /**
     * Der Kernfall: Die Sitzung behaelt ihre {@code id}, wechselt in die zweite Stufe mit der
     * Rolle {@code ADMIN} und bekommt einen neuen Token. Eingetragen wird das Profil aus
     * {@code admin_konto.spieler_id} - genau dafuer gibt es die Spalte.
     */
    @Test
    void richtigesPasswortHebtDieSitzungAufAdmin() throws Exception {
        String alterToken = pinVerifiedSitzung();
        Long sitzungsId = sitzungsIdZu(alterToken);

        MvcResult ergebnis = mockMvc.perform(anmeldung(alterToken, RICHTIGES_PASSWORT, "203.0.113.20"))
                .andExpect(status().isNoContent())
                .andReturn();

        String neuerToken = tokenAus(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(neuerToken).isNotEqualTo(alterToken);

        Map<String, Object> zeile = jdbc.queryForMap("""
                SELECT id, stage, rolle, spieler_id FROM profil.session WHERE token_hash = ?
                """, TokenGenerator.hash(neuerToken));

        assertThat(zeile.get("id")).isEqualTo(sitzungsId);
        assertThat(zeile.get("stage")).isEqualTo(Stage.PROFILE_AUTHENTICATED.name());
        assertThat(zeile.get("rolle")).isEqualTo(Rolle.ADMIN.name());
        assertThat(zeile.get("spieler_id")).isEqualTo(adminSpielerId());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash(alterToken)))
                .as("Der alte Token darf zu keiner Sitzung mehr fuehren")
                .isZero();
    }

    /**
     * Randleerzeichen werden im DTO entfernt, nicht im Dienst - sie gehoeren zur Auslegung
     * des Anfragekoerpers. Ein aus der Zwischenablage eingefuegter Name soll die Anmeldung
     * nicht scheitern lassen.
     *
     * <p><b>Kein Widerspruch zur zeichengenauen Pruefung der Schreibweise:</b> Ein
     * fuehrendes Leerzeichen ist unsichtbar und nie beabsichtigt, eine Schreibweise ist
     * sichtbar und kann es sein.
     */
    @Test
    void randleerzeichenImAnmeldenamenStoerenNicht() throws Exception {
        mockMvc.perform(anmeldung(pinVerifiedSitzung(), "  " + adminName() + "  ",
                        RICHTIGES_PASSWORT, "203.0.113.27"))
                .andExpect(status().isNoContent());
    }

    // --------------------------------------------------------------------- Fehlerfaelle

    /** Falsches Passwort: 401 mit eigenem Code, kein Cookie. */
    @Test
    void falschesPasswortLiefert401MitEigenemCode() throws Exception {
        MvcResult ergebnis = mockMvc.perform(anmeldung(pinVerifiedSitzung(), FALSCHES_PASSWORT, "203.0.113.21"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(ergebnis.getResponse().getContentAsString())
                .contains("\"code\":\"ADMIN_PASSWORT_FALSCH\"");
        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    /**
     * Falscher Anmeldename bei richtigem Passwort: dieselbe Antwort wie bei falschem
     * Passwort - {@code 401} mit {@code ADMIN_PASSWORT_FALSCH}.
     *
     * <p><b>Das ist der Kern der Ergaenzung vom 29.08.2026.</b> Wuerde die Antwort die
     * beiden Faelle unterscheiden, waere der Anmeldename ueber den Fehlercode erratbar und
     * die zusaetzliche Angabe wertlos. Aus demselben Grund laeuft im Dienst auch die
     * BCrypt-Berechnung bei falschem Namen weiter (Verknuepfung mit {@code &} statt
     * {@code &&}) - eine schnelle Ablehnung waere ein Zeitorakel. Das laesst sich hier nicht
     * sinnvoll messen; die Absicherung ist die Codeform, nicht dieser Test.
     */
    @Test
    void falscherAnmeldenameLiefertDieselbeAntwortWieEinFalschesPasswort() throws Exception {
        MvcResult ergebnis = mockMvc.perform(anmeldung(pinVerifiedSitzung(), FALSCHER_NAME,
                        RICHTIGES_PASSWORT, "203.0.113.28"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(ergebnis.getResponse().getContentAsString())
                .contains("\"code\":\"ADMIN_PASSWORT_FALSCH\"");
        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    /**
     * Abweichende Gross-/Kleinschreibung genuegt nicht (Festlegung vom 29.08.2026).
     *
     * <p>Der Name ist ein Anmeldemerkmal, und bei einem Merkmal ist Nachsicht die falsche
     * Richtung. Aussperren kann das niemanden: {@code AdminBootstrap} legt den Profilnamen
     * zeichengenau nach {@code ADMIN_NAME} ab und bricht sonst den Start ab - die
     * Gegenprobe dazu steht in {@code AdminBootstrapTests}.
     */
    @Test
    void abweichendeSchreibweiseDesAnmeldenamensLiefert401() throws Exception {
        MvcResult ergebnis = mockMvc.perform(anmeldung(pinVerifiedSitzung(),
                        adminName().toUpperCase(Locale.ROOT), RICHTIGES_PASSWORT, "203.0.113.26"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(ergebnis.getResponse().getContentAsString())
                .contains("\"code\":\"ADMIN_PASSWORT_FALSCH\"");
        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    /**
     * Der Name eines gewoehnlichen Spielerprofils traegt nicht: Geprueft wird gegen das
     * Profil aus {@code admin_konto.spieler_id}, nicht gegen die Profiltabelle im Ganzen.
     */
    @Test
    void nameEinesAnderenProfilsLiefert401() throws Exception {
        String fremderName = jdbc.queryForObject("""
                SELECT name FROM profil.spieler WHERE rolle <> 'ADMIN' ORDER BY name LIMIT 1
                """, String.class);

        mockMvc.perform(anmeldung(pinVerifiedSitzung(), fremderName,
                        RICHTIGES_PASSWORT, "203.0.113.29"))
                .andExpect(status().isUnauthorized());
    }

    /** Fehlender Anmeldename: Bean Validation, also {@code 400} statt {@code 401}. */
    @Test
    void fehlenderAnmeldenameLiefert400() throws Exception {
        String antwort = mockMvc.perform(post("/api/v1/auth/admin/anmelden")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung()))
                        .header("CF-Connecting-IP", "203.0.113.30")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwort\":\"%s\"}".formatted(RICHTIGES_PASSWORT)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("anmeldename");
    }

    /** Bean Validation greift vor jeder BCrypt-Berechnung. */
    @Test
    void leeresPasswortLiefert400() throws Exception {
        String antwort = mockMvc.perform(post("/api/v1/auth/admin/anmelden")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung()))
                        .header("CF-Connecting-IP", "203.0.113.22")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anmeldename\":\"%s\",\"passwort\":\"\"}".formatted(adminName())))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("passwort");
    }

    /**
     * Die Drosselung ist dieselbe wie am PIN-Endpunkt - derselbe Zaehler, dieselbe Sperre.
     * Sichtbare Folge: Der Code lautet {@code PIN_GESPERRT}, obwohl hier ein Passwort
     * geprueft wurde. Das ist die ehrliche Bezeichnung derselben Sperre und kein Versehen.
     */
    @Test
    void sechsterFehlversuchLiefert429MitRestwartezeit() throws Exception {
        String ip = "203.0.113.23";

        for (int versuch = 1; versuch <= 5; versuch++) {
            mockMvc.perform(anmeldung(pinVerifiedSitzung(), FALSCHES_PASSWORT, ip))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult ergebnis = mockMvc.perform(anmeldung(pinVerifiedSitzung(), FALSCHES_PASSWORT, ip))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(ergebnis.getResponse().getContentAsString())
                .contains("\"code\":\"PIN_GESPERRT\"")
                .contains("\"wartesekunden\":");
        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    /**
     * Der Zaehler ist mit dem PIN-Endpunkt geteilt: Wer fuenf Admin-Passwoerter raet, soll
     * anschliessend auch keine PINs mehr durchprobieren koennen.
     */
    @Test
    void dieSperreGiltAuchFuerDenPinEndpunkt() throws Exception {
        String ip = "203.0.113.24";

        for (int versuch = 1; versuch <= 5; versuch++) {
            mockMvc.perform(anmeldung(pinVerifiedSitzung(), FALSCHES_PASSWORT, ip))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/pin/pruefen")
                        .header("CF-Connecting-IP", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"beliebig\"}"))
                .andExpect(status().isTooManyRequests());
    }

    // --------------------------------------------------------------------- Audit-Log

    @Test
    void fehlversuchUndAnmeldungWerdenProtokolliert() throws Exception {
        String ip = "203.0.113.25";

        mockMvc.perform(anmeldung(pinVerifiedSitzung(), FALSCHES_PASSWORT, ip))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log
                 WHERE aktion = 'ADMIN_LOGIN_FEHLVERSUCH' AND akteur_bezeichnung = ?
                """, Integer.class, ip)).isEqualTo(1);

        mockMvc.perform(anmeldung(pinVerifiedSitzung(), RICHTIGES_PASSWORT, ip))
                .andExpect(status().isNoContent());

        Map<String, Object> eintrag = jdbc.queryForMap("""
                SELECT akteur_spieler_id, details->>'endpunkt' AS endpunkt
                  FROM profil.audit_log
                 WHERE aktion = 'ADMIN_ANGEMELDET' AND akteur_bezeichnung = ?
                """, ip);

        assertThat(eintrag.get("akteur_spieler_id"))
                .as("Der Erfolg wird dem Adminprofil zugeordnet, nicht nur der Adresse")
                .isEqualTo(adminSpielerId());
        assertThat(eintrag.get("endpunkt"))
                .as("Die Spalte details muss jsonb sein, nicht Text")
                .isEqualTo("/auth/admin/anmelden");
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /** Anmeldung mit dem richtigen Anmeldenamen; nur das Passwort variiert. */
    private MockHttpServletRequestBuilder anmeldung(String token, String passwort, String ip) {
        return anmeldung(token, adminName(), passwort, ip);
    }

    private static MockHttpServletRequestBuilder anmeldung(String token, String anmeldename,
                                                           String passwort, String ip) {
        return post("/api/v1/auth/admin/anmelden")
                .cookie(new Cookie(COOKIE, token))
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anmeldename\":\"%s\",\"passwort\":\"%s\"}"
                        .formatted(anmeldename, passwort));
    }

    /**
     * Liest den Profilnamen des Adminprofils aus der Datenbank.
     *
     * <p>Ueber den Umweg {@code admin_konto.spieler_id} statt ueber {@code rolle = 'ADMIN'}:
     * Das ist genau der Weg, den auch der Dienst geht, und damit derselbe Datensatz.
     */
    private String adminName() {
        return jdbc.queryForObject("""
                SELECT s.name FROM profil.spieler s
                  JOIN profil.admin_konto k ON k.spieler_id = s.id
                 WHERE k.id = 1
                """, String.class);
    }

    private String pinVerifiedSitzung() {
        return sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
    }

    /**
     * Liefert die Id der zum Token gehoerenden Sitzung.
     *
     * <p><b>Nur mit einem noch gueltigen Token aufrufen</b> - die Anmeldung rotiert ihn.
     */
    private Long sitzungsIdZu(String token) {
        return jdbc.queryForObject("SELECT id FROM profil.session WHERE token_hash = ?",
                Long.class, TokenGenerator.hash(token));
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject(
                "SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }

    /** Schneidet den Tokenwert aus dem Set-Cookie-Header. */
    private static String tokenAus(String setCookieHeader) {
        assertThat(setCookieHeader).isNotNull();
        String ohneName = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);
        int ende = ohneName.indexOf(';');
        return ende < 0 ? ohneName : ohneName.substring(0, ende);
    }
}
