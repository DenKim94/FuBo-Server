package de.fubo.appserver.controller.auth;

import de.fubo.appserver.common.security.SessionAuthFilter;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die drei Sitzungsendpunkte aus {@code S2_UMSETZUNG.md}, Abschnitt 10.4/10.5:
 * Auskunft, Erneuerung und Abmeldung - dazu die Ausnahme des Pollings von der
 * Verlaengerung (Abschnitt 10.8).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SessionControllerTests {

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

    // --------------------------------------------------------------------- Auskunft

    /**
     * Der Endpunkt stellt den Zustand nach einem Seitenneuladen wieder her. Er liefert
     * Stufe, Rolle, Anzeigename und die beiden Ablaufzeitpunkte - und <b>keine</b> interne
     * Profil-Id und keine Skillwerte.
     */
    @Test
    void sitzungLesenLiefertStufeRolleUndNamenOhneProfilId() throws Exception {
        Map<String, Object> spieler = ersterAktiverSpieler();
        String token = sessionService.anlegen(
                Stage.PROFILE_AUTHENTICATED, (Long) spieler.get("id"), Rolle.USER);

        String antwort = mockMvc.perform(get("/api/v1/auth/session/lesen")
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort)
                .contains("\"stage\":\"PROFILE_AUTHENTICATED\"")
                .contains("\"rolle\":\"USER\"")
                .contains("\"anzeigeName\":\"" + spieler.get("name") + "\"")
                .contains("\"gueltigBis\"")
                .contains("\"absolutGueltigBis\"")
                .doesNotContain("spielerId")
                .doesNotContain("token");
    }

    /**
     * In der ersten Stufe gibt es weder Rolle noch Namen. Genau daran erkennt das
     * Frontend, dass es die Namensauswahl anzeigen muss und nicht die PIN-Eingabe.
     */
    @Test
    void sitzungLesenInStufeEinsLiefertKeineIdentitaet() throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/auth/session/lesen")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort)
                .contains("\"stage\":\"PIN_VERIFIED\"")
                .contains("\"rolle\":null")
                .contains("\"anzeigeName\":null");
    }

    /** Ein Gast hat kein Profil; sein Anzeigename steht in der Sitzung. */
    @Test
    void sitzungLesenLiefertBeiGaestenDenGastnamen() throws Exception {
        String token = pinVerifiedSitzung();
        jdbc.update("""
                UPDATE profil.session
                   SET stage = 'PROFILE_AUTHENTICATED', rolle = 'GAST', gast_name = 'Testgast Frida'
                 WHERE token_hash = ?
                """, TokenGenerator.hash(token));

        String antwort = mockMvc.perform(get("/api/v1/auth/session/lesen")
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort)
                .contains("\"rolle\":\"GAST\"")
                .as("Das Anhaengen von '(Gast)' ist Sache des Frontends")
                .contains("\"anzeigeName\":\"Testgast Frida\"");
    }

    // --------------------------------------------------------------------- Erneuerung

    /**
     * Die Erneuerung tauscht den Token aus. Die {@code session.id} bleibt erhalten - daran
     * haengt bei Gaesten der belegte Platz.
     */
    @Test
    void erneuernRotiertDenTokenUndBehaeltDieId() throws Exception {
        String alterToken = pinVerifiedSitzung();
        Long sitzungsId = sitzungsIdZu(alterToken);

        MvcResult ergebnis = mockMvc.perform(post("/api/v1/auth/session/erneuern")
                        .cookie(new Cookie(COOKIE, alterToken)))
                .andExpect(status().isNoContent())
                .andReturn();

        String neuerToken = tokenAus(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(neuerToken).isNotEqualTo(alterToken);

        assertThat(sitzungsIdZu(neuerToken)).isEqualTo(sitzungsId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash(alterToken)))
                .as("Der alte Token darf zu keiner Sitzung mehr fuehren")
                .isZero();
    }

    /**
     * Die harte Obergrenze wandert nicht mit. Genau dafuer gibt es den zweiten Timer: Ohne
     * ihn liesse sich eine Sitzung durch wiederholtes Erneuern endlos am Leben halten.
     */
    @Test
    void erneuernVerschiebtDieHarteObergrenzeNicht() throws Exception {
        String token = pinVerifiedSitzung();
        // Einmal aufloesen und die Id behalten: Die Erneuerung rotiert den Token, ein
        // zweites sitzungsIdZu(token) faende die Sitzung nicht mehr. Genau das ist der
        // Beleg dafuer, dass die Rotation wirkt - die Id bleibt dabei dieselbe.
        Long sitzungsId = sitzungsIdZu(token);
        OffsetDateTime vorher = absolutGueltigBis(sitzungsId);

        mockMvc.perform(post("/api/v1/auth/session/erneuern").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isNoContent());

        assertThat(absolutGueltigBis(sitzungsId)).isEqualTo(vorher);
    }

    // --------------------------------------------------------------------- Abmeldung

    /**
     * Das Abmelden widerruft die Sitzung serverseitig und loescht das Cookie. Der Widerruf
     * ist der wirksame Teil - selbst wenn der Browser das Cookie behielte, waere der Token
     * sofort wertlos.
     */
    @Test
    void abmeldenWiderruftDieSitzungUndLoeschtDasCookie() throws Exception {
        String token = pinVerifiedSitzung();
        Long sitzungsId = sitzungsIdZu(token);

        MvcResult ergebnis = mockMvc.perform(post("/api/v1/auth/session/beenden")
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull().contains(COOKIE + "=").contains("Max-Age=0");

        assertThat(jdbc.queryForObject(
                "SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE id = ?",
                Boolean.class, sitzungsId)).isTrue();
    }

    /** Der widerrufene Token ist sofort wertlos - der naechste Aufruf endet in 401. */
    @Test
    void nachDemAbmeldenIstDerTokenWertlos() throws Exception {
        String token = pinVerifiedSitzung();

        mockMvc.perform(post("/api/v1/auth/session/beenden").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/session/lesen").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------- Polling ohne Verlaengerung

    /**
     * Der Regelfall: Ein Aufruf verschiebt das Leerlauf-Fenster nach hinten.
     *
     * <p>Der Ausgangswert wird dafuer in die Vergangenheit gesetzt - so ist der Unterschied
     * eindeutig messbar, ohne auf Zeit zu warten. Innerhalb einer Transaktion liefert
     * PostgreSQL fuer {@code now()} stets denselben Wert; die Pruefung ist damit
     * deterministisch.
     */
    @Test
    void einNormalerAufrufVerlaengertDasLeerlauffenster() throws Exception {
        String token = pinVerifiedSitzung();
        Long sitzungsId = sitzungsIdZu(token);
        jdbc.update("UPDATE profil.session SET gueltig_bis = now() + interval '1 minute' WHERE id = ?",
                sitzungsId);
        OffsetDateTime vorher = gueltigBis(sitzungsId);

        mockMvc.perform(get("/api/v1/auth/session/lesen").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk());

        assertThat(gueltigBis(sitzungsId)).isAfter(vorher);
    }

    /**
     * Mit {@code X-FuBo-Kein-Refresh: true} bleibt das Fenster stehen (Abschnitt 10.8,
     * offener Punkt 7). Nur so misst "15 Minuten Inaktivitaet" den Nutzer und nicht den
     * offenen Browser-Tab, der im Hintergrund den Belegtstatus pollt.
     */
    @Test
    void einHintergrundaufrufVerlaengertDasLeerlauffensterNicht() throws Exception {
        String token = pinVerifiedSitzung();
        Long sitzungsId = sitzungsIdZu(token);
        jdbc.update("UPDATE profil.session SET gueltig_bis = now() + interval '1 minute' WHERE id = ?",
                sitzungsId);
        OffsetDateTime vorher = gueltigBis(sitzungsId);

        mockMvc.perform(get("/api/v1/auth/session/lesen")
                        .cookie(new Cookie(COOKIE, token))
                        .header(SessionAuthFilter.HEADER_KEIN_REFRESH, "true"))
                .andExpect(status().isOk());

        assertThat(gueltigBis(sitzungsId)).isEqualTo(vorher);
    }

    /** Der Header schaltet nur die Verlaengerung ab, nicht die Anmeldung. */
    @Test
    void einHintergrundaufrufBleibtAngemeldet() throws Exception {
        Map<String, Object> spieler = ersterAktiverSpieler();
        String token = sessionService.anlegen(
                Stage.PROFILE_AUTHENTICATED, (Long) spieler.get("id"), Rolle.USER);

        mockMvc.perform(get("/api/v1/auth/users/lesen")
                        .cookie(new Cookie(COOKIE, token))
                        .header(SessionAuthFilter.HEADER_KEIN_REFRESH, "true"))
                .andExpect(status().isOk());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private String pinVerifiedSitzung() {
        return sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
    }

    /**
     * Liefert die Id der zum Token gehoerenden Sitzung.
     *
     * <p><b>Nur mit einem noch gueltigen Token aufrufen.</b> Die Suche laeuft ueber den
     * Token-Hash, und {@code /session/erneuern} rotiert ihn: Nach dem Aufruf findet der
     * alte Hash keine Zeile mehr, und die Abfrage endet in einer
     * {@code EmptyResultDataAccessException}. Wer die Id danach braucht, merkt sie sich
     * vorher - die {@code session.id} bleibt bei der Rotation erhalten.
     */
    private Long sitzungsIdZu(String token) {
        return jdbc.queryForObject("SELECT id FROM profil.session WHERE token_hash = ?",
                Long.class, TokenGenerator.hash(token));
    }

    private OffsetDateTime gueltigBis(Long sitzungsId) {
        return jdbc.queryForObject("SELECT gueltig_bis FROM profil.session WHERE id = ?",
                OffsetDateTime.class, sitzungsId);
    }

    private OffsetDateTime absolutGueltigBis(Long sitzungsId) {
        return jdbc.queryForObject("SELECT absolut_gueltig_bis FROM profil.session WHERE id = ?",
                OffsetDateTime.class, sitzungsId);
    }

    private Map<String, Object> ersterAktiverSpieler() {
        return jdbc.queryForMap(
                "SELECT id, name FROM profil.spieler WHERE aktiv ORDER BY name LIMIT 1");
    }

    /** Schneidet den Tokenwert aus dem Set-Cookie-Header. */
    private static String tokenAus(String setCookieHeader) {
        assertThat(setCookieHeader).isNotNull();
        String ohneName = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);
        int ende = ohneName.indexOf(';');
        return ende < 0 ? ohneName : ohneName.substring(0, ende);
    }
}
