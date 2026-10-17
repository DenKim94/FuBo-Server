package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.service.profil.ProfilStammdatenCache;
import de.fubo.appserver.support.MailErsatz;
import de.fubo.appserver.support.MailErsatzConfig;
import de.fubo.appserver.utils.TokenGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Aenderung des Admin-Anmeldenamens mit Bestaetigung per E-Mail (A22, Ergaenzung
 * vom 04.10.2026): {@code /admin/name/aendern} fordert an, {@code /admin/name/bestaetigen}
 * uebernimmt.
 *
 * <p>Die sechs Faelle zur Namensaenderung standen bis dahin in
 * {@code ZugangsdatenControllerTests}; sie sind hierher umgezogen und auf den zweistufigen Ablauf
 * umgestellt. Dort konnten sie nicht bleiben, aus zwei Gruenden, die beide fuer sich tragen.
 *
 * <h2>Diese Klasse traegt bewusst kein {@code @Transactional}</h2>
 * Der Versuchszaehler laeuft mit {@code REQUIRES_NEW}. In einer umgebenden Test-Transaktion
 * saehe er die dort angelegte Anforderung nicht (READ COMMITTED), und jeder
 * Bestaetigungsversuch endete in {@code 409} - dieselbe Lage wie in
 * {@code PasswortResetControllerTests}. Dazu kommen zwei Faelle, die einen <b>Rollback</b>
 * pruefen (Versandfehler, Name zwischenzeitlich vergeben); in einer Test-Transaktion waeren sie
 * gruen und prueften das Gegenteil. Aufgeraeumt wird deshalb von Hand - einschliesslich des
 * Admin-Namens und des Admin-Passworts.
 *
 * <h2>Der Mailversand laeuft ueber {@link MailErsatz}</h2>
 * Er ist der einzige Ort, an dem die PIN im Klartext steht. Die {@code @Import}-Liste ist
 * <b>wortgleich</b> mit der von {@code PasswortResetControllerTests}, damit beide Klassen
 * denselben Anwendungskontext und denselben Datenbankcontainer teilen.
 *
 * <h2>Wo der Zufall stoeren wuerde, wird der Hash ersetzt</h2>
 * Zwei Faelle brauchen zwei verschiedene PINs. Bei zwei echten PINs stimmten sie einmal in
 * 100 000 Laeufen ueberein, und der Fall fiele grundlos um. Dort wird der gespeicherte Hash
 * deshalb nach dem Anfordern durch den einer bekannten PIN ersetzt.
 *
 * <p>Client-Adressen dieser Klasse liegen in {@code 203.0.113.100} bis {@code .199}; jeder
 * Aufruf, der einen Zaehler beruehren kann, bekommt seine eigene.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MailErsatzConfig.class})
class AdminNamensaenderungTests {

    private static final String COOKIE = "FUBO_SESSION";
    private static final String PASSWORT = "pruef-admin-0815";
    private static final Pattern PIN_IM_TEXT = Pattern.compile("PIN:\\s+(\\d{5})");

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private BruteForceService bruteForceService;

    @Autowired
    private ProfilStammdatenCache profilStammdatenCache;

    @Autowired
    private PasswordEncoder passwortEncoder;

    @Autowired
    private MailErsatz mailErsatz;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private String urspruenglicherHash;
    private String urspruenglicherName;
    private Long fremdesProfil;
    private String fremderName;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        bruteForceService.alleZuruecksetzen();
        mailErsatz.zuruecksetzen();
        aufraeumen();

        urspruenglicherHash = jdbc.queryForObject(
                "SELECT passwort_hash FROM profil.admin_konto WHERE id = 1", String.class);
        urspruenglicherName = adminName();
        fremdesProfil = jdbc.queryForObject(
                "SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1",
                Long.class);
        fremderName = jdbc.queryForObject(
                "SELECT name FROM profil.spieler WHERE id = ?", String.class, fremdesProfil);

        jdbc.update("UPDATE profil.admin_konto SET passwort_hash = ? WHERE id = 1",
                passwortEncoder.encode(PASSWORT));
    }

    /**
     * Ohne umgebende Transaktion bleibt alles stehen, was die Faelle aendern: Name und Passwort
     * des Admins, der Name des fremden Profils und die Anforderungen. Jedes davon wirkte in die
     * naechste Testklasse hinein - ein falscher Admin-Name liesse dort jede Anmeldung scheitern.
     * Der Zwischenspeicher wird verworfen, weil die Rueckstellung per SQL an ihm vorbeilaeuft.
     */
    @AfterEach
    void abbauen() {
        aufraeumen();
        jdbc.update("UPDATE profil.spieler SET name = ? WHERE id = ?", fremderName, fremdesProfil);
        jdbc.update("UPDATE profil.spieler SET name = ? WHERE id = ?", urspruenglicherName, adminSpielerId());
        jdbc.update("UPDATE profil.admin_konto SET passwort_hash = ? WHERE id = 1", urspruenglicherHash);
        profilStammdatenCache.verwerfen();
    }

    private void aufraeumen() {
        jdbc.update("DELETE FROM profil.name_aenderung");
        jdbc.update("DELETE FROM profil.passwort_reset");
        jdbc.update("DELETE FROM profil.audit_log WHERE akteur_bezeichnung LIKE '203.0.113.1__'");
        // Dieselbe Abgrenzung wie in PasswortResetControllerTests: Gastsitzungen tragen einen
        // gast_name, und die transaktionalen Klassen lassen keine Sitzung zurueck.
        jdbc.update("DELETE FROM profil.session WHERE gast_name IS NULL");
    }

    // ------------------------------------------------------------------ Anfordern

    /**
     * Der Kern der Ergaenzung: Die Anforderung aendert den Namen <b>nicht</b>. Sie legt einen
     * Vorgang an, schickt eine Nachricht an die hinterlegte Adresse und antwortet {@code 202}.
     */
    @Test
    void anforderungAendertDenNamenNochNicht() throws Exception {
        MvcResult ergebnis = mockMvc.perform(anforderung("Pruefadmin Neu", "203.0.113.100"))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(adminName()).isEqualTo(urspruenglicherName);

        Map<String, Object> vorgang = jdbc.queryForMap(
                "SELECT neuer_name, versuche, verbraucht_am, gueltig_bis > now() AS gueltig "
                        + "FROM profil.name_aenderung");
        assertThat(vorgang.get("neuer_name")).isEqualTo("Pruefadmin Neu");
        assertThat(((Number) vorgang.get("versuche")).intValue()).isZero();
        assertThat(vorgang.get("verbraucht_am")).isNull();
        assertThat(vorgang.get("gueltig")).isEqualTo(true);

        assertThat(mailErsatz.nachrichten()).hasSize(1);
        SimpleMailMessage nachricht = mailErsatz.nachrichten().getFirst();
        assertThat(nachricht.getTo()).containsExactly(adminEmail());
        assertThat(nachricht.getSubject()).contains("Anmeldenamens");
        assertThat(nachricht.getText())
                .as("Die Nachricht nennt den Namen, den die PIN bestaetigt")
                .contains("Neuer Anmeldename: Pruefadmin Neu");
        assertThat(versendetePin()).hasSize(5);
    }

    /** Ein vergebener Name faellt vor dem Versand auf - es geht keine Nachricht hinaus. */
    @Test
    void belegterNameLiefert409OhneNachricht() throws Exception {
        ResultActions ergebnis = mockMvc.perform(anforderung(fremderName, "203.0.113.101"))
                .andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("NAME_BELEGT");
        assertThat(mailErsatz.nachrichten()).isEmpty();
        assertThat(anzahlAnforderungen()).isZero();
    }

    /** Ein leerer Name wird von der Bean Validation abgefangen, vor jedem Schreibzugriff. */
    @Test
    void leererNameLiefert400() throws Exception {
        mockMvc.perform(anforderung("   ", "203.0.113.102"))
                .andExpect(status().isBadRequest());

        assertThat(mailErsatz.nachrichten()).isEmpty();
        assertThat(anzahlAnforderungen()).isZero();
    }

    /**
     * Drei Anforderungen je Stunde und Adresse, die vierte wird gedrosselt - mit eigenem Code
     * und maschinenlesbarer Restwartezeit.
     */
    @Test
    void vierteAnforderungInEinerStundeLiefert429() throws Exception {
        String ip = "203.0.113.103";
        for (int nr = 1; nr <= 3; nr++) {
            mockMvc.perform(anforderung("Pruefadmin " + nr, ip)).andExpect(status().isAccepted());
        }

        ResultActions ergebnis = mockMvc.perform(anforderung("Pruefadmin 4", ip))
                .andExpect(status().isTooManyRequests());

        assertThat(fehlercode(ergebnis)).isEqualTo("NAME_AENDERUNG_GEDROSSELT");
        assertThat(alsKarte(ergebnis).get("wartesekunden")).isNotNull();
        assertThat(ergebnis.andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(mailErsatz.nachrichten()).hasSize(3);
    }

    /**
     * Scheitert der Versand, bleibt nichts zurueck - und eine zuvor offene Anforderung bleibt
     * gueltig, weil auch ihr Entwerten zurueckrollt. Nur ohne Test-Transaktion pruefbar.
     */
    @Test
    void versandfehlerHinterlaesstKeinenVorgang() throws Exception {
        mockMvc.perform(anforderung("Pruefadmin Alt", "203.0.113.104")).andExpect(status().isAccepted());
        String pin = versendetePin();

        mailErsatz.laesstScheitern(true);
        ResultActions ergebnis = mockMvc.perform(anforderung("Pruefadmin Neu", "203.0.113.105"))
                .andExpect(status().isServiceUnavailable());
        mailErsatz.laesstScheitern(false);

        assertThat(fehlercode(ergebnis)).isEqualTo("VERSAND_FEHLGESCHLAGEN");
        assertThat(anzahlAnforderungen()).as("nur die erste Anforderung").isEqualTo(1);

        mockMvc.perform(bestaetigung(pin, adminSitzung(), "203.0.113.106"))
                .andExpect(status().isNoContent());
        assertThat(adminName()).isEqualTo("Pruefadmin Alt");
    }

    // ------------------------------------------------------------------ Bestaetigen

    /**
     * Mit der richtigen PIN wird der Name uebernommen - und die Sitzung bleibt bestehen, wie
     * vor der Ergaenzung (Vorgabe vom 29.08.2026): kein Cookie-Wechsel, kein Widerruf.
     */
    @Test
    void bestaetigungUebernimmtDenNamenUndLaesstDieSitzungBestehen() throws Exception {
        String token = adminSitzung();
        mockMvc.perform(anforderung("Pruefadmin Neu", token, "203.0.113.110")).andExpect(status().isAccepted());

        MvcResult ergebnis = mockMvc.perform(bestaetigung(versendetePin(), token, "203.0.113.111"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .as("Kein Cookie-Wechsel - anders als bei der Passwortaenderung")
                .isNull();
        assertThat(widerrufen(token)).isFalse();
        mockMvc.perform(get("/api/v1/auth/session/lesen").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk());

        assertThat(adminName()).isEqualTo("Pruefadmin Neu");
        assertThat(jdbc.queryForObject(
                "SELECT verbraucht_am IS NOT NULL FROM profil.name_aenderung", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log
                 WHERE aktion = 'ADMIN_NAME_GEAENDERT'
                   AND details ->> 'nameAlt' = ? AND details ->> 'nameNeu' = 'Pruefadmin Neu'
                """, Integer.class, urspruenglicherName)).isEqualTo(1);
    }

    /**
     * Nach der Bestaetigung traegt der neue Name die Anmeldung - und der alte nicht mehr. Damit
     * ist die Kette geschlossen: Der Profilname <i>ist</i> der Anmeldename.
     */
    @Test
    void nachDerBestaetigungGiltDerNeueNameBeimAnmelden() throws Exception {
        mockMvc.perform(anforderung("Pruefadmin Zwei", "203.0.113.112")).andExpect(status().isAccepted());
        mockMvc.perform(bestaetigung(versendetePin(), adminSitzung(), "203.0.113.113"))
                .andExpect(status().isNoContent());

        anmelden("Pruefadmin Zwei", "203.0.113.114").andExpect(status().isNoContent());
        anmelden(urspruenglicherName, "203.0.113.115").andExpect(status().isUnauthorized());
    }

    /**
     * Randleerzeichen werden schon beim Anfordern entfernt - <b>und das ist hier wesentlich</b>:
     * Der Anmelde-Endpunkt trimmt seine Eingabe ebenfalls, ein mit Leerzeichen gespeicherter
     * Name liesse sich nie eingeben.
     */
    @Test
    void randleerzeichenWerdenEntferntUndDerNameBleibtEingebbar() throws Exception {
        mockMvc.perform(anforderung("  Pruefadmin Drei  ", "203.0.113.116")).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT neuer_name FROM profil.name_aenderung", String.class))
                .isEqualTo("Pruefadmin Drei");

        mockMvc.perform(bestaetigung(versendetePin(), adminSitzung(), "203.0.113.117"))
                .andExpect(status().isNoContent());

        assertThat(adminName()).isEqualTo("Pruefadmin Drei");
        anmelden("Pruefadmin Drei", "203.0.113.118").andExpect(status().isNoContent());
    }

    /**
     * Eine falsche PIN kostet einen Versuch, aendert nichts und wird protokolliert. {@code 401}
     * mit eigenem Code - die Sitzung besteht weiter.
     */
    @Test
    void falschePinLiefert401UndZaehltDenVersuch() throws Exception {
        String token = adminSitzung();
        mockMvc.perform(anforderung("Pruefadmin Neu", token, "203.0.113.120")).andExpect(status().isAccepted());

        ResultActions ergebnis = mockMvc.perform(bestaetigung(anderePin(versendetePin()), token, "203.0.113.121"))
                .andExpect(status().isUnauthorized());

        assertThat(fehlercode(ergebnis)).isEqualTo("NAME_PIN_FALSCH");
        assertThat(adminName()).isEqualTo(urspruenglicherName);
        assertThat(jdbc.queryForObject("SELECT versuche FROM profil.name_aenderung", Short.class))
                .isEqualTo((short) 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log
                 WHERE aktion = 'ADMIN_NAME_PIN_FEHLVERSUCH' AND akteur_bezeichnung = '203.0.113.121'
                """, Integer.class)).isEqualTo(1);
        assertThat(widerrufen(token)).isFalse();
    }

    /** Eine PIN im falschen Format scheitert an der Bean Validation und kostet keinen Versuch. */
    @Test
    void pinImFalschenFormatKostetKeinenVersuch() throws Exception {
        mockMvc.perform(anforderung("Pruefadmin Neu", "203.0.113.122")).andExpect(status().isAccepted());

        mockMvc.perform(bestaetigung("1234", adminSitzung(), "203.0.113.123"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT versuche FROM profil.name_aenderung", Short.class))
                .isEqualTo((short) 0);
    }

    /**
     * Nach fuenf Fehlversuchen ist die Anforderung erschoepft - auch die richtige PIN hilft
     * dann nicht mehr. Jeder Versuch kommt von einer eigenen Adresse, sonst griffe vorher die
     * Sperre des Brute-Force-Zaehlers (ebenfalls fuenf je Adresse).
     */
    @Test
    void nachFuenfFehlversuchenIstDieAnforderungErschoepft() throws Exception {
        mockMvc.perform(anforderung("Pruefadmin Neu", "203.0.113.130")).andExpect(status().isAccepted());
        String pin = versendetePin();

        for (int nr = 1; nr <= 5; nr++) {
            mockMvc.perform(bestaetigung(anderePin(pin), adminSitzung(), "203.0.113.13" + nr))
                    .andExpect(status().isUnauthorized());
        }

        ResultActions ergebnis = mockMvc.perform(bestaetigung(pin, adminSitzung(), "203.0.113.136"))
                .andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("NAME_AENDERUNG_UNGUELTIG");
        assertThat(adminName()).isEqualTo(urspruenglicherName);
    }

    /** Ohne Anforderung gibt es nichts einzuloesen - {@code 409}, nicht {@code 401}. */
    @Test
    void ohneAnforderungLiefert409() throws Exception {
        ResultActions ergebnis = mockMvc.perform(bestaetigung("12345", adminSitzung(), "203.0.113.140"))
                .andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("NAME_AENDERUNG_UNGUELTIG");
    }

    /**
     * Eine neue Anforderung entwertet die alte: Deren PIN ist danach wertlos, und es gilt der
     * zuletzt genannte Name. Die Hashes werden durch bekannte PINs ersetzt (Klassenkommentar).
     */
    @Test
    void eineNeueAnforderungEntwertetDieAlte() throws Exception {
        mockMvc.perform(anforderung("Pruefadmin Erst", "203.0.113.141")).andExpect(status().isAccepted());
        setzePin("Pruefadmin Erst", "00000");
        mockMvc.perform(anforderung("Pruefadmin Zuletzt", "203.0.113.142")).andExpect(status().isAccepted());
        setzePin("Pruefadmin Zuletzt", "99999");

        assertThat(jdbc.queryForObject(
                "SELECT verbraucht_am IS NOT NULL FROM profil.name_aenderung WHERE neuer_name = 'Pruefadmin Erst'",
                Boolean.class)).as("die erste Anforderung ist entwertet").isTrue();

        mockMvc.perform(bestaetigung("00000", adminSitzung(), "203.0.113.143"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(bestaetigung("99999", adminSitzung(), "203.0.113.144"))
                .andExpect(status().isNoContent());

        assertThat(adminName()).isEqualTo("Pruefadmin Zuletzt");
    }

    /**
     * <b>Die Trennung, wegen der es eine eigene Tabelle gibt</b> (Entscheidung vom 04.10.2026):
     * Eine Namensanforderung entwertet keinen offenen Passwort-Reset, und dessen PIN loest keine
     * Namensaenderung ein. Bei einer gemeinsamen Tabelle mit vergessener Zweckbedingung fiele
     * genau dieser Fall um.
     */
    @Test
    void namensaenderungUndPasswortResetBleibenGetrennt() throws Exception {
        mockMvc.perform(post("/api/v1/auth/passwort/zuruecksetzen")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null)))
                        .header("CF-Connecting-IP", "203.0.113.150"))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE profil.passwort_reset SET pin_hash = ?", passwortEncoder.encode("33333"));

        mockMvc.perform(anforderung("Pruefadmin Neu", "203.0.113.151")).andExpect(status().isAccepted());
        setzePin("Pruefadmin Neu", "22222");

        assertThat(jdbc.queryForObject(
                "SELECT verbraucht_am IS NULL FROM profil.passwort_reset", Boolean.class))
                .as("der Reset bleibt offen").isTrue();

        mockMvc.perform(bestaetigung("33333", adminSitzung(), "203.0.113.152"))
                .andExpect(status().isUnauthorized());

        assertThat(adminName()).isEqualTo(urspruenglicherName);
        assertThat(jdbc.queryForObject("SELECT versuche FROM profil.passwort_reset", Short.class))
                .as("der Reset-Zaehler bleibt unberuehrt").isEqualTo((short) 0);
    }

    /**
     * Wird der Name zwischen Anfordern und Bestaetigen an ein anderes Profil vergeben, scheitert
     * das Einloesen mit {@code 409 NAME_BELEGT} - und rollt ganz zurueck: Der Admin-Name bleibt,
     * die Anforderung bleibt offen. Nur ohne Test-Transaktion pruefbar.
     */
    @Test
    void zwischenzeitlichVergebenerNameLiefert409() throws Exception {
        mockMvc.perform(anforderung("Pruefadmin Spaeter", "203.0.113.160")).andExpect(status().isAccepted());
        jdbc.update("UPDATE profil.spieler SET name = 'Pruefadmin Spaeter' WHERE id = ?", fremdesProfil);

        ResultActions ergebnis = mockMvc.perform(bestaetigung(versendetePin(), adminSitzung(), "203.0.113.161"))
                .andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("NAME_BELEGT");
        assertThat(adminName()).isEqualTo(urspruenglicherName);
        assertThat(jdbc.queryForObject(
                "SELECT verbraucht_am IS NULL FROM profil.name_aenderung", Boolean.class)).isTrue();
    }

    /** Beide Endpunkte liegen unter {@code /admin/} und verlangen damit die Rolle. */
    @Test
    void spielerDarfDenAnmeldenamenNichtAendern() throws Exception {
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, fremdesProfil, Rolle.USER);

        mockMvc.perform(anforderung("Pruefadmin Vier", token, "203.0.113.170"))
                .andExpect(status().isForbidden());
        mockMvc.perform(bestaetigung("12345", token, "203.0.113.171"))
                .andExpect(status().isForbidden());

        assertThat(mailErsatz.nachrichten()).isEmpty();
    }

    // ------------------------------------------------------------------ Hilfsmittel

    private MockHttpServletRequestBuilder anforderung(String name, String ip) {
        return anforderung(name, adminSitzung(), ip);
    }

    private MockHttpServletRequestBuilder anforderung(String name, String token, String ip) {
        return post("/api/v1/admin/name/aendern")
                .cookie(new Cookie(COOKIE, token))
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"neuerName\":\"%s\"}".formatted(name));
    }

    private MockHttpServletRequestBuilder bestaetigung(String pin, String token, String ip) {
        return post("/api/v1/admin/name/bestaetigen")
                .cookie(new Cookie(COOKIE, token))
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bestaetigungsPin\":\"%s\"}".formatted(pin));
    }

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }

    /**
     * Meldet sich ueber {@code /auth/admin/anmelden} an - mit dem Passwort aus
     * {@link #aufbauen()}. Jeder Aufruf braucht eine eigene Adresse: Der Brute-Force-Zaehler ist
     * mit dem PIN-Endpunkt geteilt.
     */
    private ResultActions anmelden(String anmeldename, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/admin/anmelden")
                .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null)))
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anmeldename\":\"%s\",\"passwort\":\"%s\"}".formatted(anmeldename, PASSWORT)));
    }

    /**
     * Die PIN der zuletzt versendeten Nachricht. Gesucht wird hinter "PIN:", nicht nach der
     * ersten fuenfstelligen Zahl - die Nachricht nennt auch den Namen, und ein Name mit fuenf
     * Ziffern stuende davor.
     */
    private String versendetePin() {
        assertThat(mailErsatz.nachrichten()).isNotEmpty();
        String text = mailErsatz.nachrichten().getLast().getText();
        Matcher treffer = PIN_IM_TEXT.matcher(text == null ? "" : text);
        assertThat(treffer.find()).as("Die Nachricht muss eine fuenfstellige PIN enthalten").isTrue();
        return treffer.group(1);
    }

    /** Eine gueltig geformte PIN, die sicher nicht die richtige ist. */
    private static String anderePin(String pin) {
        return "%05d".formatted((Integer.parseInt(pin) + 1) % 100_000);
    }

    /** Ersetzt den gespeicherten Hash einer Anforderung durch den einer bekannten PIN. */
    private void setzePin(String neuerName, String pin) {
        int zeilen = jdbc.update("UPDATE profil.name_aenderung SET pin_hash = ? WHERE neuer_name = ?",
                passwortEncoder.encode(pin), neuerName);
        assertThat(zeilen).isEqualTo(1);
    }

    private int anzahlAnforderungen() {
        Integer anzahl = jdbc.queryForObject("SELECT count(*) FROM profil.name_aenderung", Integer.class);
        return anzahl == null ? 0 : anzahl;
    }

    private Map<String, Object> alsKarte(ResultActions ergebnis) throws Exception {
        return objectMapper.readValue(
                ergebnis.andReturn().getResponse().getContentAsString(), new TypeReference<>() {
                });
    }

    /** Der Fehlercode aus dem Problem Detail; nie der Meldungstext - der ist Anzeigetext. */
    private String fehlercode(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("code");
    }

    private boolean widerrufen(String token) {
        Boolean istWiderrufen = jdbc.queryForObject(
                "SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE token_hash = ?",
                Boolean.class, TokenGenerator.hash(token));
        return Boolean.TRUE.equals(istWiderrufen);
    }

    private String adminName() {
        return jdbc.queryForObject("SELECT name FROM profil.spieler WHERE id = ?", String.class, adminSpielerId());
    }

    private String adminEmail() {
        return jdbc.queryForObject("SELECT email FROM profil.admin_konto WHERE id = 1", String.class);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }
}
