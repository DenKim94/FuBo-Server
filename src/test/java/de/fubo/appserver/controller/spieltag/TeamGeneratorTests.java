package de.fubo.appserver.controller.spieltag;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den Generierungslauf am Termin: {@code POST /api/v1/teams/generieren} und das Feld
 * {@code teams} der Einzelansicht (S5, Abschnitte 6 bis 9.3).
 *
 * <h2>Was hier geprueft wird - und was nicht</h2>
 * Die Rechnung selbst steht in {@code TeamverfahrenTests} und laeuft dort ohne Spring-Kontext.
 * Diese Klasse prueft die Anbindung: Kontingent, Ablosung, Snapshot, Veraltet-Kennzeichen,
 * Auswechselspieler und - am wichtigsten - <b>dass keine Bewertung nach aussen dringt</b>.
 * Geprueft wird dafuer die <i>vollstaendige</i> Feldliste eines {@code TeamEintrag} und nicht
 * das Fehlen einzelner Namen; ein spaeter ergaenztes Feld faellt sonst niemandem auf (A12).
 *
 * <h2>Eigener Zeitstreifen: 500 Tage, 20:15</h2>
 * {@code uq_termin_zeit UNIQUE (datum, uhrzeit)} ist <b>global</b>, und beide Achsen zaehlen.
 * Vergeben sind 40/18:15 ({@code TerminControllerTests}), 120/19:45
 * ({@code TerminVerwaltungControllerTests}), 200/17:30 ({@code TeilnehmerlisteTests}) und
 * 300/16:05 ({@code SpielerControllerTests}). <b>Nicht 300/20:15</b>, wie zunaechst vorgesehen:
 * Der Tag ist vergeben, der Constraint hielte zwar - aber der Naechste, der nur die Tage
 * vergleicht, kollidiert.
 *
 * <h2>Die Konfiguration wird vor dem ersten HTTP-Aufruf gesetzt</h2>
 * Jeder Aufruf laedt ueber den Sitzungsfilter die Konfigurationszeile in den
 * Persistence-Context; eine spaetere Aenderung per SQL bliebe fuer denselben Vorgang
 * unsichtbar. <b>Der Test waere gruen und prueft nichts.</b> Reihenfolge deshalb ausnahmslos:
 * Konfiguration setzen, Daten anlegen, erst dann aufrufen.
 *
 * <h2>Meldezeiten werden per SQL gesetzt</h2>
 * {@code now()} ist innerhalb einer Transaktion konstant; ohne diesen Schritt truegen alle
 * Zusagen eines Falls denselben Zeitstempel, und die Warteschlangenreihenfolge waere nicht
 * pruefbar. <b>Der Parameter zaehlt rueckwaerts</b> - die groessere Zahl meldet sich frueher
 * und steht weiter oben.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TeamGeneratorTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Abstand zum heutigen Tag; erste Haelfte des Kollisionsschutzes. */
    private static final int BASIS_TAGE = 500;

    /** Uhrzeit aller Termine dieser Klasse; zweite Haelfte des Kollisionsschutzes. */
    private static final LocalTime UHRZEIT = LocalTime.of(20, 15);

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

        // Der Zwischenspeicher ist ein Singleton und ueberlebt die Test-Transaktion; ohne das
        // Verwerfen saehe der naechste Fall Profildaten, die es in der Datenbank nicht gibt.
        profilStammdatenCache.verwerfen();
    }

    // --------------------------------------------------------------------- Der Lauf

    /** Acht Zusagen ergeben zwei Teams zu vier, und alle acht stehen darin. */
    @Test
    void generiertZweiTeamsAusAllenZusagen() throws Exception {
        Long terminId = terminMitZusagen(8);

        Map<String, Object> einteilung = alsKarte(
                generieren(spielerSitzung(0), terminId).andExpect(status().isCreated()));

        assertThat(namen(einteilung, "teamA")).hasSize(4);
        assertThat(namen(einteilung, "teamB")).hasSize(4);
        assertThat(alleNamen(einteilung)).hasSize(8).doesNotHaveDuplicates();
    }

    /**
     * Der zweite Lauf desselben Nutzers scheitert am Kontingent (A15).
     *
     * <p>{@code anzTeamGenerator} steht in den Demodaten auf 1 - das ist der Regelfall und
     * wird hier ausdruecklich nicht verstellt.
     */
    @Test
    void zweiterLaufDesselbenNutzersIstErschoepft() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());

        assertThat(fehlercode(generieren(token, terminId).andExpect(status().isConflict())))
                .isEqualTo("KONTINGENT_ERSCHOEPFT");
    }

    /**
     * Das Kontingent zaehlt <b>je Nutzer</b>, nicht je Termin.
     *
     * <p>Ohne diesen Fall bliebe unbemerkt, wenn der Schluessel den Akteur verloere - dann
     * haette der ganze Spieltag einen einzigen Lauf.
     */
    @Test
    void zweiterNutzerHatEinEigenesKontingent() throws Exception {
        Long terminId = terminMitZusagen(8);

        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());
        generieren(spielerSitzung(1), terminId).andExpect(status().isCreated());
    }

    /**
     * Eine Absage setzt das Kontingent wieder frei (A15).
     *
     * <p><b>Nicht durch Zuruecksetzen, sondern durch einen neuen Schluessel:</b> Die Absage
     * erhoeht {@code teilnehmer_version}, und damit passt keine bestehende Kontingentzeile
     * mehr. Genau das ist der Mechanismus, und genau das prueft dieser Fall.
     */
    @Test
    void nachEinerAbsageStehtDasKontingentWiederOffen() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        rueckmeldung(spielerSitzung(7), terminId, false).andExpect(status().isNoContent());

        generieren(token, terminId).andExpect(status().isCreated());
    }

    /** Der zweite Lauf loest den ersten ab - es gibt je Termin genau eine aktuelle Einteilung. */
    @Test
    void derZweiteLaufLoestDenErstenAb() throws Exception {
        Long terminId = terminMitZusagen(8);

        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());
        generieren(spielerSitzung(1), terminId).andExpect(status().isCreated());

        assertThat(anzahlLaeufe(terminId)).as("Historie bleibt erhalten").isEqualTo(2);
        assertThat(anzahlAktuellerLaeufe(terminId))
                .as("ix_team_generierung_aktuell setzt das voraus, erzwingt es aber nicht")
                .isEqualTo(1);
    }

    // --------------------------------------------------------------------- Ablehnungen

    /** Unter der Mindestzahl wird abgelehnt; {@code detail} nennt Ist und Soll. */
    @Test
    void zuWenigeZusagenWerdenAbgelehnt() throws Exception {
        Long terminId = terminMitZusagen(4);

        ResultActions ergebnis = generieren(spielerSitzung(0), terminId)
                .andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("ZU_WENIG_TEILNEHMER");
        assertThat(detail(ergebnis)).contains("4").contains("6");
    }

    /**
     * Die Wartenden stehen in keinem Team (A11).
     *
     * <p>Die Aufstellung schneidet bei {@code max_teilnehmer} ab - und zwar per {@code LIMIT}
     * in derselben Abfrage, die auch sortiert. Waere die Reihenfolge dort eine andere als in
     * der Teilnehmerliste, zeigte die Liste einen Wartenden, den der Generator eingeteilt hat.
     */
    @Test
    void wartendeWerdenNichtEingeteilt() throws Exception {
        maxTeilnehmerSetzen(6);
        Long terminId = terminMitZusagen(8);

        Map<String, Object> einteilung = alsKarte(
                generieren(spielerSitzung(0), terminId).andExpect(status().isCreated()));

        assertThat(alleNamen(einteilung)).hasSize(6);
        assertThat(alleNamen(einteilung))
                .as("Die beiden zuletzt Gemeldeten warten")
                .doesNotContain(spielerName(6), spielerName(7));
    }

    /** Ein fehlender Skillwert wird abgelehnt und nicht aufgefuellt; die Meldung nennt den Namen. */
    @Test
    void fehlenderSkillwertWirdAbgelehnt() throws Exception {
        Long terminId = terminMitZusagen(8);
        jdbc.update("DELETE FROM profil.spieler_skill WHERE spieler_id = ? AND kategorie = 'ANGRIFF'",
                spielerId(3));

        ResultActions ergebnis = generieren(spielerSitzung(0), terminId)
                .andExpect(status().isConflict());

        assertThat(fehlercode(ergebnis)).isEqualTo("SKILLWERTE_UNVOLLSTAENDIG");
        assertThat(detail(ergebnis)).contains(spielerName(3));
    }

    /** Fuer einen abgesagten Termin wird nicht mehr generiert. */
    @Test
    void abgesagterTerminWirdAbgelehnt() throws Exception {
        Long terminId = terminMitZusagen(8);
        jdbc.update("UPDATE spieltag.termin SET status = 'ABGESAGT', version = version + 1 WHERE id = ?",
                terminId);

        assertThat(fehlercode(generieren(spielerSitzung(0), terminId)
                .andExpect(status().isConflict()))).isEqualTo("TERMIN_GESCHLOSSEN");
    }

    /**
     * Ein fixierter Termin wird abgelehnt (A18, 10.2).
     *
     * <p>Das Flag setzt sonst der Auftrag bei Terminbeginn; hier wird es unmittelbar gesetzt,
     * weil der Zustand geprueft werden soll und nicht der Auftrag.
     */
    @Test
    void fixierterTerminWirdAbgelehnt() throws Exception {
        Long terminId = terminMitZusagen(8);
        jdbc.update("UPDATE spieltag.termin SET teams_fixiert = true, version = version + 1 WHERE id = ?",
                terminId);

        assertThat(fehlercode(generieren(spielerSitzung(0), terminId)
                .andExpect(status().isConflict()))).isEqualTo("TEAMS_FIXIERT");
    }

    /** Ohne Cookie kommt der Aufruf nicht durch die Filterkette. */
    @Test
    void ohneCookieLiefert401() throws Exception {
        mockMvc.perform(post("/api/v1/teams/generieren")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Ein Gast darf generieren (A15).
     *
     * <p>Der Endpunkt liegt bewusst nicht unter {@code /admin/}. Das Kontingent zaehlt fuer
     * ihn ueber den belegten Gastplatz - ohne {@code gastSlotId} in der Sitzung waeren beide
     * Akteurspalten leer und {@code ck_kontingent_akteur} liesse die Zeile nicht zu.
     */
    @Test
    void gastDarfGenerieren() throws Exception {
        Long terminId = terminMitZusagen(8);

        generieren(gastSitzung("Testgast 1"), terminId).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM spieltag.generierung_kontingent
                 WHERE termin_id = ? AND akteur_gast_slot_id IS NOT NULL
                """, Integer.class, terminId))
                .as("Der Gast zaehlt ueber seinen Platz")
                .isEqualTo(1);
    }

    // --------------------------------------------------------------------- Gaeste und Snapshot

    /** Ein Gast ohne Stufe wird wie {@code MITTEL} bewertet (A17, 0.4 Punkt 3). */
    @Test
    void gastOhneStufeZaehltAlsMittel() throws Exception {
        Long terminId = terminMitZusagen(7);
        gastZusageAnlegen(terminId, "Testgast 2", null, 1);

        Map<String, Object> einteilung = alsKarte(
                generieren(spielerSitzung(0), terminId).andExpect(status().isCreated()));

        assertThat(alleNamen(einteilung)).contains("Testgast 2");
        assertThat(scoreVon(terminId, "Testgast 2"))
                .as("Die Werte stammen aus der Vorlage der Stufe MITTEL")
                .isEqualByComparingTo(vorlagenScore("MITTEL"));
    }

    /**
     * Der Snapshot steht als JSON-<b>Objekt</b> in der Spalte, nicht als Text.
     *
     * <p>Der Fallstrick aus S3: Eine Karte ueber {@code Map#toString} in eine
     * {@code jsonb}-Spalte zu schreiben erzeugt JSON-Text, ohne Fehlermeldung -
     * {@code ->>'ANGRIFF'} liefert darauf {@code null}. Geprueft wird deshalb der Typ und
     * nicht ein herausgelesener Wert.
     */
    @Test
    void skillsSnapshotIstEinJsonObjekt() throws Exception {
        Long terminId = terminMitZusagen(8);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());

        List<String> typen = jdbc.queryForList("""
                SELECT jsonb_typeof(tz.skills_snapshot)
                  FROM spieltag.team_zuteilung tz
                  JOIN spieltag.team_generierung tg ON tg.id = tz.generierung_id
                 WHERE tg.termin_id = ?
                """, String.class, terminId);

        assertThat(typen).hasSize(8).containsOnly("object");
    }

    // --------------------------------------------------------------------- Auswechselspieler

    /**
     * Bei ungerader Zahl weist das groessere Team den schwaechsten Spieler als
     * Auswechselspieler aus (A20b, Vorgabe {@code SCHWAECHSTER_UEBERZAHL}).
     *
     * <p>Geprueft wird gegen {@code score_snapshot} und nicht gegen einen im Test
     * nachgerechneten Wert: Der Snapshot ist die Zahl, mit der der Lauf gearbeitet hat, und
     * eine zweite Rechnung im Test waere eine zweite Wahrheit.
     */
    @Test
    void beiUngeraderZahlSitztDerSchwaechsteDerUeberzahlDrausen() throws Exception {
        Long terminId = terminMitZusagen(9);

        Map<String, Object> einteilung = alsKarte(
                generieren(spielerSitzung(0), terminId).andExpect(status().isCreated()));

        String auswechselspieler = (String) einteilung.get("auswechselspieler");
        assertThat(auswechselspieler).isNotNull();

        List<String> ueberzahl = namen(einteilung, "teamA").size() > namen(einteilung, "teamB").size()
                ? namen(einteilung, "teamA")
                : namen(einteilung, "teamB");

        assertThat(ueberzahl).hasSize(5).contains(auswechselspieler);
        assertThat(scoreVon(terminId, auswechselspieler))
                .as("Er hat den kleinsten gespeicherten Score seines Teams")
                .isEqualByComparingTo(ueberzahl.stream().map(name -> scoreVon(terminId, name))
                        .min(BigDecimal::compareTo).orElseThrow());
    }

    /** Bei gerader Zahl gibt es keinen Auswechselspieler - {@code null}, kein Platzhalter. */
    @Test
    void beiGeraderZahlGibtEsKeinenAuswechselspieler() throws Exception {
        Long terminId = terminMitZusagen(8);

        Map<String, Object> einteilung = alsKarte(
                generieren(spielerSitzung(0), terminId).andExpect(status().isCreated()));

        assertThat(einteilung).containsEntry("auswechselspieler", null);
        assertThat(alleEintraege(einteilung))
                .as("und niemand traegt das Kennzeichen")
                .allMatch(eintrag -> Boolean.FALSE.equals(eintrag.get("auswechselspieler")));
    }

    // --------------------------------------------------------------------- Lesepfad

    /** Ohne Lauf traegt die Einzelansicht {@code teams: null} - kein Fehler, sondern Normalzustand. */
    @Test
    void einzelansichtOhneLaufLiefertTeamsNull() throws Exception {
        Long terminId = terminMitZusagen(8);

        assertThat(einzeln(spielerSitzung(0), terminId)).containsEntry("teams", null);
    }

    /**
     * Die Einteilung erscheint in der Einzelansicht - und traegt <b>genau drei</b> Felder je
     * Teilnehmer.
     *
     * <p>Der wichtigste Fall der Klasse: Geprueft wird die vollstaendige Feldliste und nicht
     * das Fehlen einzelner Namen. Ein spaeter ergaenztes Feld - ein Score, eine Gast-Stufe -
     * faellt sonst niemandem auf, und die Antwort erreicht jede Rolle (A12).
     */
    @Test
    void einTeamEintragTraegtGenauDreiFelder() throws Exception {
        Long terminId = terminMitZusagen(8);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());

        Map<String, Object> teams = teams(einzeln(spielerSitzung(0), terminId));

        assertThat(teams).containsOnlyKeys(
                "erzeugtAm", "erzeugtVon", "veraltet", "auswechselspieler", "teamA", "teamB");
        assertThat(alleEintraege(teams)).allSatisfy(eintrag ->
                assertThat(eintrag).containsOnlyKeys("anzeigeName", "gast", "auswechselspieler"));
    }

    /** Ein frischer Lauf ist nicht veraltet. */
    @Test
    void einFrischerLaufIstNichtVeraltet() throws Exception {
        Long terminId = terminMitZusagen(8);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());

        assertThat(teams(einzeln(spielerSitzung(0), terminId))).containsEntry("veraltet", false);
    }

    /**
     * Eine Skillaenderung an einem Eingeteilten laesst die Einteilung veralten (A15).
     *
     * <p>Dieselben Namen ergeben eine andere Aufteilung - deshalb zaehlt eine geaenderte
     * Bewertung als Teilnehmeraenderung. <b>Verstecken waere falsch</b>: Jemand hat die
     * Aufstellung vielleicht schon vorgelesen.
     */
    @Test
    void skillaenderungLaesstDieEinteilungVeralten() throws Exception {
        Long terminId = terminMitZusagen(8);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/user/bearbeiten")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.60")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spielerId\":%d,\"skills\":{\"ANGRIFF\":1}}"
                                .formatted(spielerId(2))))
                .andExpect(status().isNoContent());

        assertThat(teams(einzeln(spielerSitzung(0), terminId))).containsEntry("veraltet", true);
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /**
     * Legt einen Termin an und laesst die ersten {@code anzahl} Profile zusagen.
     *
     * <p>Die Meldezeiten werden ausdruecklich gesetzt: {@code now()} ist innerhalb einer
     * Transaktion konstant, sonst waere die Reihenfolge nicht bestimmbar. Der Parameter zaehlt
     * rueckwaerts - das erste Profil meldet sich am fruehesten und steht ganz oben.
     */
    private Long terminMitZusagen(int anzahl) {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE));
        for (int i = 0; i < anzahl; i++) {
            zusageAnlegen(terminId, spielerId(i), anzahl - i);
        }
        return terminId;
    }

    private Long terminAnlegen(LocalDate datum) {
        return jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit) VALUES (?, ?) RETURNING id
                """, Long.class, datum, UHRZEIT);
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

    /** Muss vor dem ersten HTTP-Aufruf laufen - siehe Klassen-JavaDoc. */
    private void maxTeilnehmerSetzen(int wert) {
        jdbc.update("""
                UPDATE configs.app_config SET max_teilnehmer = ?, version = version + 1 WHERE id = 1
                """, wert);
    }

    private ResultActions generieren(String token, Long terminId) throws Exception {
        return mockMvc.perform(post("/api/v1/teams/generieren")
                .cookie(new Cookie(COOKIE, token))
                .header("CF-Connecting-IP", "198.51.100.61")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d}".formatted(terminId)));
    }

    private ResultActions rueckmeldung(String token, Long terminId, boolean zusage) throws Exception {
        return mockMvc.perform(post("/api/v1/termine/rueckmeldung")
                .cookie(new Cookie(COOKIE, token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d,\"zusage\":%b}".formatted(terminId, zusage)));
    }

    private Map<String, Object> einzeln(String token, Long terminId) throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/termine/%d/lesen".formatted(terminId))
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    private Map<String, Object> alsKarte(ResultActions ergebnis) throws Exception {
        return objectMapper.readValue(
                ergebnis.andReturn().getResponse().getContentAsString(), new TypeReference<>() {
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> teams(Map<String, Object> details) {
        return (Map<String, Object>) details.get("teams");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eintraege(Map<String, Object> einteilung, String team) {
        return (List<Map<String, Object>>) einteilung.get(team);
    }

    private static List<Map<String, Object>> alleEintraege(Map<String, Object> einteilung) {
        return Stream.concat(eintraege(einteilung, "teamA").stream(),
                        eintraege(einteilung, "teamB").stream())
                .toList();
    }

    private static List<String> namen(Map<String, Object> einteilung, String team) {
        return eintraege(einteilung, team).stream()
                .map(eintrag -> (String) eintrag.get("anzeigeName"))
                .toList();
    }

    private static List<String> alleNamen(Map<String, Object> einteilung) {
        return alleEintraege(einteilung).stream()
                .map(eintrag -> (String) eintrag.get("anzeigeName"))
                .toList();
    }

    /** Der Fehlercode aus dem Problem Detail; nie der Meldungstext - der ist Anzeigetext. */
    private String fehlercode(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("code");
    }

    /** {@code detail} ist Anzeigetext; geprueft wird er nur dort, wo er Zahlen nennen muss. */
    private String detail(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("detail");
    }

    /** Der gespeicherte Score eines Teilnehmers - die Zahl, mit der der Lauf gerechnet hat. */
    private BigDecimal scoreVon(Long terminId, String anzeigeName) {
        return jdbc.queryForObject("""
                SELECT tz.score_snapshot
                  FROM spieltag.team_zuteilung tz
                  JOIN spieltag.team_generierung tg ON tg.id = tz.generierung_id
                  JOIN spieltag.teilnahme tn        ON tn.id = tz.teilnahme_id
                  LEFT JOIN profil.spieler s        ON s.id  = tn.spieler_id
                 WHERE tg.termin_id = ?
                   AND tg.abgeloest_am IS NULL
                   AND COALESCE(s.name, tn.gast_name) = ?
                """, BigDecimal.class, terminId, anzeigeName);
    }

    /** Die gewichtete Gesamtstaerke einer Gast-Vorlage, gerechnet in der Datenbank. */
    private BigDecimal vorlagenScore(String stufe) {
        return jdbc.queryForObject("""
                SELECT sum(gv.wert * k.gewicht)
                  FROM profil.gast_vorlage gv
                  JOIN profil.skill_kategorie k ON k.schluessel = gv.kategorie
                 WHERE gv.stufe = ? AND k.aktiv
                """, BigDecimal.class, stufe);
    }

    private int anzahlLaeufe(Long terminId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM spieltag.team_generierung WHERE termin_id = ?",
                Integer.class, terminId);
    }

    private int anzahlAktuellerLaeufe(Long terminId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM spieltag.team_generierung
                 WHERE termin_id = ? AND abgeloest_am IS NULL
                """, Integer.class, terminId);
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

    private String spielerSitzung(int position) {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId(position), Rolle.USER);
    }

    /**
     * Meldet einen Gast ueber den echten Endpunkt an und liefert dessen Token.
     *
     * <p>Ueber den Endpunkt und nicht ueber {@code SessionService#anlegen}: Nur so entsteht
     * die Belegung in {@code profil.gast_slot}, und genau daran haengt das Kontingent eines
     * Gastes. <b>Der Token rotiert dabei</b> - der zurueckgegebene ist der neue.
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
