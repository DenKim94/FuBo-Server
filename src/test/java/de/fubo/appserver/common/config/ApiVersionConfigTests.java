package de.fubo.appserver.common.config;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Pfadsegment-Versionierung aus {@link ApiVersionConfig}.
 *
 * <p>Geprueft wird ausschliesslich das Verhalten am echten Endpunkt
 * {@code GET /api/{version}/auth/users/lesen}. Ein Unit-Test waere hier ohne Aussagekraft:
 * Die Versionierung entsteht erst aus dem Zusammenspiel von {@code ApiVersionResolver},
 * {@code ApiVersionParser} und der Zuordnung im {@code RequestMappingHandlerMapping}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ApiVersionConfigTests {

    private static final String COOKIE = "FUBO_SESSION";
    private static final String NAMENSLISTE = "/api/%s/auth/users/lesen";

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

    /** Der Normalfall: die einzige unterstuetzte Version. */
    @Test
    void unterstuetzteVersionWirdBedient() throws Exception {
        mockMvc.perform(get(NAMENSLISTE.formatted("v1")).cookie(sitzungsCookie(Stage.PIN_VERIFIED)))
                .andExpect(status().isOk());
    }

    /**
     * Der voreingestellte {@code SemanticApiVersionParser} ueberspringt fuehrende
     * Nicht-Ziffern; {@code /api/1/...} wird deshalb wie {@code /api/v1/...} gelesen.
     *
     * <p>Der Test haelt das fest, damit die Toleranz nicht versehentlich verloren geht -
     * nach aussen kommuniziert wird trotzdem ausschliesslich {@code v1}.
     */
    @Test
    void versionOhneFuehrendesVWirdEbenfallsAkzeptiert() throws Exception {
        mockMvc.perform(get(NAMENSLISTE.formatted("1")).cookie(sitzungsCookie(Stage.PIN_VERIFIED)))
                .andExpect(status().isOk());
    }

    /**
     * Eine nicht unterstuetzte Version wird mit {@code 400} abgelehnt
     * ({@code InvalidApiVersionException}), nicht stillschweigend auf v1 abgebildet.
     *
     * <p>Die Filterchain laesst den Aufruf vorher durch - ihre Regeln enthalten fuer das
     * Versionssegment ein Sternchen. Die Pruefung der Version ist Sache der Zuordnung, nicht
     * der Autorisierung.
     */
    @Test
    void unbekannteVersionLiefert400() throws Exception {
        mockMvc.perform(get(NAMENSLISTE.formatted("v99")).cookie(sitzungsCookie(Stage.PIN_VERIFIED)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Ohne Versionssegment gibt es den Pfad schlicht nicht - die Antwort ist {@code 404}.
     *
     * <p>Das ist die praktische Ausformung von "Version ist Pflicht": Bei der
     * Pfadsegment-Strategie entsteht kein {@code MissingApiVersionException}, weil ein
     * fehlendes Segment den Pfad verkuerzt und damit auf kein Mapping mehr passt. Geprueft
     * wird mit einer Sitzung in Stufe 2, weil der Aufruf sonst schon an der Autorisierung
     * mit {@code 403} endet und die Aussage verfehlt waere.
     */
    @Test
    void fehlendesVersionssegmentLiefert404() throws Exception {
        mockMvc.perform(get("/api/auth/users/lesen").cookie(sitzungsCookie(Stage.PROFILE_AUTHENTICATED)))
                .andExpect(status().isNotFound());
    }

    /**
     * Der Container-Healthcheck ruft einen festen, unversionierten Pfad auf.
     *
     * <p>Genau dafuer bekommt {@code usePathSegment} ein {@code Predicate}: Ohne das
     * versuchte der Resolver, {@code health} als Version zu lesen, und der Endpunkt
     * antwortete mit {@code 400}. Der Container waere dauerhaft {@code unhealthy}.
     */
    @Test
    void actuatorHealthBrauchtKeineVersion() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private Cookie sitzungsCookie(Stage stufe) {
        String token = (stufe == Stage.PIN_VERIFIED)
                ? sessionService.anlegen(Stage.PIN_VERIFIED, null, null)
                : sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);
        return new Cookie(COOKIE, token);
    }

    /** Liefert eine beliebige Profil-Id aus den Demodaten (keine realen Namen). */
    private Long ersterSpieler() {
        return jdbc.queryForObject("SELECT id FROM profil.spieler ORDER BY id LIMIT 1", Long.class);
    }
}
