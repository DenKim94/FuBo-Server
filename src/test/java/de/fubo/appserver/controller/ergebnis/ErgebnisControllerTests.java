package de.fubo.appserver.controller.ergebnis;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Ergebniserfassung, die Admin-Korrektur, die Bilanz und den Lesepfad
 * (A9, A21; S6 Abschnitte 2 bis 5).
 *
 * <h2>Der Aufbau je Fall ist der eigentliche Aufwand dieser Klasse</h2>
 * Erfasst wird nur fuer einen Termin, der {@code ABGESCHLOSSEN} ist <b>und</b> eine
 * Teameinteilung hat. Den Zustand gibt es nur in dieser Reihenfolge:
 * <ol>
 *   <li>Termin in der Vergangenheit anlegen (per SQL).</li>
 *   <li>Zusagen per SQL, mit gesetzten Meldezeiten.</li>
 *   <li>Ueber {@code POST /teams/generieren} einteilen - der Endpunkt prueft {@code GEPLANT}
 *       und {@code teams_fixiert}, <b>nicht das Datum</b>; ein vergangener Termin geht also
 *       durch.</li>
 *   <li>Per SQL auf {@code ABGESCHLOSSEN} setzen, <b>mit {@code version = version + 1}</b>.</li>
 * </ol>
 * <b>Schritt 4 nach Schritt 3</b>, nicht davor: Fuer einen abgeschlossenen Termin laesst sich
 * nicht mehr generieren.
 *
 * <h2>Eigener Zeitstreifen: 600 Tage rueckwaerts, 21:30</h2>
 * {@code uq_termin_zeit UNIQUE (datum, uhrzeit)} ist <b>global</b>, und beide Achsen zaehlen.
 * Vergeben sind 40/18:15 ({@code TerminControllerTests}), 120/19:45
 * ({@code TerminVerwaltungControllerTests}), 200/17:30 ({@code TeilnehmerlisteTests}),
 * 300/16:05 ({@code SpielerControllerTests}) und 500/20:15 ({@code TeamGeneratorTests}).
 * <b>Die Uhrzeit allein traegt hier schon</b> - kein anderer Fall legt einen Termin um 21:30
 * an; deshalb darf diese Klasse mehrere Tage belegen, was sie fuer den Fall mit zwei Terminen
 * auch braucht.
 *
 * <h2>Warum kein Fall die Profilverwaltung anfasst</h2>
 * Die Bilanzrechnung erhoeht {@code profil.spieler.version} per SQL. In einer
 * {@code @Transactional}-Testklasse teilen sich alle Aufrufe einen Persistence-Context: Ein
 * Fall, der erst {@code /admin/user/bearbeiten} fuer einen Spieler aufriefe und danach ein
 * Ergebnis erfasste, das denselben Spieler betrifft, haette dessen Entity im Speicher - der
 * naechste Schreibvorgang scheiterte an einem Sperrkonflikt, <b>den es im Betrieb nicht
 * gaebe</b> (dort ist jeder Request eine eigene Transaktion). Die beiden Vorgaenge gehoeren
 * deshalb in getrennte Testklassen, und der Bilanzfall der Adminuebersicht steht in
 * {@code SpielerControllerTests}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ErgebnisControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Abstand zum heutigen Tag, rueckwaerts; erste Haelfte des Kollisionsschutzes. */
    private static final int BASIS_TAGE = 600;

    /** Uhrzeit aller Termine dieser Klasse; zweite Haelfte des Kollisionsschutzes. */
    private static final LocalTime UHRZEIT = LocalTime.of(21, 30);

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

    /** Zaehlt die Termine eines Falls; jeder bekommt einen eigenen Tag. */
    private int terminZaehler;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Der Zwischenspeicher ist ein Singleton und ueberlebt die Test-Transaktion; ohne das
        // Verwerfen saehe der naechste Fall Profildaten, die es in der Datenbank nicht gibt.
        profilStammdatenCache.verwerfen();
        terminZaehler = 0;
    }

    // --------------------------------------------------------------------- Erfassen

    /** Der Regelfall: 201, und die Zeile steht mit dem gesendeten Ausgang in der Tabelle. */
    @Test
    void erfasstDasErgebnisUndSchreibtDieZeile() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);

        Map<String, Object> antwort = alsKarte(
                erfassen(spielerSitzung(0), terminId, "A", true).andExpect(status().isCreated()));

        assertThat(antwort.get("sieger")).isEqualTo("A");
        assertThat(antwort.get("deutlich")).isEqualTo(true);
        assertThat(antwort.get("erfasstVon")).isEqualTo(spielerName(0));
        assertThat(antwort.get("korrigiertAm")).as("unkorrigiert ist null, kein Datum").isNull();

        assertThat(jdbc.queryForObject(
                "SELECT sieger FROM spieltag.ergebnis WHERE termin_id = ?", String.class, terminId))
                .isEqualTo("A");
    }

    /**
     * <b>Der erste Eintrag gilt.</b> Der zweite Nutzer bekommt {@code 409}, und der erste
     * Eintrag bleibt unveraendert.
     *
     * <p>Der zweite Aufruf sendet bewusst einen <b>anderen</b> Sieger: Nur so belegt die
     * Gegenprobe etwas. Mit demselben Koerper liesse sich ein durchgegangener Eintrag von
     * einem abgewiesenen nicht unterscheiden.
     */
    @Test
    void zweiterEintragScheitertUndLaesstDenErstenUnveraendert() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        erfassen(spielerSitzung(0), terminId, "A", true).andExpect(status().isCreated());

        assertThat(fehlercode(erfassen(spielerSitzung(1), terminId, "B", false)
                .andExpect(status().isConflict())))
                .isEqualTo("ERGEBNIS_VORHANDEN");

        Map<String, Object> zeile = ergebniszeile(terminId);
        assertThat(String.valueOf(zeile.get("sieger")).trim()).isEqualTo("A");
        assertThat(zeile.get("deutlich")).isEqualTo(true);
        assertThat(zeile.get("erfasst_von_bezeichnung")).isEqualTo(spielerName(0));
        assertThat(zeile.get("korrigiert_am"))
                .as("Ein zweiter Eintrag ist keine Korrektur - er hinterlaesst gar nichts")
                .isNull();
    }

    /**
     * Ein geplanter Termin ist noch nicht so weit.
     *
     * <p><b>Nicht {@code TERMIN_GESCHLOSSEN}:</b> Dort ist die Polaritaet umgekehrt. Der
     * Aufrufer koennte aus dem falschen Code nicht ableiten, ob Warten hilft.
     */
    @Test
    void geplanterTerminWirdAbgelehnt() throws Exception {
        Long terminId = terminMitEinteilung(8);

        ResultActions antwort = erfassen(spielerSitzung(0), terminId, "A", false)
                .andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("TERMIN_NICHT_ABGESCHLOSSEN");
        assertThat(detail(antwort)).as("detail nennt den Status").contains("GEPLANT");
    }

    /** Ein abgesagter Termin hat nicht stattgefunden - derselbe Code, aber Warten hilft nicht. */
    @Test
    void abgesagterTerminWirdAbgelehnt() throws Exception {
        Long terminId = terminMitEinteilung(8);
        statusSetzen(terminId, "ABGESAGT");

        ResultActions antwort = erfassen(spielerSitzung(0), terminId, "A", false)
                .andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("TERMIN_NICHT_ABGESCHLOSSEN");
        assertThat(detail(antwort)).contains("ABGESAGT");
    }

    /**
     * Ohne Einteilung kein Ergebnis - es haette keine beteiligten Spieler.
     *
     * <p><b>Der Fall ist selten und deshalb gefaehrlich:</b> Er tritt nur auf, wenn ein Termin
     * gespielt wurde, ohne dass jemand generiert hat - und genau dann faellt eine stumme
     * Nullbilanz nicht auf.
     */
    @Test
    void abgeschlossenerTerminOhneEinteilungWirdAbgelehnt() throws Exception {
        Long terminId = terminMitZusagen(8);
        abschliessen(terminId);

        assertThat(fehlercode(erfassen(spielerSitzung(0), terminId, "A", false)
                .andExpect(status().isConflict())))
                .isEqualTo("KEINE_EINTEILUNG");
    }

    /** Die Existenz des Termins wird zuerst geprueft. */
    @Test
    void unbekannteTerminIdLiefertNichtGefunden() throws Exception {
        assertThat(fehlercode(erfassen(spielerSitzung(0), 999_999L, "A", false)
                .andExpect(status().isNotFound())))
                .isEqualTo("INHALT_NICHT_GEFUNDEN");
    }

    /**
     * Ein deutliches Unentschieden gibt es nicht.
     *
     * <p>Die Pruefung sitzt am DTO und liefert deshalb denselben {@code felder}-Block wie jede
     * andere Eingabepruefung - <b>unter dem Schluessel der Pruefmethode</b>, weil beanstandet
     * wird, was beide Felder zusammen aussagen.
     */
    @Test
    void deutlichesUnentschiedenWirdAbgelehnt() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);

        ResultActions antwort = erfassen(spielerSitzung(0), terminId, "U", true)
                .andExpect(status().isBadRequest());

        assertThat(fehlercode(antwort)).isEqualTo("EINGABE_UNGUELTIG");
        assertThat(felder(antwort)).containsKey("deutlichNurBeiSieg");
    }

    /** Ein Gast darf erfassen (A21, 2.1) - der Endpunkt liegt nicht unter {@code /admin/}. */
    @Test
    void gastDarfErfassen() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);

        Map<String, Object> antwort = alsKarte(
                erfassen(gastSitzung("Testgast 1"), terminId, "B", false)
                        .andExpect(status().isCreated()));

        assertThat(antwort.get("erfasstVon"))
                .as("Der Gastname wird kopiert, nicht verwiesen")
                .isEqualTo("Testgast 1");
    }

    /** Ohne Cookie greift die Filterchain, nicht der Controller. */
    @Test
    void ohneCookieAbgelehnt() throws Exception {
        mockMvc.perform(post("/api/v1/ergebnis/erfassen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminId\":1,\"sieger\":\"A\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------------- Bilanz

    /** Ein Sieg fuer A: jeder in A bekommt einen Sieg, jeder in B eine Niederlage. */
    @Test
    void siegFuerAZaehltSiegeUndNiederlagen() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        Map<Long, String> teams = teamzuordnung(terminId);

        erfassen(spielerSitzung(0), terminId, "A", false).andExpect(status().isCreated());

        teams.forEach((spielerId, team) -> {
            if ("A".equals(team)) {
                bilanzErwartet(spielerId, 1, 0, 0);
            } else {
                bilanzErwartet(spielerId, 0, 1, 0);
            }
        });
    }

    /** Ein Unentschieden zaehlt bei allen Eingeteilten in denselben Zaehler. */
    @Test
    void unentschiedenZaehltBeiAllenEingeteilten() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        Map<Long, String> teams = teamzuordnung(terminId);

        erfassen(spielerSitzung(0), terminId, "U", false).andExpect(status().isCreated());

        teams.keySet().forEach(spielerId -> bilanzErwartet(spielerId, 0, 0, 1));
    }

    /**
     * {@code deutlich} beschreibt die Hoehe, nicht den Ausgang - die Zaehler sehen es nie.
     *
     * <p>Der haeufigste Irrtum beim Lesen von A21, und der einzige Fall, der ihn abdeckt.
     */
    @Test
    void deutlichAendertAnDerBilanzNichts() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        Map<Long, String> teams = teamzuordnung(terminId);

        erfassen(spielerSitzung(0), terminId, "A", true).andExpect(status().isCreated());

        teams.forEach((spielerId, team) ->
                bilanzErwartet(spielerId, "A".equals(team) ? 1 : 0, "A".equals(team) ? 0 : 1, 0));
    }

    /**
     * Ein Gast in der Einteilung bekommt keine Bilanzzeile - und das ist <b>kein Fehler</b>.
     *
     * <p>Er hat keine Zeile in {@code profil.spieler}; ein Zaehler an {@code gast_slot}
     * summierte die Ergebnisse verschiedener Personen, weil der Platz wiederverwendet wird.
     */
    @Test
    void gastInDerEinteilungBekommtKeineBilanzUndKeinenFehler() throws Exception {
        Long terminId = terminMitZusagen(7);
        gastZusageAnlegen(terminId, "Testgast 2", "MITTEL", 1);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());
        abschliessen(terminId);

        erfassen(spielerSitzung(0), terminId, "A", false).andExpect(status().isCreated());

        assertThat(anzahlProfileMitBilanz())
                .as("nur die sieben Profile, nicht der Gast")
                .isEqualTo(7);
    }

    /**
     * Bei ungerader Zahl zaehlt der Auswechselspieler mit - er hat gespielt.
     *
     * <p>Er steht in {@code team_zuteilung} wie alle anderen; ihn herauszunehmen waere
     * zusaetzlicher Aufwand fuer ein falsches Ergebnis. Zumal er nirgends gespeichert ist und
     * beim Lesen jedes Mal neu bestimmt wird.
     */
    @Test
    void auswechselspielerZaehltMit() throws Exception {
        Long terminId = terminBereitFuerErgebnis(7);
        Map<Long, String> teams = teamzuordnung(terminId);

        erfassen(spielerSitzung(0), terminId, "A", false).andExpect(status().isCreated());

        assertThat(teams).hasSize(7);
        teams.keySet().forEach(spielerId ->
                assertThat(gesamt(spielerId))
                        .as("jeder Eingeteilte hat genau ein gewertetes Spiel")
                        .isEqualTo(1));
    }

    // --------------------------------------------------------------------- Korrigieren

    /** Die Korrektur dreht den Ausgang - sie addiert nicht. */
    @Test
    void korrekturDrehtDenAusgangStattZuAddieren() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        Map<Long, String> teams = teamzuordnung(terminId);
        long version = version(erfassen(spielerSitzung(0), terminId, "A", true)
                .andExpect(status().isCreated()));

        korrigieren(adminSitzung(), terminId, "B", false, version)
                .andExpect(status().isNoContent());

        teams.forEach((spielerId, team) -> {
            if ("B".equals(team)) {
                bilanzErwartet(spielerId, 1, 0, 0);
            } else {
                bilanzErwartet(spielerId, 0, 1, 0);
            }
        });
    }

    /** Eine veraltete {@code version} wird abgewiesen, bevor etwas geschrieben wird. */
    @Test
    void korrekturMitFalscherVersionScheitert() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        long version = version(erfassen(spielerSitzung(0), terminId, "A", true)
                .andExpect(status().isCreated()));

        assertThat(fehlercode(korrigieren(adminSitzung(), terminId, "B", false, version + 1)
                .andExpect(status().isConflict())))
                .isEqualTo("DATEN_VERALTET");

        assertThat(String.valueOf(ergebniszeile(terminId).get("sieger")).trim())
                .as("nichts geschrieben")
                .isEqualTo("A");
    }

    /** {@code korrigiert_am} ist vorher {@code null} und danach gesetzt. */
    @Test
    void korrekturSetztKorrigiertAm() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        long version = version(erfassen(spielerSitzung(0), terminId, "A", true)
                .andExpect(status().isCreated()));

        assertThat(ergebniszeile(terminId).get("korrigiert_am")).isNull();

        korrigieren(adminSitzung(), terminId, "A", false, version)
                .andExpect(status().isNoContent());

        assertThat(ergebniszeile(terminId).get("korrigiert_am"))
                .as("auch eine Korrektur ohne Aenderung am Sieger ist eine Handlung")
                .isNotNull();
    }

    /** Korrigieren ist eine Adminbefugnis - der Pfad liegt unter {@code /admin/}. */
    @Test
    void korrekturAlsUserIstVerboten() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        long version = version(erfassen(spielerSitzung(0), terminId, "A", true)
                .andExpect(status().isCreated()));

        korrigieren(spielerSitzung(1), terminId, "B", false, version)
                .andExpect(status().isForbidden());
    }

    /**
     * <b>Der wertvollste Fall der Klasse.</b> Zwei Termine mit Ergebnis, dann eine Korrektur am
     * ersten.
     *
     * <p>Er ist der einzige, der "neu berechnen" von "fortschreiben" unterscheidet: Wer die
     * Zaehler mit {@code +1}/{@code -1} pflegt, besteht alle anderen Faelle. Und er ist
     * zugleich der einzige, der bemerkt, wenn die Neuberechnung nur ueber den einen Termin
     * zaehlt und die uebrige Historie loescht.
     */
    @Test
    void zweiTermineUndEineKorrekturAmErstenZaehlenBeide() throws Exception {
        Long ersterTermin = terminBereitFuerErgebnis(8);
        Map<Long, String> teamsErster = teamzuordnung(ersterTermin);
        long version = version(erfassen(spielerSitzung(0), ersterTermin, "A", false)
                .andExpect(status().isCreated()));

        Long zweiterTermin = terminBereitFuerErgebnis(8);
        Map<Long, String> teamsZweiter = teamzuordnung(zweiterTermin);
        erfassen(spielerSitzung(0), zweiterTermin, "A", false).andExpect(status().isCreated());

        teamsErster.keySet().forEach(spielerId ->
                assertThat(gesamt(spielerId)).as("beide Termine zaehlen").isEqualTo(2));

        korrigieren(adminSitzung(), ersterTermin, "B", false, version)
                .andExpect(status().isNoContent());

        teamsErster.forEach((spielerId, teamErster) -> {
            String teamZweiter = teamsZweiter.get(spielerId);
            int siege = ("B".equals(teamErster) ? 1 : 0) + ("A".equals(teamZweiter) ? 1 : 0);
            bilanzErwartet(spielerId, siege, 2 - siege, 0);
        });
    }

    // --------------------------------------------------------------------- Audit

    /**
     * Beide Vorgaenge stehen im Protokoll, und die Korrektur traegt alten <b>und</b> neuen Wert.
     *
     * <p>Ohne den alten Wert ist "wer hat den Sieger gedreht" nicht zu beantworten - und die
     * Zeile selbst fuehrt keine Historie.
     */
    @Test
    void auditTraegtBeideVorgaengeMitAltemUndNeuemWert() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        long version = version(erfassen(spielerSitzung(0), terminId, "A", true)
                .andExpect(status().isCreated()));
        korrigieren(adminSitzung(), terminId, "B", false, version)
                .andExpect(status().isNoContent());

        assertThat(auditDetail(terminId, "ERGEBNIS_ERFASST", "sieger")).isEqualTo("A");
        assertThat(auditDetail(terminId, "ERGEBNIS_ERFASST", "betroffeneSpieler"))
                .as("die Zahl der bewegten Bilanzen")
                .isEqualTo("8");

        assertThat(auditDetail(terminId, "ERGEBNIS_KORRIGIERT", "siegerAlt")).isEqualTo("A");
        assertThat(auditDetail(terminId, "ERGEBNIS_KORRIGIERT", "siegerNeu")).isEqualTo("B");
        assertThat(auditDetail(terminId, "ERGEBNIS_KORRIGIERT", "deutlichAlt")).isEqualTo("true");
        assertThat(auditDetail(terminId, "ERGEBNIS_KORRIGIERT", "deutlichNeu")).isEqualTo("false");
    }

    // --------------------------------------------------------------------- Lesepfad

    /** Ohne Ergebnis traegt die Einzelansicht {@code null} - der Normalzustand, kein Fehler. */
    @Test
    void einzelansichtOhneErgebnisLiefertNull() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);

        Map<String, Object> einzelansicht = einzeln(spielerSitzung(0), terminId);

        assertThat(einzelansicht).containsKey("ergebnis");
        assertThat(einzelansicht.get("ergebnis")).isNull();
    }

    /**
     * Mit Ergebnis traegt die Einzelansicht <b>genau</b> die sechs vertraglichen Felder.
     *
     * <p>Geprueft wird die vollstaendige Liste und nicht das Fehlen einzelner Namen: Ein
     * spaeter ergaenztes Feld faellt sonst niemandem auf - und die Antwort erreicht jede
     * Rolle, auch {@code GAST} (A12).
     */
    @Test
    void einzelansichtMitErgebnisTraegtGenauDieVertraglichenFelder() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        erfassen(spielerSitzung(0), terminId, "A", true).andExpect(status().isCreated());

        Map<String, Object> ergebnis = ergebnisAus(einzeln(gastSitzung("Testgast 3"), terminId));

        assertThat(ergebnis.keySet()).containsExactlyInAnyOrder(
                "sieger", "deutlich", "erfasstVon", "erfasstAm", "korrigiertAm", "version");
        assertThat(ergebnis.get("sieger")).isEqualTo("A");
        assertThat(ergebnis.get("version")).isNotNull();
    }

    // --------------------------------------------------------------------- Eigene Bilanz

    /** Der Spieler liest seine eigene Bilanz - ohne Id, allein ueber die Sitzung. */
    @Test
    void eigeneBilanzLiefertDieZaehler() throws Exception {
        Long terminId = terminBereitFuerErgebnis(8);
        Map<Long, String> teams = teamzuordnung(terminId);
        erfassen(spielerSitzung(0), terminId, "A", false).andExpect(status().isCreated());

        Map<String, Object> bilanz = alsKarte(mockMvc.perform(get("/api/v1/bilanz/lesen")
                        .cookie(new Cookie(COOKIE, spielerSitzung(0))))
                .andExpect(status().isOk()));

        boolean gewonnen = "A".equals(teams.get(spielerId(0)));
        assertThat(bilanz.get("siege")).isEqualTo(gewonnen ? 1 : 0);
        assertThat(bilanz.get("niederlagen")).isEqualTo(gewonnen ? 0 : 1);
        assertThat(bilanz.get("unentschieden")).isEqualTo(0);
    }

    /**
     * Ein Gast bekommt die leere Bilanz - <b>kein Fehler</b>.
     *
     * <p>Dieselbe Antwort wie fuer einen Spieler ohne gewertete Termine. Beide Faelle sind
     * absichtlich nicht unterscheidbar: Ein Kennzeichen verriete, wer Gast ist, ohne dass
     * jemand danach gefragt hat.
     */
    @Test
    void gastBekommtDieLeereBilanz() throws Exception {
        Map<String, Object> bilanz = alsKarte(mockMvc.perform(get("/api/v1/bilanz/lesen")
                        .cookie(new Cookie(COOKIE, gastSitzung("Testgast 4"))))
                .andExpect(status().isOk()));

        assertThat(bilanz).containsEntry("siege", 0)
                .containsEntry("niederlagen", 0)
                .containsEntry("unentschieden", 0);
    }

    // --------------------------------------------------------------------- Aufbau

    /**
     * Der vollstaendige Aufbau: Termin in der Vergangenheit, Zusagen, Einteilung, Abschluss.
     *
     * <p><b>Die Reihenfolge ist nicht verhandelbar</b> - siehe Klassen-JavaDoc.
     */
    private Long terminBereitFuerErgebnis(int anzahlSpieler) throws Exception {
        Long terminId = terminMitEinteilung(anzahlSpieler);
        abschliessen(terminId);
        return terminId;
    }

    private Long terminMitEinteilung(int anzahlSpieler) throws Exception {
        Long terminId = terminMitZusagen(anzahlSpieler);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());
        return terminId;
    }

    private Long terminMitZusagen(int anzahlSpieler) {
        Long terminId = terminAnlegen();
        for (int i = 0; i < anzahlSpieler; i++) {
            zusageAnlegen(terminId, spielerId(i), anzahlSpieler - i);
        }
        return terminId;
    }

    /** Jeder Termin eines Falls bekommt einen eigenen Tag; die Uhrzeit bleibt. */
    private Long terminAnlegen() {
        return jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit) VALUES (?, ?) RETURNING id
                """, Long.class, LocalDate.now().minusDays(BASIS_TAGE + terminZaehler++), UHRZEIT);
    }

    /** {@code vorMinuten} zaehlt rueckwaerts: die groessere Zahl meldet sich frueher. */
    private void zusageAnlegen(Long terminId, Long spielerId, int vorMinuten) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage, gemeldet_am)
                VALUES (?, ?, true, now() - make_interval(mins => CAST(? AS integer)))
                """, terminId, spielerId, vorMinuten);
    }

    private void gastZusageAnlegen(Long terminId, String name, String stufe, int vorMinuten) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, gast_name, gast_stufe, zusage, gemeldet_am)
                VALUES (?, ?, CAST(? AS varchar), true, now() - make_interval(mins => CAST(? AS integer)))
                """, terminId, name, stufe, vorMinuten);
    }

    private void abschliessen(Long terminId) {
        statusSetzen(terminId, "ABGESCHLOSSEN");
    }

    /**
     * Setzt den Status per SQL - <b>mit erhoehter {@code version}</b>.
     *
     * <p>Die Regel gilt fuer jede {@code version}-Spalte, die per SQL geaendert wird. Ohne den
     * Nachtrag liefe ein spaeterer Schreibvorgang auf derselben Zeile in einen Sperrkonflikt,
     * den niemand verursacht hat.
     */
    private void statusSetzen(Long terminId, String status) {
        jdbc.update("""
                UPDATE spieltag.termin SET status = CAST(? AS varchar), version = version + 1
                 WHERE id = ?
                """, status, terminId);
    }

    // --------------------------------------------------------------------- Aufrufe

    private ResultActions generieren(String token, Long terminId) throws Exception {
        return mockMvc.perform(post("/api/v1/teams/generieren")
                .cookie(new Cookie(COOKIE, token))
                .header("CF-Connecting-IP", "198.51.100.71")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d}".formatted(terminId)));
    }

    private ResultActions erfassen(String token, Long terminId, String sieger, boolean deutlich)
            throws Exception {
        return mockMvc.perform(post("/api/v1/ergebnis/erfassen")
                .cookie(new Cookie(COOKIE, token))
                .header("CF-Connecting-IP", "198.51.100.72")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d,\"sieger\":\"%s\",\"deutlich\":%b}"
                        .formatted(terminId, sieger, deutlich)));
    }

    private ResultActions korrigieren(String token, Long terminId, String sieger,
                                      boolean deutlich, long version) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/ergebnis/korrigieren")
                .cookie(new Cookie(COOKIE, token))
                .header("CF-Connecting-IP", "198.51.100.73")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d,\"sieger\":\"%s\",\"deutlich\":%b,\"version\":%d}"
                        .formatted(terminId, sieger, deutlich, version)));
    }

    private Map<String, Object> einzeln(String token, Long terminId) throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/termine/%d/lesen".formatted(terminId))
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    // --------------------------------------------------------------------- Auswertung

    private Map<String, Object> alsKarte(ResultActions ergebnis) throws Exception {
        return objectMapper.readValue(
                ergebnis.andReturn().getResponse().getContentAsString(), new TypeReference<>() {
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ergebnisAus(Map<String, Object> einzelansicht) {
        return (Map<String, Object>) einzelansicht.get("ergebnis");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> felder(ResultActions ergebnis) throws Exception {
        return (Map<String, String>) alsKarte(ergebnis).get("felder");
    }

    /** Der Fehlercode aus dem Problem Detail; nie der Meldungstext - der ist Anzeigetext. */
    private String fehlercode(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("code");
    }

    private String detail(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("detail");
    }

    private long version(ResultActions ergebnis) throws Exception {
        return ((Number) alsKarte(ergebnis).get("version")).longValue();
    }

    /**
     * Die gespeicherte Zeile, roh - fuer die Gegenproben, die an der API nicht sichtbar sind.
     *
     * <p>{@code sieger} kommt als {@code bpchar} zurueck und wird beim Vergleichen
     * getrimmt: {@code CHAR(1)} fuellt nicht auf, aber der Treiber liefert den Typ, und ein
     * Vergleich, der sich darauf verlaesst, bricht bei einer spaeteren Spaltenaenderung.
     */
    private Map<String, Object> ergebniszeile(Long terminId) {
        return jdbc.queryForMap("SELECT * FROM spieltag.ergebnis WHERE termin_id = ?", terminId);
    }

    /**
     * Profil-Id zu Team ({@code A}/{@code B}) der aktuellen Einteilung, Gaeste ausgenommen.
     *
     * <p><b>Bewusst ueber {@code team_zuteilung} und nicht ueber die Antwort der
     * Generierung:</b> Die traegt nur Anzeigenamen. Und bewusst nicht ueber dieselbe
     * Aggregation wie die Bilanzrechnung - eine Gegenprobe, die den geprueften Weg nachbaut,
     * prueft nichts.
     */
    private Map<Long, String> teamzuordnung(Long terminId) {
        Map<Long, String> zuordnung = new LinkedHashMap<>();
        for (Map<String, Object> zeile : jdbc.queryForList("""
                SELECT tn.spieler_id, tz.team
                  FROM spieltag.team_zuteilung tz
                  JOIN spieltag.team_generierung tg ON tg.id = tz.generierung_id
                                                   AND tg.abgeloest_am IS NULL
                  JOIN spieltag.teilnahme tn        ON tn.id = tz.teilnahme_id
                 WHERE tg.termin_id = ?
                   AND tn.spieler_id IS NOT NULL
                 ORDER BY tz.id
                """, terminId)) {
            zuordnung.put(((Number) zeile.get("spieler_id")).longValue(),
                    String.valueOf(zeile.get("team")).trim());
        }
        return zuordnung;
    }

    private void bilanzErwartet(Long spielerId, int siege, int niederlagen, int unentschieden) {
        Map<String, Object> zeile = jdbc.queryForMap("""
                SELECT name, anz_siege, anz_niederlagen, anz_unentschieden
                  FROM profil.spieler WHERE id = ?
                """, spielerId);

        assertThat(List.of(zeile.get("anz_siege"), zeile.get("anz_niederlagen"),
                zeile.get("anz_unentschieden")))
                .as("Bilanz von %s", zeile.get("name"))
                .containsExactly(siege, niederlagen, unentschieden);
    }

    private int gesamt(Long spielerId) {
        return jdbc.queryForObject("""
                SELECT anz_siege + anz_niederlagen + anz_unentschieden
                  FROM profil.spieler WHERE id = ?
                """, Integer.class, spielerId);
    }

    private int anzahlProfileMitBilanz() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM profil.spieler
                 WHERE anz_siege + anz_niederlagen + anz_unentschieden > 0
                """, Integer.class);
    }

    /**
     * Ein Detail aus dem juengsten Protokolleintrag zu diesem Termin.
     *
     * <p>{@code ->>} liefert den Wert als Text - auch bei Zahlen und Wahrheitswerten. Der
     * Vergleich laeuft deshalb gegen Zeichenketten. <b>Der jsonb-Existenzoperator {@code ?}
     * waere hier nicht verwendbar</b>: Fuer JDBC ist das Fragezeichen ein Bind-Platzhalter.
     */
    private String auditDetail(Long terminId, String aktion, String schluessel) {
        return jdbc.queryForObject("""
                SELECT details ->> CAST(? AS text)
                  FROM profil.audit_log
                 WHERE aktion = CAST(? AS varchar)
                   AND entitaet = 'termin'
                   AND entitaet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, String.class, schluessel, aktion, terminId);
    }

    // --------------------------------------------------------------------- Sitzungen

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

    private String spielerSitzung(int position) {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId(position), Rolle.USER);
    }

    /**
     * Meldet einen Gast ueber den echten Endpunkt an und liefert dessen Token.
     *
     * <p>Ueber den Endpunkt und nicht ueber {@code SessionService#anlegen}: Nur so entsteht die
     * Belegung in {@code profil.gast_slot}. <b>Der Token rotiert dabei</b> - der
     * zurueckgegebene ist der neue.
     */
    private String gastSitzung(String gastName) throws Exception {
        MvcResult ergebnis = mockMvc.perform(post("/api/v1/auth/gast/anmelden")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gastName\":\"%s\",\"stufe\":\"MITTEL\"}".formatted(gastName)))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = ergebnis.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        String ohneName = setCookie.substring(setCookie.indexOf('=') + 1);
        int ende = ohneName.indexOf(';');
        return ende < 0 ? ohneName : ohneName.substring(0, ende);
    }

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }
}
