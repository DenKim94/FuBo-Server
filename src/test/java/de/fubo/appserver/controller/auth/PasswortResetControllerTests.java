package de.fubo.appserver.controller.auth;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.service.auth.PasswortResetService;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.utils.TokenGenerator;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den Passwort-Reset aus {@code S2b_UMSETZUNG.md}, Abschnitte 3 bis 5.
 *
 * <h2>Diese Klasse traegt bewusst kein {@code @Transactional}</h2>
 * Der Versuchszaehler laeuft mit {@code REQUIRES_NEW} in einer eigenen Transaktion. Liefe der
 * Test in einer umgebenden Test-Transaktion, saehe die innere Transaktion die dort erst
 * angelegte Reset-Zeile ueberhaupt nicht (READ COMMITTED) - jeder Bestaetigungsversuch
 * endete in {@code 409}, und die Faelle prueften das Gegenteil dessen, was sie sollen.
 * Aufgeraeumt wird deshalb von Hand, wie in {@code AuditServiceTests}.
 *
 * <h2>Der Mailversand wird durch einen handgeschriebenen Ersatz ausgetauscht</h2>
 * Kein Mockito und keine zusaetzliche Abhaengigkeit - dasselbe Vorgehen wie beim
 * {@code SessionService}-Ersatz in {@code SessionAuthFilterTests}. Der Ersatz merkt sich die
 * Nachrichten und kann auf Wunsch scheitern; nebenbei ist er der einzige Weg, im Test an die
 * erzeugte PIN zu kommen - sie steht nirgends sonst im Klartext.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PasswortResetControllerTests {

    private static final String COOKIE = "FUBO_SESSION";
    private static final String NEUES_PASSWORT = "ein-langes-neues-passwort";
    private static final Pattern PIN_IM_TEXT = Pattern.compile("(\\d{5})");

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private PasswortResetService passwortResetService;

    @Autowired
    private BruteForceService bruteForceService;

    @Autowired
    private PasswordEncoder passwortEncoder;

    @Autowired
    private MailErsatz mailErsatz;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private String urspruenglicherHash;

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
    }

    /**
     * Ohne umgebende Transaktion bleibt alles stehen, was die Faelle anlegen - auch das
     * geaenderte Adminpasswort. Beides wuerde in die naechste Testklasse hineinwirken.
     */
    @AfterEach
    void abbauen() {
        aufraeumen();
        jdbc.update("UPDATE profil.admin_konto SET passwort_hash = ? WHERE id = 1", urspruenglicherHash);
    }

    private void aufraeumen() {
        jdbc.update("DELETE FROM profil.passwort_reset");
        jdbc.update("DELETE FROM profil.audit_log WHERE akteur_bezeichnung LIKE '198.51.100.%'");
        // Nur die Sitzungen dieser Klasse: Gastsitzungen tragen einen gast_name, und alle
        // uebrigen Testklassen laufen transaktional und lassen nichts zurueck.
        jdbc.update("DELETE FROM profil.session WHERE gast_name IS NULL");
    }

    // ------------------------------------------------------------------ Anfordern

    /** Der Kernfall: eine Zeile, eine Nachricht, und der Empfaenger stimmt. */
    @Test
    void anforderungLegtVorgangAnUndVersendet() throws Exception {
        mockMvc.perform(anforderung("198.51.100.10")).andExpect(status().isNoContent());

        Map<String, Object> vorgang = jdbc.queryForMap(
                "SELECT versuche, verbraucht_am, angefordert_von_ip, gueltig_bis FROM profil.passwort_reset");
        assertThat(vorgang.get("versuche")).isEqualTo((short) 0);
        assertThat(vorgang.get("verbraucht_am")).isNull();
        assertThat(vorgang.get("angefordert_von_ip")).isEqualTo("198.51.100.10");

        assertThat(mailErsatz.nachrichten()).hasSize(1);
        SimpleMailMessage nachricht = mailErsatz.nachrichten().getFirst();
        assertThat(nachricht.getTo()).containsExactly(adminEmail());
        assertThat(nachricht.getText()).contains("15 Minuten");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log WHERE aktion = 'PASSWORT_RESET_ANGEFORDERT'
                """, Integer.class)).isEqualTo(1);
    }

    /**
     * Ein Datenbankabzug darf die PIN nicht verraten. Geprueft wird beides: dass der Klartext
     * nirgends steht und dass der gespeicherte Wert trotzdem zu ihm passt.
     */
    @Test
    void nurDerHashWirdGespeichert() throws Exception {
        mockMvc.perform(anforderung("198.51.100.11")).andExpect(status().isNoContent());

        String pin = versendetePin();
        String hash = jdbc.queryForObject("SELECT pin_hash FROM profil.passwort_reset", String.class);

        assertThat(hash).isNotEqualTo(pin).startsWith("$2");
        assertThat(passwortEncoder.matches(pin, hash)).isTrue();
    }

    /**
     * Ohne das Entwerten waeren mehrere PINs gleichzeitig gueltig, und jede zusaetzliche
     * vervielfachte die Trefferchance beim Raten.
     */
    @Test
    void neueAnforderungEntwertetDieAlte() throws Exception {
        mockMvc.perform(anforderung("198.51.100.12")).andExpect(status().isNoContent());
        String ersteP = versendetePin();

        mockMvc.perform(anforderung("198.51.100.12")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.passwort_reset WHERE verbraucht_am IS NULL",
                Integer.class))
                .as("Hoechstens ein offener Vorgang")
                .isEqualTo(1);

        mockMvc.perform(bestaetigung(ersteP, NEUES_PASSWORT, "198.51.100.12"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Drei Anforderungen je Stunde und Adresse. Die vierte wird gedrosselt - mit
     * maschinenlesbarer Restwartezeit, wie am PIN-Endpunkt.
     */
    @Test
    void vierteAnforderungInEinerStundeLiefert429() throws Exception {
        String ip = "198.51.100.13";
        for (int nr = 1; nr <= 3; nr++) {
            mockMvc.perform(anforderung(ip)).andExpect(status().isNoContent());
        }

        String antwort = mockMvc.perform(anforderung(ip))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"RESET_GEDROSSELT\"").contains("\"wartesekunden\":");
        assertThat(mailErsatz.nachrichten()).hasSize(3);
    }

    /**
     * Der Versand laeuft innerhalb der Transaktion: Scheitert er, bleibt keine PIN
     * gespeichert, die niemand bekommen hat.
     */
    @Test
    void versandfehlerRolltDenVorgangZurueck() throws Exception {
        mailErsatz.laesstScheitern(true);

        mockMvc.perform(anforderung("198.51.100.14"))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM profil.passwort_reset", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log WHERE aktion = 'PASSWORT_RESET_ANGEFORDERT'
                """, Integer.class))
                .as("Auch der Protokolleintrag darf nicht stehen bleiben")
                .isZero();
    }

    // ------------------------------------------------------------------ Bestaetigen

    /**
     * Der Kernfall: Das Passwort gilt, die Adminsitzungen sind widerrufen - und die
     * Spielersitzungen ausdruecklich nicht (offener Punkt 5).
     */
    @Test
    void richtigePinSetztPasswortUndWiderruftNurDieAdminSitzungen() throws Exception {
        String adminToken = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
        String spielerToken = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);

        mockMvc.perform(anforderung("198.51.100.15")).andExpect(status().isNoContent());

        mockMvc.perform(bestaetigung(versendetePin(), NEUES_PASSWORT, "198.51.100.15"))
                .andExpect(status().isNoContent());

        String hash = jdbc.queryForObject(
                "SELECT passwort_hash FROM profil.admin_konto WHERE id = 1", String.class);
        assertThat(passwortEncoder.matches(NEUES_PASSWORT, hash)).isTrue();

        assertThat(widerrufen(adminToken)).as("Die Adminsitzung ist widerrufen").isTrue();
        assertThat(widerrufen(spielerToken)).as("Die Spielersitzung bleibt bestehen").isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.passwort_reset WHERE verbraucht_am IS NULL",
                Integer.class))
                .as("Der Vorgang ist eingeloest und nicht erneut brauchbar")
                .isZero();

        assertThat(jdbc.queryForObject("""
                SELECT details->>'weg' FROM profil.audit_log WHERE aktion = 'PASSWORT_GEAENDERT'
                """, String.class)).isEqualTo("reset");
    }

    /**
     * <b>Der wichtigste Fall dieser Klasse.</b> Die Ablehnung rollt die Transaktion zurueck -
     * der Zaehler muss das ueberleben, sonst waere die Begrenzung auf fuenf Versuche
     * wirkungslos. Genau dafuer laeuft er mit {@code REQUIRES_NEW}.
     */
    @Test
    void falschePinZaehltDenVersuch() throws Exception {
        mockMvc.perform(anforderung("198.51.100.16")).andExpect(status().isNoContent());

        String antwort = mockMvc.perform(bestaetigung("00000", NEUES_PASSWORT, "198.51.100.16"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(antwort).contains("\"code\":\"RESET_PIN_FALSCH\"");

        assertThat(jdbc.queryForObject("SELECT versuche FROM profil.passwort_reset", Short.class))
                .as("Der Zaehler ueberlebt den Rollback")
                .isEqualTo((short) 1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log WHERE aktion = 'PASSWORT_RESET_FEHLVERSUCH'
                """, Integer.class))
                .as("Der Protokolleintrag entsteht im Controller und ueberlebt ebenfalls")
                .isEqualTo(1);

        assertThat(passwortEncoder.matches(NEUES_PASSWORT, jdbc.queryForObject(
                "SELECT passwort_hash FROM profil.admin_konto WHERE id = 1", String.class)))
                .as("Das Passwort darf nicht gesetzt worden sein")
                .isFalse();
    }

    /**
     * {@code ck_passwort_reset_versuche} laesst nur Werte bis 5 zu. Ohne die Bedingung
     * {@code versuche < :maxVersuche} liefe der sechste Versuch in eine
     * Constraint-Verletzung und damit in einen {@code 500} statt in eine saubere Ablehnung.
     */
    @Test
    void sechsterVersuchLiefert409() throws Exception {
        mockMvc.perform(anforderung("198.51.100.17")).andExpect(status().isNoContent());

        for (int versuch = 1; versuch <= 5; versuch++) {
            mockMvc.perform(bestaetigung("00000", NEUES_PASSWORT, "198.51.100.17"))
                    .andExpect(status().isUnauthorized());
        }

        // Fuenf Fehlversuche sperren die Adresse ueber den Brute-Force-Zaehler - er wuerde
        // vor dem Vorgangszaehler zuschlagen und den 409 verdecken. Hier interessiert
        // ausschliesslich die Erschoepfung des Vorgangs.
        bruteForceService.alleZuruecksetzen();

        String antwort = mockMvc.perform(bestaetigung("00000", NEUES_PASSWORT, "198.51.100.17"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"RESET_UNGUELTIG\"");
        assertThat(jdbc.queryForObject("SELECT versuche FROM profil.passwort_reset", Short.class))
                .isEqualTo((short) 5);
    }

    /** Der Ablaufzeitpunkt wird direkt gesetzt - keine Wartekonstruktion im Test. */
    @Test
    void abgelaufenerVorgangLiefert409() throws Exception {
        mockMvc.perform(anforderung("198.51.100.18")).andExpect(status().isNoContent());
        String pin = versendetePin();

        jdbc.update("UPDATE profil.passwort_reset SET gueltig_bis = ?",
                OffsetDateTime.now().minusMinutes(1));

        String antwort = mockMvc.perform(bestaetigung(pin, NEUES_PASSWORT, "198.51.100.18"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"RESET_UNGUELTIG\"");
    }

    /**
     * Die Eingabeform wird vor der PIN geprueft. Ein zu kurzes Passwort kostet deshalb
     * keinen der fuenf Versuche - sonst waere ein Vertipper im Passwortfeld teurer als ein
     * falsch geratener Wert.
     */
    @Test
    void zuKurzesPasswortLiefert400OhneVersuchZuVerbrauchen() throws Exception {
        mockMvc.perform(anforderung("198.51.100.19")).andExpect(status().isNoContent());

        String antwort = mockMvc.perform(bestaetigung(versendetePin(), "kurz", "198.51.100.19"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("neuesPasswort");
        assertThat(jdbc.queryForObject("SELECT versuche FROM profil.passwort_reset", Short.class))
                .isEqualTo((short) 0);
    }

    // ------------------------------------------------------------------ Aufraeumjob

    /**
     * Abschnitt 9: Vorgaenge jenseits der Frist verschwinden, juengere bleiben. Der
     * Zeitstempel wird direkt gesetzt, damit der Fall nicht an der konfigurierten Frist
     * haengt.
     */
    @Test
    void aufraeumjobEntferntNurAlteVorgaenge() throws Exception {
        mockMvc.perform(anforderung("198.51.100.20")).andExpect(status().isNoContent());
        jdbc.update("UPDATE profil.passwort_reset SET erstellt_am = ?",
                OffsetDateTime.now().minusDays(31));

        mockMvc.perform(anforderung("198.51.100.20")).andExpect(status().isNoContent());

        passwortResetService.alteVorgaengeEntfernen();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM profil.passwort_reset", Integer.class))
                .as("Nur der frische Vorgang bleibt")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ Berechtigung

    /** Der Reset liegt hinter der zentralen PIN - ohne Sitzung kommt niemand hin. */
    @Test
    void ohneSitzungLiefert401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/passwort/zuruecksetzen")
                        .header("CF-Connecting-IP", "198.51.100.21"))
                .andExpect(status().isUnauthorized());
    }

    /** Wer bereits eine Identitaet gewaehlt hat, ist an dieser Stelle falsch. */
    @Test
    void angemeldeterSpielerDarfNichtZuruecksetzen() throws Exception {
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);

        mockMvc.perform(post("/api/v1/auth/passwort/zuruecksetzen")
                        .cookie(new Cookie(COOKIE, token))
                        .header("CF-Connecting-IP", "198.51.100.22"))
                .andExpect(status().isForbidden());
    }

    /** Die aufrufende Sitzung bleibt gueltig - das Frontend geht direkt zum Admin-Login. */
    @Test
    void dieAufrufendeSitzungBleibtBestehen() throws Exception {
        String token = pinVerifiedSitzung();

        mockMvc.perform(post("/api/v1/auth/passwort/zuruecksetzen")
                        .cookie(new Cookie(COOKIE, token))
                        .header("CF-Connecting-IP", "198.51.100.23"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/session/lesen").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ Hilfsmittel

    private MockHttpServletRequestBuilder anforderung(String ip) {
        return post("/api/v1/auth/passwort/zuruecksetzen")
                .cookie(new Cookie(COOKIE, pinVerifiedSitzung()))
                .header("CF-Connecting-IP", ip);
    }

    private MockHttpServletRequestBuilder bestaetigung(String pin, String passwort, String ip) {
        return post("/api/v1/auth/passwort/bestaetigen")
                .cookie(new Cookie(COOKIE, pinVerifiedSitzung()))
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bestaetigungsPin\":\"%s\",\"neuesPasswort\":\"%s\"}".formatted(pin, passwort));
    }

    private String pinVerifiedSitzung() {
        return sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
    }

    /** Die PIN der zuletzt versendeten Nachricht - der einzige Ort, an dem sie im Klartext steht. */
    private String versendetePin() {
        assertThat(mailErsatz.nachrichten()).isNotEmpty();
        String text = mailErsatz.nachrichten().getLast().getText();
        Matcher treffer = PIN_IM_TEXT.matcher(text == null ? "" : text);
        assertThat(treffer.find()).as("Die Nachricht muss eine fuenfstellige PIN enthalten").isTrue();
        return treffer.group(1);
    }

    private boolean widerrufen(String token) {
        Boolean istWiderrufen = jdbc.queryForObject("""
                SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE token_hash = ?
                """, Boolean.class, TokenGenerator.hash(token));
        return Boolean.TRUE.equals(istWiderrufen);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }

    private String adminEmail() {
        return jdbc.queryForObject("SELECT email FROM profil.admin_konto WHERE id = 1", String.class);
    }

    private Long ersterSpieler() {
        return jdbc.queryForObject("""
                SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1
                """, Long.class);
    }

    // ------------------------------------------------------------------ Mailersatz

    @TestConfiguration
    static class MailErsatzConfig {

        /**
         * {@code @Primary} sticht die Bean aus {@code MailConfig}: Beide sind vom Typ
         * {@code JavaMailSender}, und ohne Vorrang waere die Einspeisung mehrdeutig.
         */
        @Bean
        @Primary
        MailErsatz mailErsatz() {
            return new MailErsatz();
        }
    }

    /**
     * Handgeschriebener Ersatz fuer den {@link JavaMailSender}.
     *
     * <p>Nur {@link #send(SimpleMailMessage)} wird gebraucht - alles Uebrige gehoert zum
     * MIME-Teil der Schnittstelle, den die Anwendung nicht benutzt. Die Methoden werfen
     * deshalb ausdruecklich, statt still nichts zu tun: Griffe die Anwendung eines Tages doch
     * darauf zu, soll das auffallen.
     */
    static class MailErsatz implements JavaMailSender {

        private final List<SimpleMailMessage> nachrichten = new ArrayList<>();
        private boolean scheitert;

        List<SimpleMailMessage> nachrichten() {
            return nachrichten;
        }

        void zuruecksetzen() {
            nachrichten.clear();
            scheitert = false;
        }

        /** Stellt den Versandfehler nach, den Abschnitt 3.4 mit {@code 503} beantwortet. */
        void laesstScheitern(boolean scheitert) {
            this.scheitert = scheitert;
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            if (scheitert) {
                throw new MailSendException("Versand im Test absichtlich fehlgeschlagen.");
            }
            nachrichten.add(simpleMessage);
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            for (SimpleMailMessage nachricht : simpleMessages) {
                send(nachricht);
            }
        }

        @Override
        public MimeMessage createMimeMessage() {
            throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException("Die Anwendung versendet ausschliesslich einfachen Text.");
        }
    }
}
