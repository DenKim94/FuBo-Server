package de.fubo.appserver.common.security;

import de.fubo.appserver.common.config.FuboProperties;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.service.config.ConfigService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echte Unit-Tests fuer den {@link SessionAuthFilter}: kein Spring-Kontext, keine Datenbank.
 *
 * <p>Geprueft wird ausschliesslich die Aufgabe des Filters - Cookie lesen, Ergebnis der
 * Sitzungspruefung in einen {@code SecurityContext} uebersetzen und die Kette in jedem Fall
 * fortsetzen. Ob daraus spaeter ein {@code 401} oder ein {@code 200} wird, entscheidet die
 * Autorisierung und wird in {@code SecurityConfigTests} geprueft.
 *
 * <p>Statt eines Mocking-Frameworks wird ein handgeschriebener Ersatz fuer den
 * {@link SessionService} verwendet. Das haelt den Test frei von einer weiteren Abhaengigkeit
 * und macht sichtbar, dass der Filter genau eine Methode des Service braucht.
 */
class SessionAuthFilterTests {

    private static final String COOKIE_NAME = "FUBO_SESSION";

    private static final FuboProperties PROPS = new FuboProperties(
            new FuboProperties.Session(COOKIE_NAME, false, "Lax"),
            new FuboProperties.Cors(List.of("http://localhost:5173")),
            // Fuer den Filter ohne Bedeutung; die Drosselung haengt am PIN-Endpunkt.
            new FuboProperties.BruteForce(5, 30, 15, List.of(1, 5, 15)),
            new FuboProperties.Audit(90));

    /** Der SecurityContext haengt am Thread und wuerde sonst in den naechsten Test lecken. */
    @AfterEach
    void kontextLeeren() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- Kein Kontext

    /** Ohne Cookie darf keine Sitzungspruefung stattfinden - und schon gar kein Kontext entstehen. */
    @Test
    void ohneCookieBleibtDerKontextLeer() throws Exception {
        SessionServiceErsatz service = new SessionServiceErsatz(Optional.empty());
        MockFilterChain kette = filterAusfuehren(new MockHttpServletRequest(), service);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(service.zuletztGeprueft).isNull();   // kein Datenbankzugriff
        assertThat(kette.getRequest()).isNotNull();     // Kette wurde fortgesetzt
    }

    /** Ein Cookie mit anderem Namen ist fuer uns kein Cookie. */
    @Test
    void fremdesCookieWirdIgnoriert() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("JSESSIONID", "irgendetwas"));

        SessionServiceErsatz service = new SessionServiceErsatz(Optional.empty());
        filterAusfuehren(req, service);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(service.zuletztGeprueft).isNull();
    }

    /** Ein vorhandener, aber ungueltiger Token fuehrt zu keinem Kontext. */
    @Test
    void ungueltigerTokenSetztKeinenKontext() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(COOKIE_NAME, "abgelaufen"));

        SessionServiceErsatz service = new SessionServiceErsatz(Optional.empty());
        filterAusfuehren(req, service);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(service.zuletztGeprueft).isEqualTo("abgelaufen");   // Pruefung fand statt
    }

    // ------------------------------------------------------------- Authority-Abbildung

    /** Stufe 1: Die Rolle ist noch null, die Authority ergibt sich allein aus der Stufe. */
    @Test
    void pinVerifiedErhaeltRolePinVerified() throws Exception {
        Authentication auth = anmelden(sitzung(Stage.PIN_VERIFIED, null));

        assertThat(auth).isNotNull();
        assertThat(autoritaeten(auth)).containsExactly("ROLE_PIN_VERIFIED");
    }

    @Test
    void userErhaeltRoleUser() throws Exception {
        Authentication auth = anmelden(sitzung(Stage.PROFILE_AUTHENTICATED, Rolle.USER));

        assertThat(autoritaeten(auth)).containsExactly("ROLE_USER");
    }

    @Test
    void adminErhaeltRoleAdmin() throws Exception {
        Authentication auth = anmelden(sitzung(Stage.PROFILE_AUTHENTICATED, Rolle.ADMIN));

        assertThat(autoritaeten(auth)).containsExactly("ROLE_ADMIN");
    }

    @Test
    void gastErhaeltRoleGast() throws Exception {
        Authentication auth = anmelden(sitzung(Stage.PROFILE_AUTHENTICATED, Rolle.GAST));

        assertThat(autoritaeten(auth)).containsExactly("ROLE_GAST");
    }

    /**
     * Genau eine Authority je Sitzung. Kaemen mehrere zusammen, koennte eine Sitzung in
     * PIN_VERIFIED zusaetzlich als USER durchgehen - die Stufenerzwingung waere ausgehebelt.
     */
    @Test
    void jedeSitzungHatGenauEineAuthority() throws Exception {
        Authentication auth = anmelden(sitzung(Stage.PROFILE_AUTHENTICATED, Rolle.USER));

        assertThat(auth.getAuthorities()).hasSize(1);
    }

    // ------------------------------------------------------------- Principal und Verhalten

    /** Der Principal ist die Sitzung selbst - Grundlage fuer @AuthenticationPrincipal. */
    @Test
    void derPrincipalIstDieSitzung() throws Exception {
        AktiveSitzung erwartet = sitzung(Stage.PROFILE_AUTHENTICATED, Rolle.USER);
        Authentication auth = anmelden(erwartet);

        assertThat(auth.getPrincipal()).isSameAs(erwartet);
    }

    /** Es duerfen keine Zugangsdaten im Kontext landen - es gibt keine. */
    @Test
    void derKontextEnthaeltKeineZugangsdaten() throws Exception {
        Authentication auth = anmelden(sitzung(Stage.PROFILE_AUTHENTICATED, Rolle.USER));

        assertThat(auth.getCredentials()).isNull();
        assertThat(auth.isAuthenticated()).isTrue();
    }

    /**
     * Der Filter lehnt nie selbst ab. Er setzt keinen Statuscode und bricht die Kette nicht ab -
     * die Entscheidung faellt erst in authorizeHttpRequests.
     */
    @Test
    void derFilterLehntNichtSelbstAb() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(COOKIE_NAME, "ungueltig"));

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain kette = new MockFilterChain();
        new SessionAuthFilter(new SessionServiceErsatz(Optional.empty()), PROPS)
                .doFilter(req, res, kette);

        assertThat(res.getStatus()).isEqualTo(200);       // unveraendert
        assertThat(res.getContentAsString()).isEmpty();   // nichts geschrieben
        assertThat(kette.getRequest()).isNotNull();       // fortgesetzt
    }

    // ------------------------------------------------------------- Hilfsmittel

    /** Fuehrt den Filter mit dem uebergebenen Request aus und liefert die benutzte Kette. */
    private MockFilterChain filterAusfuehren(MockHttpServletRequest req, SessionServiceErsatz service)
            throws Exception {
        MockFilterChain kette = new MockFilterChain();
        new SessionAuthFilter(service, PROPS).doFilter(req, new MockHttpServletResponse(), kette);
        return kette;
    }

    /** Laesst den Filter mit gueltigem Cookie laufen und liefert das Ergebnis im Kontext. */
    private Authentication anmelden(AktiveSitzung sitzung) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(COOKIE_NAME, "gueltiger-token"));

        filterAusfuehren(req, new SessionServiceErsatz(Optional.of(sitzung)));
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** Baut eine Sitzung ohne Datenbank. */
    private static AktiveSitzung sitzung(Stage stage, Rolle rolle) {
        Long spielerId = (rolle == null || rolle == Rolle.GAST) ? null : 7L;
        String gastName = (rolle == Rolle.GAST) ? "Gast 1" : null;
        return new AktiveSitzung(42L, spielerId, gastName, rolle, stage);
    }

    private static List<String> autoritaeten(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /**
     * Minimaler Ersatz fuer den {@link SessionService}. Die Konstruktorparameter bleiben leer,
     * weil ausschliesslich die ueberschriebene Methode aufgerufen wird.
     */
    private static class SessionServiceErsatz extends SessionService {

        private final Optional<AktiveSitzung> ergebnis;
        private String zuletztGeprueft;

        SessionServiceErsatz(Optional<AktiveSitzung> ergebnis) {
            super((SessionRepository) null, (ConfigService) null);
            this.ergebnis = ergebnis;
        }

        @Override
        public Optional<AktiveSitzung> pruefenUndVerlaengern(String token) {
            this.zuletztGeprueft = token;
            return ergebnis;
        }
    }
}
