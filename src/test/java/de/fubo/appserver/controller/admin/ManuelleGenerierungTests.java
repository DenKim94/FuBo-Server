package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.service.profil.ProfilStammdatenCache;
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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den manuellen Generierungslauf des Admins:
 * {@code POST /api/v1/admin/teams/generieren} (A24, S5 Abschnitte 2.5 und 9.4).
 *
 * <h2>Diese Klasse legt keinen Termin an - und das ist die Pruefung</h2>
 * A24 ist terminfrei. Sie braucht deshalb <b>keinen Zeitstreifen</b> in
 * {@code uq_termin_zeit}: Sobald sie einen bekommen muss, hat sich eine
 * Terminabhaengigkeit eingeschlichen. Das ist die schnellste Gegenprobe, die es dafuer gibt.
 *
 * <h2>Die beiden wichtigsten Faelle pruefen eine Abwesenheit</h2>
 * Dass nach dem Lauf {@code team_generierung} und {@code team_zuteilung} leer bleiben, und
 * dass kein Kontingent verbraucht wird. <b>Abwesenheit ist das, was beim Bauen versehentlich
 * verschwindet</b>: Ein aus dem Termin-Pfad kopiertes Speichern faellt in keinem anderen Test
 * auf - die Teams saehen richtig aus.
 *
 * <h2>Die Konfiguration wird vor dem ersten HTTP-Aufruf gesetzt</h2>
 * Jeder Aufruf laedt ueber den Sitzungsfilter die Konfigurationszeile in den
 * Persistence-Context; eine spaetere Aenderung per SQL bliebe fuer denselben Vorgang
 * unsichtbar, und der Test waere gruen, ohne etwas zu pruefen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ManuelleGenerierungTests {

    private static final String COOKIE = "FUBO_SESSION";

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProfilStammdatenCache profilStammdatenCache;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        profilStammdatenCache.verwerfen();
    }

    // --------------------------------------------------------------------- Der Lauf

    /** Acht benannte Profile ergeben zwei Teams zu vier - ohne jeden Termin. */
    @Test
    void achtProfileErgebenZweiTeamsZuVier() throws Exception {
        Map<String, Object> antwort = alsKarte(
                manuell(spielerIds(8), "[]").andExpect(status().isOk()));

        assertThat(namen(antwort, "teamA")).hasSize(4);
        assertThat(namen(antwort, "teamB")).hasSize(4);
        assertThat(alleNamen(antwort)).hasSize(8).doesNotHaveDuplicates();
    }

    /** Gaeste mit Stufe werden eingeteilt und als solche gekennzeichnet. */
    @Test
    void gaesteWerdenEingeteiltUndGekennzeichnet() throws Exception {
        Map<String, Object> antwort = alsKarte(manuell(spielerIds(6),
                "[{\"stufe\":\"STARK\",\"name\":\"Testgast 1\"},"
                        + "{\"stufe\":\"SCHWACH\",\"name\":\"Testgast 2\"}]")
                .andExpect(status().isOk()));

        assertThat(alleNamen(antwort)).hasSize(8).contains("Testgast 1", "Testgast 2");
        assertThat(alleEintraege(antwort).stream()
                .filter(eintrag -> Boolean.TRUE.equals(eintrag.get("gast")))
                .map(eintrag -> (String) eintrag.get("anzeigeName")))
                .containsExactlyInAnyOrder("Testgast 1", "Testgast 2");
    }

    /**
     * Ohne Namen vergibt der Server {@code Gast 1}, {@code Gast 2}, ... nach der
     * <b>Position in der Liste</b>.
     *
     * <p>Nicht nach der Zahl der unbenannten Gaeste: Der zweite Gast heisst {@code Gast 2},
     * auch wenn der erste einen Namen trug. Das ist die Regel, die sich ohne Blick auf die
     * anderen Eintraege vorhersagen laesst.
     */
    @Test
    void gaesteOhneNamenBekommenDieVorgabe() throws Exception {
        Map<String, Object> antwort = alsKarte(manuell(spielerIds(6),
                "[{\"stufe\":\"MITTEL\"},{\"stufe\":\"MITTEL\"}]").andExpect(status().isOk()));

        assertThat(alleNamen(antwort)).contains("Gast 1", "Gast 2");
    }

    // --------------------------------------------------------------------- Ablehnungen

    /** Ohne jeden Teilnehmer gibt es nichts zu rechnen. */
    @Test
    void leereAuswahlWirdAbgelehnt() throws Exception {
        assertThat(fehlercode(manuell("[]", "[]").andExpect(status().isBadRequest())))
                .isEqualTo("EINGABE_UNGUELTIG");
    }

    /** Eine doppelt genannte Id wird abgelehnt und benannt. */
    @Test
    void doppelteIdWirdAbgelehnt() throws Exception {
        Long id = spielerId(0);
        String ids = "[%d,%d,%d,%d,%d,%d,%d]".formatted(
                id, id, spielerId(1), spielerId(2), spielerId(3), spielerId(4), spielerId(5));

        ResultActions ergebnis = manuell(ids, "[]").andExpect(status().isBadRequest());

        assertThat(fehlercode(ergebnis)).isEqualTo("EINGABE_UNGUELTIG");
        assertThat(detail(ergebnis)).contains(String.valueOf(id));
    }

    /**
     * Ein doppelter Anzeigename wird abgelehnt - auch dann, wenn er einem ausgewaehlten
     * Profil gehoert.
     *
     * <p>Zwei gleiche Namen in der Teamausgabe sind genau die Verwechslung, die der Gastname
     * verhindern soll.
     */
    @Test
    void gastnameDarfNichtMitEinemProfilnamenZusammenfallen() throws Exception {
        ResultActions ergebnis = manuell(spielerIds(6),
                "[{\"stufe\":\"MITTEL\",\"name\":\"%s\"}]".formatted(spielerName(0)))
                .andExpect(status().isBadRequest());

        assertThat(fehlercode(ergebnis)).isEqualTo("EINGABE_UNGUELTIG");
        assertThat(detail(ergebnis)).contains(spielerName(0));
    }

    /**
     * Eine unbekannte Id wird abgelehnt und genannt - <b>nicht still gefiltert</b>.
     *
     * <p>Am Termin ergibt sich die Menge; hier hat der Admin jeden Einzelnen benannt. Wer
     * eine genannte Id kommentarlos herausnimmt, liefert Teams, die niemand angefordert hat -
     * und es faellt erst auf, wenn jemand vor Ort ohne Team dasteht.
     */
    @Test
    void unbekannteIdWirdAbgelehntUndGenannt() throws Exception {
        String ids = "[%d,%d,%d,%d,%d,999999]".formatted(
                spielerId(0), spielerId(1), spielerId(2), spielerId(3), spielerId(4));

        ResultActions ergebnis = manuell(ids, "[]").andExpect(status().isBadRequest());

        assertThat(fehlercode(ergebnis)).isEqualTo("EINGABE_UNGUELTIG");
        assertThat(detail(ergebnis)).contains("999999");
    }

    /**
     * Ein gesperrtes Profil ist derselbe Fall wie ein unbekanntes.
     *
     * <p>Sie zu unterscheiden hiesse, die Existenz eines gesperrten Profils zu bestaetigen -
     * fuer den Admin ohne Nutzen, denn die Antwort ist beide Male dieselbe: Diese Id gehoert
     * nicht in den Lauf.
     */
    @Test
    void gesperrtesProfilWirdAbgelehnt() throws Exception {
        // Die Auswahl entsteht VOR dem Sperren - danach liefert spielerId(5) ein anderes
        // Profil, weil die Abfrage auf aktive filtert, und der Fall pruefte nichts mehr.
        String ids = spielerIds(6);
        Long gesperrt = spielerId(5);
        jdbc.update("UPDATE profil.spieler SET aktiv = false WHERE id = ?", gesperrt);

        ResultActions ergebnis = manuell(ids, "[]").andExpect(status().isBadRequest());

        assertThat(fehlercode(ergebnis)).isEqualTo("EINGABE_UNGUELTIG");
        assertThat(detail(ergebnis)).contains(String.valueOf(gesperrt));
    }

    /**
     * Das Adminprofil nimmt an keiner Teamgenerierung teil.
     *
     * <p>Die Wiederholung des Ausschlusses an einer neuen Grenze: {@code /admin/user/lesen}
     * weist die Rolle aus, aber wer die Id kennt, kommt an der Liste vorbei - und das
     * Adminprofil traegt Skillwerte von 0.
     */
    @Test
    void adminprofilWirdAbgelehnt() throws Exception {
        String ids = "[%d,%d,%d,%d,%d,%d]".formatted(
                spielerId(0), spielerId(1), spielerId(2), spielerId(3), spielerId(4),
                adminSpielerId());

        assertThat(fehlercode(manuell(ids, "[]").andExpect(status().isConflict())))
                .isEqualTo("PROFIL_GESCHUETZT");
    }

    /** Unter der Mindestzahl wird abgelehnt; {@code detail} nennt Ist und Soll. */
    @Test
    void zuWenigTeilnehmerWerdenAbgelehnt() throws Exception {
        ResultActions ergebnis = manuell(spielerIds(4), "[]").andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("ZU_WENIG_TEILNEHMER");
        assertThat(detail(ergebnis)).contains("4").contains("6");
    }

    /**
     * Ueber der Hoechstzahl wird abgelehnt - <b>hier wird nicht abgeschnitten</b>.
     *
     * <p>Am Termin ist {@code max_teilnehmer} die Grenze zur Warteschlange; hier gibt es
     * keine, in die jemand rutschen koennte. Der Fehlercode ist deshalb neu und hat am Termin
     * keine Entsprechung.
     */
    @Test
    void zuVieleTeilnehmerWerdenAbgelehnt() throws Exception {
        maxTeilnehmerSetzen(6);

        assertThat(fehlercode(manuell(spielerIds(8), "[]").andExpect(status().isConflict())))
                .isEqualTo("ZU_VIELE_TEILNEHMER");
    }

    /** Ein fehlender Skillwert wird abgelehnt; die Meldung nennt den Namen. */
    @Test
    void fehlenderSkillwertWirdAbgelehnt() throws Exception {
        jdbc.update("DELETE FROM profil.spieler_skill WHERE spieler_id = ? AND kategorie = 'TORWART'",
                spielerId(2));

        ResultActions ergebnis = manuell(spielerIds(6), "[]").andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("SKILLWERTE_UNVOLLSTAENDIG");
        assertThat(detail(ergebnis)).contains(spielerName(2));
    }

    // --------------------------------------------------------------------- Abwesenheiten

    /**
     * <b>Der Kern der Klasse:</b> Der Lauf hinterlaesst keine Zeile in den Teamtabellen.
     *
     * <p>{@code team_generierung.termin_id} ist {@code NOT NULL} und
     * {@code team_zuteilung.teilnahme_id} haengt am Fremdschluessel auf
     * {@code spieltag.teilnahme} - ein Teilnehmer ohne Teilnahmezeile passt dort nicht hinein.
     * Genau das haelt S5 migrationsfrei.
     */
    @Test
    void derLaufSpeichertNichts() throws Exception {
        manuell(spielerIds(8), "[]").andExpect(status().isOk());

        assertThat(anzahl("spieltag.team_generierung")).isZero();
        assertThat(anzahl("spieltag.team_zuteilung")).isZero();
    }

    /**
     * <b>Der zweite Kernfall:</b> Kein Kontingent - zehn Laeufe hintereinander liefern zehnmal
     * {@code 200}.
     *
     * <p>A15 zaehlt Laeufe je Termin und Teilnehmerstand; beides gibt es hier nicht, und die
     * Kontingentzeile traegt {@code termin_id NOT NULL}. Schutz vor Dauerlaeufen sind der
     * Zugang und {@code MAX_EXHAUSTIV}.
     */
    @Test
    void derLaufKostetKeinKontingent() throws Exception {
        for (int i = 0; i < 10; i++) {
            manuell(spielerIds(8), "[]").andExpect(status().isOk());
        }

        assertThat(anzahl("spieltag.generierung_kontingent")).isZero();
    }

    // --------------------------------------------------------------------- Antwort und Protokoll

    /**
     * Die Antwort traegt genau sechs Felder - und <b>keinen {@code seed}</b>.
     *
     * <p>Er waere ohne die Skillwerte daneben eine Zahl ohne Verwendung, und die stehen hier
     * bewusst nicht. Taucht dort einer auf, ist er stehen geblieben.
     */
    @Test
    void dieAntwortTraegtSechsFelderUndKeinenSeed() throws Exception {
        Map<String, Object> antwort = alsKarte(
                manuell(spielerIds(8), "[]").andExpect(status().isOk()));

        assertThat(antwort).containsOnlyKeys("algorithmType", "auswechselModus",
                "auswechselspieler", "differenzTeamstaerke", "teamA", "teamB");
        assertThat(alleEintraege(antwort)).allSatisfy(eintrag ->
                assertThat(eintrag).containsOnlyKeys("anzeigeName", "gast", "auswechselspieler"));
    }

    /**
     * {@code ZULETZT_ANGEMELDET} faellt zurueck - und die Antwort sagt es.
     *
     * <p>Der Modus hat hier keine Datengrundlage: {@code gemeldetAm} ist {@code null}, weil
     * sich niemand gemeldet hat. <b>Still zurueckzufallen waere das Schlimmste von beidem</b>:
     * Der Admin hat die Einstellung gesetzt und saehe ein Ergebnis, das ihr widerspricht.
     */
    @Test
    void zuletztAngemeldetFaelltZurueckUndSagtEs() throws Exception {
        auswechselModusSetzen("ZULETZT_ANGEMELDET");

        Map<String, Object> antwort = alsKarte(
                manuell(spielerIds(7), "[]").andExpect(status().isOk()));

        assertThat(antwort).containsEntry("auswechselModus", "SCHWAECHSTER_UEBERZAHL");
        assertThat((String) antwort.get("auswechselspieler")).isNotNull();
    }

    /**
     * Zwei gleich starke Gruppen ergeben Kosten von {@code 0} - und nicht {@code null}.
     *
     * <p>Sechs Gaeste derselben Stufe sind bauartbedingt gleich stark; jede Aufteilung ist
     * damit optimal. Der Fall prueft zugleich, dass {@code differenzTeamstaerke} die
     * <b>Kosten der Zielfunktion</b> traegt: Waere es der Tie-Break, stuende hier ebenfalls
     * {@code 0} - deshalb steht daneben die Feldliste aus dem vorigen Fall.
     */
    @Test
    void gleichStarkeGruppenErgebenKostenNull() throws Exception {
        String gaeste = IntStream.range(0, 6)
                .mapToObj(i -> "{\"stufe\":\"MITTEL\"}")
                .collect(Collectors.joining(",", "[", "]"));

        Map<String, Object> antwort = alsKarte(manuell("[]", gaeste).andExpect(status().isOk()));

        assertThat(antwort.get("differenzTeamstaerke")).isNotNull();
        assertThat(((Number) antwort.get("differenzTeamstaerke")).doubleValue()).isZero();
        assertThat(antwort).containsEntry("auswechselspieler", null);
    }

    /**
     * Der Audit-Eintrag ist die einzige Spur des Laufs - und der einzige Ort, an dem der Seed
     * steht.
     *
     * <p>Er traegt deshalb mehr als sonst ueblich: Teilnehmer, Seed, Verfahren und Kosten.
     * <b>Der Preis, den man kennen muss:</b> Nach 90 Tagen faellt er der Loeschfrist zum
     * Opfer.
     */
    @Test
    void derAuditEintragTraegtSeedUndTeilnehmer() throws Exception {
        manuell(spielerIds(8), "[]").andExpect(status().isOk());

        Map<String, Object> eintrag = jdbc.queryForMap("""
                SELECT details->>'seed'                       AS seed,
                       jsonb_array_length(details->'teilnehmer') AS anzahl,
                       details->>'verfahren'                  AS verfahren
                  FROM profil.audit_log
                 WHERE aktion = 'TEAMS_MANUELL_GENERIERT'
                """);

        assertThat(eintrag.get("seed")).as("nur hier steht er").isNotNull();
        assertThat(eintrag.get("anzahl")).isEqualTo(8);
        assertThat(eintrag.get("verfahren")).isEqualTo("EXHAUSTIV");
    }

    // --------------------------------------------------------------------- Zugang

    /** Der Endpunkt liegt unter {@code /admin/} - ein Spieler kommt nicht durch. */
    @Test
    void alsSpielerLiefert403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/teams/generieren")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(
                                Stage.PROFILE_AUTHENTICATED, spielerId(0), Rolle.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(koerper(spielerIds(8), "[]")))
                .andExpect(status().isForbidden());
    }

    /** Ohne Cookie kommt der Aufruf nicht durch die Filterkette. */
    @Test
    void ohneCookieLiefert401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/teams/generieren")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    private ResultActions manuell(String spielerIds, String gaeste) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/teams/generieren")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.70")
                .contentType(MediaType.APPLICATION_JSON)
                .content(koerper(spielerIds, gaeste)));
    }

    private static String koerper(String spielerIds, String gaeste) {
        return "{\"spielerIds\":%s,\"gaeste\":%s}".formatted(spielerIds, gaeste);
    }

    /** Die Ids der ersten {@code anzahl} Spielerprofile als JSON-Array. */
    private String spielerIds(int anzahl) {
        return IntStream.range(0, anzahl)
                .mapToObj(i -> String.valueOf(spielerId(i)))
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** Muss vor dem ersten HTTP-Aufruf laufen - siehe Klassen-JavaDoc. */
    private void maxTeilnehmerSetzen(int wert) {
        jdbc.update("""
                UPDATE configs.app_config SET max_teilnehmer = ?, version = version + 1 WHERE id = 1
                """, wert);
    }

    /** Muss vor dem ersten HTTP-Aufruf laufen - siehe Klassen-JavaDoc. */
    private void auswechselModusSetzen(String modus) {
        jdbc.update("""
                UPDATE configs.app_config SET auswechsel_modus = ?, version = version + 1 WHERE id = 1
                """, modus);
    }

    private Map<String, Object> alsKarte(ResultActions ergebnis) throws Exception {
        return objectMapper.readValue(
                ergebnis.andReturn().getResponse().getContentAsString(), new TypeReference<>() {
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eintraege(Map<String, Object> antwort, String team) {
        return (List<Map<String, Object>>) antwort.get(team);
    }

    private static List<Map<String, Object>> alleEintraege(Map<String, Object> antwort) {
        return Stream.concat(eintraege(antwort, "teamA").stream(),
                        eintraege(antwort, "teamB").stream())
                .toList();
    }

    private static List<String> namen(Map<String, Object> antwort, String team) {
        return eintraege(antwort, team).stream()
                .map(eintrag -> (String) eintrag.get("anzeigeName"))
                .toList();
    }

    private static List<String> alleNamen(Map<String, Object> antwort) {
        return alleEintraege(antwort).stream()
                .map(eintrag -> (String) eintrag.get("anzeigeName"))
                .toList();
    }

    private String fehlercode(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("code");
    }

    private String detail(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("detail");
    }

    private int anzahl(String tabelle) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabelle, Integer.class);
    }

    /** Profil-Id eines Spielerprofils aus den Demodaten; {@code position} ab 0. */
    private Long spielerId(int position) {
        return jdbc.queryForObject("""
                SELECT id FROM profil.spieler
                 WHERE rolle = 'USER' AND aktiv
                 ORDER BY name
                 LIMIT 1 OFFSET ?
                """, Long.class, position);
    }

    private String spielerName(int position) {
        return jdbc.queryForObject("""
                SELECT name FROM profil.spieler
                 WHERE rolle = 'USER' AND aktiv
                 ORDER BY name
                 LIMIT 1 OFFSET ?
                """, String.class, position);
    }

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }
}
