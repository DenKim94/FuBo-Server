package de.fubo.appserver.common.config;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Zugriffsregeln aus {@link SecurityConfig} durch die vollstaendige Filterkette.
 *
 * <p>Ein reiner Unit-Test waere hier ohne Aussagekraft: Die {@code SecurityFilterChain} ist
 * eine Beschreibung, deren Wirkung erst durch das Zusammenspiel von {@code SessionAuthFilter},
 * {@code AuthorizationFilter} und {@code ExceptionTranslationFilter} entsteht. Was sich isoliert
 * pruefen laesst, liegt in {@code SessionAuthFilterTests} und {@code SessionCookieFactoryTests}.
 *
 * <p>Die Endpunkte aus {@link TestEndpunkte} existieren nur fuer diesen Test. Ohne sie liefe
 * jeder erlaubte Aufruf in ein {@code 404} - man koennte "erlaubt" dann nicht von "nicht
 * gefunden" unterscheiden.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SecurityConfigTests {

    private static final String COOKIE = "FUBO_SESSION";
    private static final String ERLAUBTE_ORIGIN = "http://localhost:5173";

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    /**
     * MockMvc wird von Hand aufgebaut statt ueber {@code @AutoConfigureMockMvc}: Der
     * Configurer {@code springSecurity()} bindet die echte Filterkette ein, und der Aufbau
     * haengt nur an spring-test und spring-security-test.
     */
    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ------------------------------------------------------------- Offene Endpunkte

    /**
     * Der Container-Healthcheck ruft diesen Endpunkt ohne Cookie auf. Mit 401 bliebe der
     * Container dauerhaft unhealthy und depends_on waere nie erfuellt.
     */
    @Test
    void actuatorHealthIstOhneCookieErreichbar() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * Ohne offenen PIN-Endpunkt kaeme niemand jemals zu einer Sitzung.
     *
     * <p>Geprueft wird mit einem leeren Anfragekoerper. Die Antwort ist deshalb 400 aus der
     * Bean Validation - und genau das ist die Aussage: Der Aufruf hat die Filterchain
     * passiert und den Controller erreicht. Waere der Endpunkt gesperrt, kaeme 401.
     * Der fachliche Ablauf steht in {@code AuthControllerTests}.
     */
    @Test
    void pinEndpunktIstOhneCookieErreichbar() throws Exception {
        String antwort = mockMvc.perform(post("/api/v1/auth/pin/pruefen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"");
    }

    // ------------------------------------------------------------- Deny-by-default

    /** Alles Uebrige ist gesperrt - und liefert dasselbe Fehlerformat wie fachliche Fehler. */
    @Test
    void ohneCookieLiefert401ImEinheitlichenFehlerformat() throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/beliebig"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"SESSION_UNGUELTIG\"");
    }

    @Test
    void unbekannterTokenLiefert401() throws Exception {
        mockMvc.perform(get("/api/v1/beliebig").cookie(new Cookie(COOKIE, "gibt-es-nicht")))
                .andExpect(status().isUnauthorized());
    }

    /** Erster Timer: abgelaufenes Leerlauf-Fenster. */
    @Test
    void abgelaufeneSitzungLiefert401() throws Exception {
        String token = sitzung(Rolle.USER);
        jdbc.update("UPDATE profil.session SET gueltig_bis = now() - interval '1 second' "
                + "WHERE token_hash = ?", TokenGenerator.hash(token));

        mockMvc.perform(get("/api/v1/beliebig").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isUnauthorized());
    }

    /** Eine widerrufene Sitzung wirkt sofort - der Grund fuer den serverseitigen Token. */
    @Test
    void widerrufeneSitzungLiefert401() throws Exception {
        String token = sitzung(Rolle.USER);
        jdbc.update("UPDATE profil.session SET widerrufen_am = now() WHERE token_hash = ?",
                TokenGenerator.hash(token));

        mockMvc.perform(get("/api/v1/beliebig").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- Stufenerzwingung

    /** In Stufe 1 darf die Namensliste gelesen werden - dafuer ist sie da. */
    @Test
    void pinVerifiedDarfDieNamenslisteLesen() throws Exception {
        mockMvc.perform(get("/api/v1/auth/users/lesen").cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isOk());
    }

    /**
     * Auch hier ist 400 der Beleg fuer "erlaubt": Der leere Koerper scheitert erst an der
     * Bean Validation im Controller, also nach der Autorisierung. Die Gegenprobe steht
     * eine Methode weiter unten - dort liefert dieselbe Anfrage 403.
     */
    @Test
    void pinVerifiedDarfDieIdentitaetWaehlen() throws Exception {
        mockMvc.perform(post("/api/v1/auth/user/waehlen")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Der Kernfall der Stufenerzwingung: angemeldet, aber noch ohne Identitaet. Die Antwort
     * muss 403 sein, nicht 401 - bei 401 liefe das Frontend in eine Login-Schleife.
     */
    @Test
    void pinVerifiedDarfKeinenGeschuetztenEndpunktAufrufen() throws Exception {
        String antwort = mockMvc.perform(
                        get("/api/v1/beliebig").cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"KEINE_BERECHTIGUNG\"");
    }

    /** Gegenrichtung: Wer bereits eine Identitaet hat, waehlt keine zweite. */
    @Test
    void userDarfDieIdentitaetNichtErneutWaehlen() throws Exception {
        mockMvc.perform(post("/api/v1/auth/user/waehlen")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Der Adminzugang <i>verleiht</i> die Rolle und ist deshalb nur in der Stufe
     * {@code PIN_VERIFIED} erreichbar - nicht zu verwechseln mit dem Adminbereich, der sie
     * voraussetzt.
     *
     * <p>{@code 400} ist hier der Beleg fuer "erlaubt": Der leere Koerper scheitert erst an
     * der Bean Validation im Controller, also nach der Autorisierung.
     */
    @Test
    void pinVerifiedDarfSichAlsAdminAnmelden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/anmelden")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** Gegenrichtung: Wer bereits eine Identitaet hat, holt sich keine zweite. */
    @Test
    void userDarfSichNichtAlsAdminAnmelden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/anmelden")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------- Sitzungsverwaltung

    /**
     * Die drei Sitzungsendpunkte sind bereits ab {@code PIN_VERIFIED} erlaubt und nicht
     * erst ab {@code PROFILE_AUTHENTICATED}. Laedt jemand die Seite zwischen PIN-Eingabe und
     * Namenswahl neu, muss das Frontend erfahren, in welcher Stufe es steht - mit
     * {@code 403} liefe es zurueck zur PIN-Eingabe, obwohl die Sitzung gueltig ist.
     */
    @Test
    void pinVerifiedDarfDieSitzungLesen() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session/lesen")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isOk());
    }

    /** Einen angefangenen Login abzubrechen muss ebenfalls in Stufe 1 moeglich sein. */
    @Test
    void pinVerifiedDarfSichAbmelden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/session/beenden")
                        .cookie(new Cookie(COOKIE, pinVerifiedSitzung())))
                .andExpect(status().isNoContent());
    }

    /** Gegenprobe: ohne Cookie bleibt es bei 401 - die Endpunkte sind nicht offen. */
    @Test
    void sitzungLesenOhneCookieLiefert401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session/lesen"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- Rollentrennung

    /**
     * Diese Regel schuetzt A13. Sie greift schon heute, obwohl es unter /api/admin/ noch
     * keinen Endpunkt gibt - der Test schlaegt fehl, sobald jemand die Zeile entfernt.
     */
    @Test
    void userDarfNichtInDenAdminbereich() throws Exception {
        mockMvc.perform(get("/api/v1/admin/test").cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void gastDarfNichtInDenAdminbereich() throws Exception {
        mockMvc.perform(get("/api/v1/admin/test").cookie(new Cookie(COOKIE, gastSitzung())))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDarfInDenAdminbereich() throws Exception {
        mockMvc.perform(get("/api/v1/admin/test").cookie(new Cookie(COOKIE, sitzung(Rolle.ADMIN))))
                .andExpect(status().isOk());
    }

    /**
     * Die acht Endpunkte aus S3 und der Gastverwaltung sind ohne Sitzung gesperrt -
     * jeder einzeln geprueft.
     *
     * <p><b>Warum trotz {@code userDarfNichtInDenAdminbereich} noch einmal einzeln:</b> Jener
     * Fall prueft die Regel {@code /api/*&#47;admin/**} an einem Platzhalterpfad. Er bliebe
     * gruen, wenn jemand fuer einen der echten Endpunkte eine eigene, offenere Regel
     * <i>davor</i> setzte - Spring Security wertet die Matcher in ihrer Reihenfolge aus, und
     * die erste passende gewinnt. Diese acht Pfade sind neu; sie sollen mit ihrem echten
     * Namen in der Pruefung stehen.
     *
     * <p>{@code GET} und {@code POST} gemischt, wie im Vertrag: Die Regel selbst ist
     * methodenunabhaengig, aber eine spaetere methodenbezogene Ausnahme faellt so auf.
     */
    @Test
    void dieNeuenAdminEndpunkteSindOhneSitzungGesperrt() throws Exception {
        mockMvc.perform(get("/api/v1/admin/user/lesen"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(get("/api/v1/admin/skills/lesen"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/config/lesen"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/user/bearbeiten")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/name/aendern")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/config/aendern")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/gast/lesen"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/gast/freigeben")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // S4, Pakete 3 und 4
        mockMvc.perform(post("/api/v1/admin/termin/anlegen")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/termin/aendern")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/termin/absagen")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/serie/anlegen")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Gegenprobe mit einer Spielersitzung: {@code 403}, nicht {@code 401}. Bei {@code 401}
     * liefe das Frontend in eine Login-Schleife, obwohl die Sitzung gueltig ist - es fehlt
     * die Rolle, nicht die Anmeldung.
     */
    @Test
    void dieNeuenAdminEndpunkteLiefernMitSpielersitzung403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/user/lesen")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/skills/lesen")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/config/lesen")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/gast/lesen")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/gast/freigeben")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alle\": true}")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());

        // S4, Pakete 3 und 4: Ein Spieler darf Termine lesen, aber nicht verwalten.
        mockMvc.perform(post("/api/v1/admin/termin/anlegen")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/termin/absagen")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/serie/anlegen")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void userUndGastDuerfenGeschuetzteEndpunkteAufrufen() throws Exception {
        mockMvc.perform(get("/api/v1/beliebig").cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/beliebig").cookie(new Cookie(COOKIE, gastSitzung())))
                .andExpect(status().isOk());

        // S4, Paket 2: Der Terminbereich liegt nicht unter /admin/ und ist damit ab
        // PROFILE_AUTHENTICATED erreichbar - auch fuer Gaeste (Weggabelung F). Der Fall
        // steht hier mit dem echten Pfad und nicht nur am Platzhalter oben, weil eine
        // spaeter davorgesetzte, engere Regel sonst unbemerkt bliebe.
        mockMvc.perform(get("/api/v1/termine/lesen").cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/termine/lesen").cookie(new Cookie(COOKIE, gastSitzung())))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- Konfiguration der Kette

    /**
     * SessionCreationPolicy.STATELESS: Spring darf keine zusaetzliche HttpSession anlegen.
     * Ein zweiter Sitzungsmechanismus neben der Datenbank waere eine Fehlerquelle.
     */
    @Test
    void esEntstehtKeineHttpSession() throws Exception {
        var ergebnis = mockMvc.perform(
                        get("/api/v1/beliebig").cookie(new Cookie(COOKIE, sitzung(Rolle.USER))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(ergebnis.getRequest().getSession(false)).isNull();
    }

    /**
     * httpBasic ist abgeschaltet. Mit WWW-Authenticate oeffnete der Browser bei jedem 401
     * einen Passwortdialog - auf einer JSON-API unbrauchbar.
     */
    @Test
    void keinBrowserPasswortdialogBei401() throws Exception {
        mockMvc.perform(get("/api/v1/beliebig"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    /**
     * formLogin ist abgeschaltet. Ohne das antwortete Spring mit einer Weiterleitung auf
     * /login, und das Frontend bekaeme 302 statt eines auswertbaren Statuscodes.
     */
    @Test
    void keineWeiterleitungZumLoginFormular() throws Exception {
        mockMvc.perform(get("/api/v1/beliebig"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    // ------------------------------------------------------------- CORS

    /**
     * Der Preflight muss ohne Sitzung durchgehen: Er traegt bauartbedingt kein Cookie.
     * allowCredentials ist die Gegenstelle zu credentials:'include' im Frontend.
     */
    @Test
    void preflightVonErlaubterOriginWirdBeantwortet() throws Exception {
        mockMvc.perform(options("/api/v1/auth/users/lesen")
                        .header(HttpHeaders.ORIGIN, ERLAUBTE_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ERLAUBTE_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    /** Eine fremde Origin wird vom CorsFilter abgewiesen, bevor die Autorisierung greift. */
    @Test
    void preflightVonFremderOriginWirdAbgelehnt() throws Exception {
        mockMvc.perform(options("/api/v1/auth/users/lesen")
                        .header(HttpHeaders.ORIGIN, "https://boese-seite.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------- Hilfsmittel

    /** Sitzung in Stufe 1: PIN geprueft, noch keine Identitaet. */
    private String pinVerifiedSitzung() {
        return sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
    }

    /** Sitzung in Stufe 2 mit Profilbezug. */
    private String sitzung(Rolle rolle) {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), rolle);
    }

    /**
     * Gastsitzung: kein Profil, dafuer ein temporaerer Name. Der Umweg ueber ein UPDATE ist
     * noetig, weil das Setzen des Gastnamens erst in Abschnitt 8 im Service entsteht.
     */
    private String gastSitzung() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        jdbc.update("""
                UPDATE profil.session
                   SET stage = 'PROFILE_AUTHENTICATED', rolle = 'GAST', gast_name = 'Gast 1'
                 WHERE token_hash = ?
                """, TokenGenerator.hash(token));
        return token;
    }

    /** Liefert eine beliebige Profil-Id aus den Demodaten (keine realen Namen). */
    private Long ersterSpieler() {
        return jdbc.queryForObject("SELECT id FROM profil.spieler ORDER BY id LIMIT 1", Long.class);
    }

    /**
     * Platzhalter-Endpunkte, damit sich "erlaubt" von "nicht gefunden" unterscheiden laesst.
     *
     * <p><b>Nur noch fuer Pfade ohne echten Controller.</b> Mit den Abschnitten 6 bis 10
     * sind {@code /auth/pin}, {@code /auth/users}, {@code /auth/user},
     * {@code /auth/gast/anmelden} und die drei {@code /auth/session/...}-Pfade echte
     * Endpunkte geworden; ihre Platzhalter mussten hier entfallen. Waeren beide vorhanden,
     * meldete Spring "Ambiguous mapping" und der Kontext startete gar nicht erst.
     * Uebrig bleiben nur noch {@code /api/{version}/admin/test} und
     * {@code /api/{version}/beliebig} - fuer sie gibt es bis S3 keinen Controller.
     *
     * <p>Auch die Platzhalter tragen das Versionssegment und ein {@code version}-Attribut.
     * Ohne beides pruefte der Test eine Filterchain gegen Pfade, die es so nicht gibt.
     *
     * <p><b>Genau eine Registrierung.</b> Die Klasse ist zugleich {@code @TestConfiguration}
     * und {@code @RestController}. Eine geschachtelte {@code @TestConfiguration} eines
     * {@code @SpringBootTest} wird zusaetzlich zur Hauptkonfiguration herangezogen und
     * gleichzeitig vom Komponentenscan ausgeschlossen - der Controller entsteht damit genau
     * einmal. Ein zusaetzlicher innerer {@code @RestController} mit eigener
     * {@code @Bean}-Methode wuerde doppelt registriert: einmal ueber den Komponentenscan
     * (der Basispaket {@code de.fubo.appserver} auch in den Testquellen absucht) und einmal
     * ueber die Bean-Methode. Ergebnis waere ein "Ambiguous mapping" und ein Kontext, der
     * gar nicht erst startet.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class TestEndpunkte {

        @GetMapping(value = "/api/{version}/admin/test", version = ApiVersionConfig.VERSION)
        void adminbereich() {
        }

        @GetMapping(value = "/api/{version}/beliebig", version = ApiVersionConfig.VERSION)
        void geschuetzt() {
        }
    }
}
