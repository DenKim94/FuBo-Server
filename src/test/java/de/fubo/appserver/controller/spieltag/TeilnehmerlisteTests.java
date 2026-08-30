package de.fubo.appserver.controller.spieltag;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Teilnehmerliste und die Warteschlange aus {@code S4_UMSETZUNG.md}, Abschnitt 7.
 *
 * <h2>Warum die Testdaten per SQL entstehen und nicht ueber die Endpunkte</h2>
 * Zwei Gruende, und der zweite ist der wichtigere.
 *
 * <p><b>Erstens die Meldezeit.</b> {@code now()} ist innerhalb einer Transaktion konstant -
 * ueber den Endpunkt angelegte Zusagen truegen alle denselben Zeitstempel, und die
 * Reihenfolge fiele auf die {@code id} zurueck. Die Sortierung nach {@code gemeldet_am} waere
 * damit gar nicht geprueft. Per SQL laesst sich je Zeile ein eigener Zeitpunkt setzen.
 *
 * <p><b>Zweitens der Zwischenspeicher von Hibernate.</b> Diese Klasse verstellt
 * {@code min_teilnehmer} und {@code max_teilnehmer}. Jeder HTTP-Aufruf laedt ueber den
 * Sitzungsfilter die Konfigurationszeile in den Persistence-Context; eine spaetere Aenderung
 * per SQL waere fuer den naechsten Lesezugriff derselben Transaktion unsichtbar.
 * <b>Deshalb gilt hier: erst die Konfiguration setzen, dann Daten anlegen, dann genau
 * einmal lesen.</b> Wer diese Reihenfolge umdreht, bekommt einen gruenen Test, der nichts
 * prueft.
 *
 * <h2>Eigener Zeitstreifen</h2>
 * {@code uq_termin_zeit} ist global. Diese Klasse arbeitet um {@link #BASIS_TAGE} herum und
 * zur {@link #UHRZEIT}; {@code TerminControllerTests} und
 * {@code TerminVerwaltungControllerTests} haben beides anders.
 *
 * <h2>{@code @Transactional} ist hier Pflicht</h2>
 * Die Klasse aendert eine anwendungsweit gueltige Zeile. Ohne die Test-Transaktion liefe sie
 * anderen Klassen in die Quere - {@code GastControllerTests} verlaesst sich auf
 * {@code anz_guests = 4}, {@code SessionServiceTests} auf das Zwei-Timer-Modell.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TeilnehmerlisteTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Abstand zum heutigen Tag; haelt diese Klasse von den uebrigen fern. */
    private static final int BASIS_TAGE = 200;

    /** Uhrzeit aller Termine dieser Klasse - zweite Haelfte des Kollisionsschutzes. */
    private static final LocalTime UHRZEIT = LocalTime.of(17, 30);

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

    /**
     * Die Reihenfolge folgt der Meldezeit, nicht der Reihenfolge des Einfuegens.
     *
     * <p>Die drei Zeilen entstehen absichtlich in umgekehrter zeitlicher Folge: Waere die
     * Sortierung versehentlich nach {@code id}, faellt es genau hier auf.
     */
    @Test
    void dieReihenfolgeFolgtDerMeldezeit() throws Exception {
        Long terminId = terminAnlegen(0);
        zusageAnlegen(terminId, spielerId(0), 10);   // vor 10 Minuten - Platz 3
        zusageAnlegen(terminId, spielerId(1), 30);   // vor 30 Minuten - Platz 1
        zusageAnlegen(terminId, spielerId(2), 20);   // vor 20 Minuten - Platz 2

        List<Map<String, Object>> liste = teilnehmer(terminId);

        assertThat(namen(liste)).containsExactly(name(1), name(2), name(0));
        assertThat(liste.stream().map(e -> zahl(e, "position")).toList()).containsExactly(1, 2, 3);
    }

    /**
     * Alles jenseits von {@code maxTeilnehmer} wartet.
     *
     * <p>Die Grenze wird auf 1 gesenkt - so ist der Fall mit zwei Zusagen pruefbar, statt
     * dreiundzwanzig Profile anzulegen.
     */
    @Test
    void abPositionUeberMaxTeilnehmerWirdGewartet() throws Exception {
        grenzenSetzen(1, 1);
        Long terminId = terminAnlegen(1);
        zusageAnlegen(terminId, spielerId(0), 20);   // vor 20 Minuten - der fruehere
        zusageAnlegen(terminId, spielerId(1), 10);   // vor 10 Minuten - der spaetere

        List<Map<String, Object>> liste = teilnehmer(terminId);

        assertThat(liste.get(0).get("wartet")).isEqualTo(false);
        assertThat(liste.get(1).get("wartet")).isEqualTo(true);
        assertThat(liste.get(1).get("anzeigeName"))
                .as("Wer spaeter gemeldet hat, wartet")
                .isEqualTo(name(1));
    }

    /**
     * Sagt jemand aus der Mitte ab, ruecken alle dahinter auf.
     *
     * <p><b>Genau der Grund gegen eine Positionsspalte.</b> Sie muesste bei jeder Absage neu
     * durchnummeriert werden - mit Schreiblast und einem Wettlauf bei parallelen Meldungen.
     * Hier geschieht nichts, was geschrieben werden muesste: Die Reihenfolge ergibt sich bei
     * der naechsten Abfrage neu.
     *
     * <p>Die Hoechstzahl steht auf 2: Der dritte wartet, und nach der Absage aus der Mitte
     * wartet niemand mehr - damit ist beides in einem Fall zu sehen.
     */
    @Test
    void eineAbsageAusDerMitteLaesstAlleAufruecken() throws Exception {
        grenzenSetzen(2, 2);
        Long terminId = terminAnlegen(2);
        zusageAnlegen(terminId, spielerId(0), 30);   // vor 30 Minuten - Platz 1
        zusageAnlegen(terminId, spielerId(1), 20);   // vor 20 Minuten - Platz 2, die Mitte
        zusageAnlegen(terminId, spielerId(2), 10);   // vor 10 Minuten - Platz 3, wartet

        assertThat(namen(teilnehmer(terminId))).containsExactly(name(0), name(1), name(2));
        assertThat(teilnehmer(terminId).get(2).get("wartet")).isEqualTo(true);

        jdbc.update("UPDATE spieltag.teilnahme SET zusage = false WHERE termin_id = ? AND spieler_id = ?",
                terminId, spielerId(1));

        List<Map<String, Object>> danach = teilnehmer(terminId);
        assertThat(namen(danach)).containsExactly(name(0), name(2));
        assertThat(danach.get(1).get("wartet"))
                .as("Wer auf Platz 3 stand, steht jetzt auf Platz 2 und wartet nicht mehr")
                .isEqualTo(false);
    }

    /**
     * {@code mindestzahlErreicht} beantwortet A10 - und zaehlt auch die Wartenden mit.
     *
     * <p>Wer wartet, hat zugesagt und rueckt nach; er zaehlt also fuer die Frage, ob genug
     * Leute zusammenkommen.
     *
     * <p><b>Warum beide Grenzen auf 2 stehen:</b> {@code ck_app_config_teilnehmer} verlangt
     * {@code max >= min} - eine Hoechstzahl unter der Mindestzahl liesse sich gar nicht
     * setzen. Mit drei Zusagen ist die Mindestzahl erreicht <i>und</i> der dritte wartet;
     * beide Aussagen stehen damit in einem Fall.
     */
    @Test
    void mindestzahlErreichtZaehltAuchDieWartenden() throws Exception {
        grenzenSetzen(2, 2);
        Long terminId = terminAnlegen(3);
        zusageAnlegen(terminId, spielerId(0), 30);   // vor 30 Minuten - Platz 1

        assertThat(liste(terminId).get("mindestzahlErreicht")).isEqualTo(false);

        zusageAnlegen(terminId, spielerId(1), 20);   // vor 20 Minuten - Platz 2
        zusageAnlegen(terminId, spielerId(2), 10);   // vor 10 Minuten - Platz 3, wartet

        Map<String, Object> danach = liste(terminId);
        assertThat(danach.get("mindestzahlErreicht")).isEqualTo(true);
        assertThat(eintraege(danach).get(2).get("wartet"))
                .as("Der dritte wartet - und zaehlt trotzdem mit")
                .isEqualTo(true);
    }

    /** Absagen stehen nicht in der Liste; sie ist eine Liste der Zusagen. */
    @Test
    void absagenErscheinenNichtInDerListe() throws Exception {
        Long terminId = terminAnlegen(4);
        zusageAnlegen(terminId, spielerId(0), 20);
        absageAnlegen(terminId, spielerId(1));

        assertThat(namen(teilnehmer(terminId))).containsExactly(name(0));
    }

    /**
     * Gaeste und Spieler stehen in <b>einer</b> Liste, unterschieden durch ein Kennzeichen
     * (Weggabelung D).
     *
     * <p>Die Reihenfolge ist die eine Information, die beide Gruppen verbindet. Zwei
     * getrennte Listen zwaengen den Client, sie fuer die Anzeige wieder zusammenzufuegen.
     *
     * <p><b>Der Gast meldet sich absichtlich zuerst.</b> Stuende er hinten, waere der Fall
     * blind dafuer, ob die Liste wirklich nach Meldezeit sortiert oder die Gaeste nur hinten
     * anhaengt - beide Umsetzungen lieferten dieselbe Reihenfolge. So nicht.
     */
    @Test
    void gastUndSpielerStehenInEinerListe() throws Exception {
        Long terminId = terminAnlegen(5);
        gastZusageAnlegen(terminId, "Testgast Hanna", 20);   // vor 20 Minuten - der fruehere
        zusageAnlegen(terminId, spielerId(0), 10);           // vor 10 Minuten

        List<Map<String, Object>> liste = teilnehmer(terminId);

        assertThat(namen(liste)).containsExactly("Testgast Hanna", name(0));
        assertThat(liste.get(0).get("gast")).isEqualTo(true);
        assertThat(liste.get(1).get("gast")).isEqualTo(false);
    }

    /**
     * Die Liste traegt keine Bewertungen - weder Skillwerte noch die Stufe eines Gastes.
     *
     * <p>Sie erreicht jede Rolle, auch {@code GAST}; A12 verlangt, dass Bewertungen den Server
     * dorthin nicht verlassen. Geprueft wird die vollstaendige Feldliste und nicht nur das
     * Fehlen einzelner Namen: Der Pruefpunkt ist "was kommt heraus", nicht "kommt ein
     * bestimmtes Wort vor".
     */
    @Test
    void dieListeTraegtKeineBewertungen() throws Exception {
        Long terminId = terminAnlegen(6);
        gastZusageAnlegen(terminId, "Testgast Ines", 10);

        assertThat(teilnehmer(terminId).getFirst().keySet())
                .containsExactlyInAnyOrder("position", "anzeigeName", "gast", "wartet");
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /**
     * Setzt Mindest- und Hoechstzahl.
     *
     * <p><b>Vor dem ersten HTTP-Aufruf des Tests aufzurufen</b> - danach steht die
     * Konfigurationszeile im Persistence-Context und die Aenderung bliebe unsichtbar.
     */
    private void grenzenSetzen(int min, int max) {
        jdbc.update("UPDATE configs.app_config SET min_teilnehmer = ?, max_teilnehmer = ? WHERE id = 1",
                min, max);
    }

    private Long terminAnlegen(int versatz) {
        return jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit) VALUES (?, ?) RETURNING id
                """, Long.class, LocalDate.now().plusDays(BASIS_TAGE + versatz), UHRZEIT);
    }

    /**
     * Zusage mit einer eigenen Meldezeit.
     *
     * <p><b>{@code vorMinuten} zaehlt rueckwaerts.</b> {@code 30} hat sich vor dreissig
     * Minuten gemeldet und steht damit <i>vor</i> {@code 10}. Die groessere Zahl steht weiter
     * oben in der Liste - {@code ORDER BY gemeldet_am} sortiert aufsteigend. Diese Richtung
     * war am 30.08.2026 in drei Faellen verdreht; die Zahlen sind hier deshalb ueberall am
     * Aufruf kommentiert.
     */
    private void zusageAnlegen(Long terminId, Long spielerId, int vorMinuten) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage, gemeldet_am)
                VALUES (?, ?, true, now() - make_interval(mins => ?))
                """, terminId, spielerId, vorMinuten);
    }

    private void absageAnlegen(Long terminId, Long spielerId) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage) VALUES (?, ?, false)
                """, terminId, spielerId);
    }

    /** Wie {@link #zusageAnlegen} - {@code vorMinuten} zaehlt auch hier rueckwaerts. */
    private void gastZusageAnlegen(Long terminId, String gastName, int vorMinuten) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, gast_name, gast_stufe, zusage, gemeldet_am)
                VALUES (?, ?, 'MITTEL', true, now() - make_interval(mins => ?))
                """, terminId, gastName, vorMinuten);
    }

    /** Die Teilnehmerliste aus der Einzelansicht - der einzige Weg, sie zu lesen. */
    private Map<String, Object> liste(Long terminId) throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/termine/%d/lesen".formatted(terminId))
                        .cookie(new Cookie(COOKIE, spielerSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> details = objectMapper.readValue(antwort, new TypeReference<>() {
        });
        return teilnehmerliste(details);
    }

    private List<Map<String, Object>> teilnehmer(Long terminId) throws Exception {
        return eintraege(liste(terminId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> teilnehmerliste(Map<String, Object> details) {
        return (Map<String, Object>) details.get("teilnehmerliste");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eintraege(Map<String, Object> liste) {
        return (List<Map<String, Object>>) liste.get("teilnehmer");
    }

    private static List<String> namen(List<Map<String, Object>> liste) {
        return liste.stream().map(e -> (String) e.get("anzeigeName")).toList();
    }

    /** Profil-Id eines Spielerprofils aus den Demodaten; {@code position} ab 0. */
    private Long spielerId(int position) {
        return jdbc.queryForObject("""
                SELECT id FROM profil.spieler
                 WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1 OFFSET ?
                """, Long.class, position);
    }

    /** Der Anzeigename desselben Profils - so, wie er in der Liste erscheint. */
    private String name(int position) {
        return jdbc.queryForObject("""
                SELECT name FROM profil.spieler
                 WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1 OFFSET ?
                """, String.class, position);
    }

    private String spielerSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId(0), Rolle.USER);
    }

    private static int zahl(Map<String, Object> karte, String feld) {
        return ((Number) karte.get(feld)).intValue();
    }
}
