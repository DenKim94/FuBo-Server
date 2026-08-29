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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die beiden Konfigurations-Endpunkte aus {@code S3_UMSETZUNG.md}, Abschnitt 5, und das
 * Nachziehen der Gastplaetze aus Abschnitt 6.
 *
 * <h2>Warum diese Klasse {@code @Transactional} tragen muss</h2>
 * Sie aendert eine <b>anwendungsweit gueltige, einzeilige</b> Tabelle. Ohne die Test-Transaktion
 * liefe sie anderen Klassen in die Quere - {@code GastControllerTests} verlaesst sich auf
 * {@code anz_guests = 4}, {@code SessionServiceTests} auf das Zwei-Timer-Modell mit 15 Minuten und
 * einer Stunde. Mit {@code @Transactional} wird jeder Fall zurueckgerollt; die Klasse darf deshalb
 * ausdruecklich <b>nicht</b> dem Muster von {@code PasswortResetControllerTests} folgen, das aus
 * einem anderen Grund darauf verzichtet.
 *
 * <p>Das ist hier auch gefahrlos: In S3 laeuft nichts in einer eigenen Transaktion. Der
 * Versuchszaehler mit {@code REQUIRES_NEW}, wegen dessen der Passwort-Reset seine Testdaten nicht
 * saehe, hat in diesem Meilenstein kein Gegenstueck.
 *
 * <h2>Testdaten</h2>
 * E-Mail-Adressen enden auf {@code @example.invalid} (RFC 2606), Adressen liegen in
 * {@code 203.0.113.0/24} (RFC 5737), Gastnamen sind neutral. Keine realen Personennamen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class KonfigurationControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Die zehn aenderbaren Felder in der Reihenfolge des Vertrags. */
    private static final List<String> AENDERBAR = List.of(
            "minTeilnehmer", "maxTeilnehmer", "anzGuests", "algorithmType", "anzTeamGenerator",
            "sessionLeerlaufMinuten", "sessionMaximalStunden", "halleEmail", "halleAbsageVorlage",
            "halleVorlaufStunden");

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

    /**
     * Die Antwort liefert die Seed-Werte aus {@code V004} und {@code V007}.
     *
     * <p>Wie bei {@code ConfigServiceTests} ist das kein Selbstzweck: {@code ddl-auto=validate}
     * vergleicht nur Spaltenexistenz und Typcode. Zwei vertauschte Felder gleichen Typs - etwa die
     * beiden Teilnehmerzahlen oder die beiden Sitzungs-Timer - faenden dabei nicht auf. Hier faellt
     * es auf, weil die Zahlen verschieden sind.
     */
    @Test
    void lesenLiefertDieSeedWerte() throws Exception {
        Map<String, Object> konfiguration = lesen();

        assertThat(zahl(konfiguration, "minTeilnehmer")).isEqualTo(6);
        assertThat(zahl(konfiguration, "maxTeilnehmer")).isEqualTo(22);
        assertThat(zahl(konfiguration, "anzGuests")).isEqualTo(4);
        assertThat(konfiguration.get("algorithmType")).isEqualTo("EXHAUSTIV");
        assertThat(zahl(konfiguration, "anzTeamGenerator")).isEqualTo(1);
        assertThat(zahl(konfiguration, "sessionLeerlaufMinuten")).isEqualTo(15);
        assertThat(zahl(konfiguration, "sessionMaximalStunden")).isEqualTo(1);
        assertThat(konfiguration.get("halleEmail")).isNull();
        assertThat(konfiguration.get("halleAbsageVorlage")).isNull();
        assertThat(zahl(konfiguration, "halleVorlaufStunden")).isEqualTo(48);

        assertThat(konfiguration.get("geaendertAm")).isNotNull();
        assertThat(konfiguration.get("version")).isNotNull();
    }

    /**
     * {@code geaendertVon} steht nicht in der Antwort.
     *
     * <p>Es gibt genau ein Admin-Konto; die Auskunft "geaendert von 1" waere inhaltsleer. Wer
     * wissen will, wer wann was geaendert hat, liest das Audit-Log - dort steht auch der
     * vorherige Wert.
     */
    @Test
    void lesenLiefertKeinGeaendertVon() throws Exception {
        assertThat(lesen()).doesNotContainKey("geaendertVon");
    }

    // --------------------------------------------------------------------- Aendern

    /** Ein Voll-Update schreibt alle zehn Felder in die Datenbank. */
    @Test
    void aendernSchreibtAlleZehnFelder() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("minTeilnehmer", 8);
        koerper.put("maxTeilnehmer", 20);
        koerper.put("anzGuests", 6);
        koerper.put("algorithmType", "HEURISTIK");
        koerper.put("anzTeamGenerator", 3);
        koerper.put("sessionLeerlaufMinuten", 30);
        koerper.put("sessionMaximalStunden", 8);
        koerper.put("halleEmail", "halle@example.invalid");
        koerper.put("halleAbsageVorlage", "Der Termin faellt leider aus.");
        koerper.put("halleVorlaufStunden", 24);

        aendern(koerper).andExpect(status().isNoContent());

        Map<String, Object> zeile = konfigurationsZeile();
        assertThat(spalte(zeile, "min_teilnehmer")).isEqualTo(8);
        assertThat(spalte(zeile, "max_teilnehmer")).isEqualTo(20);
        assertThat(spalte(zeile, "anz_guests")).isEqualTo(6);
        assertThat(zeile.get("algorithm_type")).isEqualTo("HEURISTIK");
        assertThat(spalte(zeile, "anz_team_generator")).isEqualTo(3);
        assertThat(spalte(zeile, "session_leerlauf_minuten")).isEqualTo(30);
        assertThat(spalte(zeile, "session_maximal_stunden")).isEqualTo(8);
        assertThat(zeile.get("halle_email")).isEqualTo("halle@example.invalid");
        assertThat(zeile.get("halle_absage_vorlage")).isEqualTo("Der Termin faellt leider aus.");
        assertThat(spalte(zeile, "halle_vorlauf_stunden")).isEqualTo(24);
    }

    /**
     * Die Maximalzahl darf nicht unter der Mindestzahl liegen, und die Meldung nennt beide Zahlen.
     *
     * <p>Die Datenbank hat dazu {@code ck_app_config_teilnehmer} - der braechte aber einen
     * {@code 500} mit einem Constraint-Namen im Log. Bean Validation kann die Regel nicht, weil sie
     * zwei Felder verbindet; sie liegt deshalb im Dienst.
     */
    @Test
    void maximalzahlUnterMindestzahlLiefert400MitBeidenZahlen() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("minTeilnehmer", 10);
        koerper.put("maxTeilnehmer", 8);

        String antwort = aendern(koerper)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("8").contains("10");
    }

    /**
     * Eine veraltete Version endet in {@code 409 DATEN_VERALTET}, und es wird nichts geschrieben.
     *
     * <p>Das ist der Fall zweier geoeffneter Browser-Tabs - mit einem einzigen Admin durchaus
     * moeglich. Ohne die Version zoege der zuletzt gespeicherte Tab die Aenderungen des anderen
     * stillschweigend zurueck.
     */
    @Test
    void veralteteVersionLiefert409UndSchreibtNichts() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("version", version() + 1);
        koerper.put("minTeilnehmer", 9);

        String antwort = aendern(koerper)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"DATEN_VERALTET\"");
        assertThat(spalte(konfigurationsZeile(), "min_teilnehmer")).isEqualTo(6);
    }

    /**
     * Auch der Sperrkonflikt aus Hibernate endet in {@code 409}, nicht in {@code 500}.
     *
     * <p><b>Warum es diesen Fall zusaetzlich zum vorherigen gibt:</b> Dort greift der
     * Versionsvergleich im Dienst, hier greift er ausdruecklich <i>nicht</i>. Der Vergleich liest,
     * der Schreibvorgang schreibt, und dazwischen liegt ein Fenster - genau das wird hier
     * geoeffnet: Der Lesezugriff legt die Entity in den Persistence-Context, ein direkter
     * {@code UPDATE} erhoeht die Version daneben, und der Vergleich sieht davon nichts mehr.
     * Erst Hibernate bemerkt es beim Schreiben.
     *
     * <p>Ohne den Handler fuer {@code ObjectOptimisticLockingFailureException} fiele das in den
     * Auffangzweig und kaeme als {@code 500 INTERNER_FEHLER} heraus - ein Bedienfehler, der wie
     * ein Serverfehler aussieht.
     */
    @Test
    void sperrkonfliktAusHibernateLiefert409UndKein500() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("minTeilnehmer", 7);

        // Die Sitzung wird bewusst vor dem Fremdzugriff angelegt und nicht wie sonst in
        // aendern(): Zwischen dem UPDATE und dem Schreibvorgang darf nichts mehr laufen, was
        // den Persistence-Context leeren koennte - sonst laese der Dienst die Zeile neu, der
        // Versionsvergleich griffe wieder, und der Fall pruefte den Handler gar nicht mehr.
        String token = adminSitzung();

        jdbc.update("UPDATE configs.app_config SET version = version + 1 WHERE id = 1");

        String antwort = mockMvc.perform(post("/api/v1/admin/config/aendern")
                        .cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(koerper)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"DATEN_VERALTET\"");
    }

    /**
     * Die Version steigt je Speichervorgang um genau 1 - auch dann, wenn sich kein fachlicher Wert
     * aendert, denn {@code geaendert_am} wandert bei jedem Speichern.
     */
    @Test
    void versionSteigtJeSpeichervorgangUmEins() throws Exception {
        long vorher = version();

        aendern(aktuellerKoerper()).andExpect(status().isNoContent());
        assertThat(version()).isEqualTo(vorher + 1);

        Map<String, Object> zweiterAufruf = aktuellerKoerper();
        zweiterAufruf.put("anzTeamGenerator", 2);
        aendern(zweiterAufruf).andExpect(status().isNoContent());

        assertThat(version()).isEqualTo(vorher + 2);
    }

    /**
     * Die Hallenadresse laesst sich wieder leeren - der Grund, aus dem der Endpunkt ein
     * Voll-Update ist. Feldweise waere {@code null} nicht von "nicht angegeben" zu unterscheiden.
     */
    @Test
    void halleEmailLaesstSichWiederLeeren() throws Exception {
        Map<String, Object> gesetzt = aktuellerKoerper();
        gesetzt.put("halleEmail", "halle@example.invalid");
        aendern(gesetzt).andExpect(status().isNoContent());

        Map<String, Object> geleert = aktuellerKoerper();
        geleert.put("halleEmail", null);
        aendern(geleert).andExpect(status().isNoContent());

        assertThat(konfigurationsZeile().get("halle_email")).isNull();
    }

    /** Ein unbekannter Aufzaehlungswert wird beim Lesen des Koerpers abgelehnt. */
    @Test
    void unbekannterAlgorithmusLiefert400() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("algorithmType", "ZUFALL");

        String antwort = aendern(koerper)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"");
    }

    /**
     * Mehr als 22 Gastplaetze werden abgelehnt.
     *
     * <p>Die Grenze ist der Riegel gegen den Tippfehler: {@code anz_guests} ist {@code SMALLINT}
     * ohne oberen CHECK, und seit S3 legt eine Erhoehung wirklich Zeilen an - "40" statt "4"
     * erzeugte 40 Plaetze, die niemand wieder loescht.
     */
    @Test
    void zuVieleGastplaetzeLiefert400() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("anzGuests", 40);

        String antwort = aendern(koerper)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("anzGuests");
        assertThat(gastSlots()).isEqualTo(4);
    }

    // --------------------------------------------------------------------- Gastplaetze

    /**
     * Eine Erhoehung legt die fehlenden Plaetze in derselben Transaktion an (Abschnitt 6.2).
     *
     * <p>Vorher blieb sie wirkungslos: {@code V007} legt vier Zeilen an, und die Grenze wirkt ueber
     * {@code id <= :maxGaeste} - eine fuenfte Zeile, die belegt werden koennte, gab es nicht.
     */
    @Test
    void anzGuestsErhoehenLegtFehlendePlaetzeAn() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("anzGuests", 6);
        aendern(koerper).andExpect(status().isNoContent());

        assertThat(gastSlots()).isEqualTo(6);

        List<Map<String, Object>> neue = jdbc.queryForList("""
                SELECT id, anzeige_name, belegt FROM profil.gast_slot WHERE id > 4 ORDER BY id
                """);

        assertThat(neue).hasSize(2);
        assertThat(neue.get(0).get("anzeige_name")).isEqualTo("Gast 5");
        assertThat(neue.get(1).get("anzeige_name")).isEqualTo("Gast 6");
        assertThat(neue).allSatisfy(zeile -> assertThat(zeile.get("belegt")).isEqualTo(false));
    }

    /**
     * Eine Senkung loescht keine Zeile - wirksam ist trotzdem nur noch die neue Grenze.
     *
     * <p>{@code fk_gast_slot_session} hat kein {@code ON DELETE}; ein belegter Platz liesse sich
     * ohnehin nicht loeschen, und die Meldung nennte den Fremdschluessel statt der Ursache. Die
     * Grenze {@code id <= :maxGaeste} erledigt die Sache vollstaendig: Der fuenfte Gast wird
     * abgewiesen, obwohl sechs Zeilen dastehen.
     */
    @Test
    void anzGuestsSenkenLoeschtKeineZeileUndDieGrenzeGreiftTrotzdem() throws Exception {
        Map<String, Object> hoch = aktuellerKoerper();
        hoch.put("anzGuests", 6);
        aendern(hoch).andExpect(status().isNoContent());
        assertThat(gastSlots()).isEqualTo(6);

        Map<String, Object> runter = aktuellerKoerper();
        runter.put("anzGuests", 4);
        aendern(runter).andExpect(status().isNoContent());

        assertThat(gastSlots()).isEqualTo(6);

        for (int nummer = 1; nummer <= 4; nummer++) {
            gastAnmelden("Testgast " + nummer).andExpect(status().isNoContent());
        }

        String antwort = gastAnmelden("Testgast 5")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"KEIN_GAST_SLOT_FREI\"");
    }

    // --------------------------------------------------------------------- Audit

    /**
     * Die Aenderung erzeugt einen Eintrag mit altem <b>und</b> neuem Wert je geaendertem Feld.
     *
     * <p>Anders als bei den Skillwerten lohnt sich der Vorher-Wert hier: Es sind hoechstens zehn
     * Werte, sie gelten anwendungsweit, und "seit wann steht das Leerlauf-Fenster auf 30 Minuten"
     * ist ohne ihn nicht zu beantworten. Unveraenderte Felder stehen nicht im Eintrag.
     *
     * <p>{@code jsonb_typeof} gehoert dazu: Eine Karte, die als Text in der Spalte landet, sieht
     * wie JSON aus und ist keines - {@code ->>'alt'} lieferte dann {@code null}, ohne dass
     * irgendwo ein Fehler auftraete.
     */
    @Test
    void aenderungErzeugtAuditEintragMitAltemUndNeuemWert() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("sessionLeerlaufMinuten", 30);
        aendern(koerper).andExpect(status().isNoContent());

        Map<String, Object> eintrag = jdbc.queryForMap("""
                SELECT entitaet,
                       entitaet_id,
                       akteur_spieler_id,
                       jsonb_typeof(details->'sessionLeerlaufMinuten')      AS typ,
                       details->'sessionLeerlaufMinuten'->>'alt'            AS alt,
                       details->'sessionLeerlaufMinuten'->>'neu'            AS neu,
                       jsonb_exists(details, 'minTeilnehmer')               AS unveraendert
                  FROM profil.audit_log
                 WHERE aktion = 'KONFIG_GEAENDERT'
                 ORDER BY id DESC
                 LIMIT 1
                """);

        assertThat(eintrag.get("entitaet")).isEqualTo("app_config");
        assertThat(((Number) eintrag.get("entitaet_id")).longValue()).isEqualTo(1L);
        assertThat(eintrag.get("akteur_spieler_id")).isEqualTo(adminSpielerId());
        assertThat(eintrag.get("typ")).isEqualTo("object");
        assertThat(eintrag.get("alt")).isEqualTo("15");
        assertThat(eintrag.get("neu")).isEqualTo("30");
        assertThat(eintrag.get("unveraendert")).isEqualTo(false);
    }

    /**
     * Die Absagevorlage steht nur als Vermerk im Protokoll, nicht mit ihrem Text.
     *
     * <p>Ein mehrzeiliger Text in jedem Eintrag blaehte die Tabelle auf, ohne etwas zu belegen.
     */
    @Test
    void absagevorlageStehtNurAlsVermerkImProtokoll() throws Exception {
        Map<String, Object> koerper = aktuellerKoerper();
        koerper.put("halleAbsageVorlage", "Sehr geehrte Damen und Herren, der Termin faellt aus.");
        aendern(koerper).andExpect(status().isNoContent());

        String vermerk = jdbc.queryForObject("""
                SELECT details->>'halleAbsageVorlage'
                  FROM profil.audit_log
                 WHERE aktion = 'KONFIG_GEAENDERT'
                 ORDER BY id DESC
                 LIMIT 1
                """, String.class);

        assertThat(vermerk).isEqualTo("geaendert");
    }

    // --------------------------------------------------------------------- Autorisierung

    /**
     * Beide Endpunkte sind fuer eine Spielersitzung gesperrt - {@code 403}, nicht {@code 401}.
     *
     * <p>Bei {@code 401} liefe das Frontend in eine Login-Schleife, obwohl die Sitzung gueltig ist:
     * Es fehlt die Rolle, nicht die Anmeldung.
     */
    @Test
    void mitUserSitzungLiefernBeideEndpunkte403() throws Exception {
        String token = userSitzung();

        mockMvc.perform(get("/api/v1/admin/config/lesen").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/config/aendern")
                        .cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /** Liest die Konfiguration ueber den Endpunkt und gibt sie als Karte zurueck. */
    private Map<String, Object> lesen() throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/admin/config/lesen")
                        .cookie(new Cookie(COOKIE, adminSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    /**
     * Baut einen vollstaendigen Anfragekoerper aus dem aktuellen Stand.
     *
     * <p>Genau der Ablauf, den der Vertrag vorschreibt: lesen, einzelne Werte ersetzen, das
     * Ganze zurueckschicken. Ein Test, der den Koerper von Hand zusammensetzte, wuerde beim
     * naechsten neuen Feld gruen bleiben und trotzdem das Falsche pruefen.
     */
    private Map<String, Object> aktuellerKoerper() throws Exception {
        Map<String, Object> gelesen = lesen();

        Map<String, Object> koerper = new LinkedHashMap<>();
        koerper.put("version", gelesen.get("version"));
        AENDERBAR.forEach(feld -> koerper.put(feld, gelesen.get(feld)));
        return koerper;
    }

    private ResultActions aendern(Map<String, Object> koerper) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/config/aendern")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(koerper)));
    }

    /** Meldet einen Gast an; die Stufe ist fuer die Platzvergabe ohne Bedeutung. */
    private ResultActions gastAnmelden(String name) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/gast/anmelden")
                .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gastName\":\"%s\",\"stufe\":\"MITTEL\"}".formatted(name)));
    }

    private Map<String, Object> konfigurationsZeile() {
        return jdbc.queryForMap("SELECT * FROM configs.app_config WHERE id = 1");
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM configs.app_config WHERE id = 1", Long.class);
    }

    private int gastSlots() {
        return jdbc.queryForObject("SELECT count(*) FROM profil.gast_slot", Integer.class);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject(
                "SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }

    private String userSitzung() {
        Long spielerId = jdbc.queryForObject(
                "SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1",
                Long.class);
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId, Rolle.USER);
    }

    /** Zahl aus der JSON-Antwort; Jackson liefert je nach Groesse Integer oder Long. */
    private static int zahl(Map<String, Object> karte, String feld) {
        return ((Number) karte.get(feld)).intValue();
    }

    /** Zahl aus einer Datenbankzeile; der Treiber liefert SMALLINT je nach Fassung verschieden. */
    private static int spalte(Map<String, Object> zeile, String name) {
        return ((Number) zeile.get(name)).intValue();
    }
}
