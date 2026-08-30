package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.service.spieltag.TerminService;
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
 * Prueft die vier schreibenden Terminendpunkte aus {@code S4_UMSETZUNG.md},
 * Abschnitte 3 und 4.
 *
 * <h2>Eigener Zeitstreifen je Testklasse</h2>
 * {@code uq_termin_zeit UNIQUE (datum, uhrzeit)} ist <b>global</b> - zwei Testklassen, die
 * beide denselben Zeitpunkt anlegen, kollidieren, auch wenn jede fuer sich zurueckgerollt
 * wird. Diese Klasse arbeitet um {@link #BASIS_TAGE} herum und zur {@link #UHRZEIT};
 * {@code TerminControllerTests} hat beides anders. <b>Der Serientest braucht den weitesten
 * Abstand</b>, weil er ueber Wochen hinweg Zeilen anlegt.
 *
 * <h2>Warum die Klasse {@code @Transactional} tragen darf</h2>
 * In diesen Paketen laeuft nichts in einer eigenen Transaktion. Der Grund, aus dem
 * {@code PasswortResetControllerTests} darauf verzichtet - ein Zaehler mit
 * {@code REQUIRES_NEW} -, hat hier kein Gegenstueck. Jeder Fall wird damit zurueckgerollt,
 * und die angelegten Termine wirken nicht in die naechste Klasse hinein.
 *
 * <h2>Testdaten</h2>
 * Keine realen Personennamen; Orte und Serientitel sind neutral. Fuer Zeitgrenzen kein
 * {@code Thread.sleep} - die Termine liegen weit genug von "jetzt" entfernt, dass die
 * Zeitzone der Anwendung keine Rolle spielt.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TerminVerwaltungControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Abstand zum heutigen Tag; haelt diese Klasse von den uebrigen fern. */
    private static final int BASIS_TAGE = 120;

    /** Uhrzeit aller Termine dieser Klasse - zweite Haelfte des Kollisionsschutzes. */
    private static final LocalTime UHRZEIT = LocalTime.of(19, 45);

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Fuer den Auftrag aus A18. Er gehoert fachlich zu denselben Zustandsuebergaengen wie das
     * Absagen und steht deshalb hier statt in einer eigenen Klasse mit zwei Faellen.
     */
    @Autowired
    private TerminService terminService;

    private MockMvc mockMvc;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // --------------------------------------------------------------------- Anlegen

    /** Der Erfolgsfall: {@code 201} mit der Id, und die Zeile steht im Zustand GEPLANT. */
    @Test
    void anlegenLegtDenTerminAn() throws Exception {
        LocalDate datum = tag(0);

        Map<String, Object> antwort = anlegenErfolgreich(datum, "Sporthalle Nord");
        Long terminId = ((Number) antwort.get("terminId")).longValue();

        Map<String, Object> zeile = terminZeile(terminId);
        assertThat(zeile.get("status")).isEqualTo("GEPLANT");
        assertThat(zeile.get("ort")).isEqualTo("Sporthalle Nord");
        assertThat(zeile.get("serie_id")).as("Ein Einzeltermin gehoert zu keiner Serie").isNull();
        assertThat(((Number) zeile.get("teilnehmer_version")).intValue()).isZero();
    }

    /**
     * Ein Zeitpunkt in der Vergangenheit wird abgelehnt.
     *
     * <p>Nicht weil die Datenbank es verboete, sondern weil niemand mehr zusagen koennte -
     * der Termin laege als unveraenderliche Leiche im Bestand.
     */
    @Test
    void anlegenLehntVergangenenZeitpunktAb() throws Exception {
        anlegen(LocalDate.now().minusDays(1), null)
                .andExpect(status().isBadRequest());
    }

    /**
     * Der zweite Termin zur selben Zeit scheitert - und zwar mit einer Meldung, nicht mit
     * einem {@code 500}.
     *
     * <p>{@code uq_termin_zeit} ist global: Der bestehende Termin kann an einem ganz anderen
     * Ort liegen.
     */
    @Test
    void zweiterTerminZurSelbenZeitLiefert409() throws Exception {
        LocalDate datum = tag(1);
        anlegenErfolgreich(datum, "Sporthalle Nord");

        String antwort = anlegen(datum, "Sporthalle Sued")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(karte(antwort).get("code")).isEqualTo("TERMIN_BELEGT");
    }

    // --------------------------------------------------------------------- Aendern

    /** Datum und Uhrzeit lassen sich verschieben; die Version steigt dabei. */
    @Test
    void aendernVerschiebtDenTermin() throws Exception {
        Long terminId = angelegterTermin(tag(2));
        long version = version(terminId);
        LocalDate neuesDatum = tag(3);

        aendern(Map.of("terminId", terminId, "version", version,
                "datum", neuesDatum.toString(), "uhrzeit", "20:30:00"))
                .andExpect(status().isNoContent());

        Map<String, Object> zeile = terminZeile(terminId);
        assertThat(zeile.get("datum").toString()).isEqualTo(neuesDatum.toString());
        assertThat(zeile.get("uhrzeit").toString()).startsWith("20:30");
        assertThat(version(terminId)).isGreaterThan(version);
    }

    /**
     * Eine leere Zeichenkette leert den Ort, ein fehlendes Feld laesst ihn stehen.
     *
     * <p>Das ist der ganze Grund, aus dem hier feldweise geschrieben wird statt als
     * Voll-Update: Beide Faelle bleiben unterscheidbar.
     */
    @Test
    void ortLaesstSichLeerenUndBleibtSonstStehen() throws Exception {
        Long terminId = angelegterTermin(tag(4), "Sporthalle Nord");

        aendern(Map.of("terminId", terminId, "version", version(terminId), "uhrzeit", "20:15:00"))
                .andExpect(status().isNoContent());
        assertThat(terminZeile(terminId).get("ort"))
                .as("Weglassen heisst nicht aendern")
                .isEqualTo("Sporthalle Nord");

        aendern(Map.of("terminId", terminId, "version", version(terminId), "ort", ""))
                .andExpect(status().isNoContent());
        assertThat(terminZeile(terminId).get("ort")).isNull();
    }

    /**
     * Eine veraltete Version wird abgewiesen, bevor irgendetwas geschrieben ist.
     *
     * <p>Derselbe Fehlercode wie bei der Konfiguration in S3 - genau dafuer wurde er dort
     * allgemein benannt.
     */
    @Test
    void aendernMitVeralteterVersionLiefert409() throws Exception {
        Long terminId = angelegterTermin(tag(5), "Sporthalle Nord");

        String antwort = aendern(Map.of("terminId", terminId, "version", version(terminId) + 1,
                "ort", "Sporthalle Sued"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(karte(antwort).get("code")).isEqualTo("DATEN_VERALTET");
        assertThat(terminZeile(terminId).get("ort"))
                .as("Bei einem Konflikt wird nichts geschrieben")
                .isEqualTo("Sporthalle Nord");
    }

    /**
     * Ein Aufruf ohne jede Angabe wird abgelehnt.
     *
     * <p>Er taete nichts, hinterliesse aber einen Protokolleintrag und eine erhoehte
     * Version - dieselbe Regel wie bei {@code /admin/user/bearbeiten}.
     */
    @Test
    void aendernOhneAenderungLiefert400() throws Exception {
        Long terminId = angelegterTermin(tag(6));

        aendern(Map.of("terminId", terminId, "version", version(terminId)))
                .andExpect(status().isBadRequest());
    }

    /** Ein Termin laesst sich nicht auf einen bereits belegten Zeitpunkt schieben. */
    @Test
    void aendernAufBelegtenZeitpunktLiefert409() throws Exception {
        LocalDate belegt = tag(7);
        angelegterTermin(belegt);
        Long verschiebbar = angelegterTermin(tag(8));

        String antwort = aendern(Map.of("terminId", verschiebbar, "version", version(verschiebbar),
                "datum", belegt.toString()))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(karte(antwort).get("code")).isEqualTo("TERMIN_BELEGT");
    }

    /**
     * Ein Termin laesst sich nicht in die Vergangenheit schieben.
     *
     * <p>Die Pruefung greift nur beim Verschieben: Der Ort eines laengst vergangenen Termins
     * bleibt berichtigbar, weil das niemandem schadet.
     */
    @Test
    void aendernInDieVergangenheitLiefert400() throws Exception {
        Long terminId = angelegterTermin(tag(9));

        aendern(Map.of("terminId", terminId, "version", version(terminId),
                "datum", LocalDate.now().minusDays(1).toString()))
                .andExpect(status().isBadRequest());
    }

    /** Eine unbekannte Id liefert {@code 404}. */
    @Test
    void aendernUnbekannterIdLiefert404() throws Exception {
        aendern(Map.of("terminId", 999999999L, "version", 0L, "ort", "Sporthalle Nord"))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------- Absagen

    /**
     * Die Absage setzt den Status und laesst die Teilnahmen stehen.
     *
     * <p><b>Der Kern der Entscheidung "absagen statt loeschen":</b> Fuenf Tabellen haengen
     * mit {@code ON DELETE CASCADE} am Termin. Waere hier ein {@code DELETE}, verschwaende
     * mit der Zeile auch der einzige Beleg dafuer, wer zugesagt hatte.
     */
    @Test
    void absagenSetztDenStatusUndBehaeltDieTeilnahmen() throws Exception {
        Long terminId = angelegterTermin(tag(10));
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage)
                VALUES (?, (SELECT id FROM profil.spieler
                             WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1), true)
                """, terminId);

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(terminZeile(terminId).get("status")).isEqualTo("ABGESAGT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM spieltag.teilnahme WHERE termin_id = ?", Integer.class, terminId))
                .isEqualTo(1);
    }

    /**
     * Die Absage ist endgueltig: Ein zweiter Aufruf laeuft in {@code 409}, und es gibt
     * keinen Weg zurueck nach GEPLANT.
     */
    @Test
    void zweitesAbsagenLiefert409() throws Exception {
        Long terminId = angelegterTermin(tag(11));
        absagen(terminId).andExpect(status().isNoContent());

        String antwort = absagen(terminId)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(karte(antwort).get("code")).isEqualTo("TERMIN_GESCHLOSSEN");
    }

    /** Ein abgesagter Termin laesst sich auch nicht mehr aendern. */
    @Test
    void aendernEinesAbgesagtenTerminsLiefert409() throws Exception {
        Long terminId = angelegterTermin(tag(12));
        long version = version(terminId);
        absagen(terminId).andExpect(status().isNoContent());

        String antwort = aendern(Map.of("terminId", terminId, "version", version,
                "ort", "Sporthalle Sued"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(karte(antwort).get("code")).isEqualTo("TERMIN_GESCHLOSSEN");
    }

    // --------------------------------------------------------------------- Status (A19)

    /**
     * Der Status laesst sich ueber das Bearbeitungsformular setzen (A19, 30.08.2026).
     *
     * <p>Der Protokolleintrag heisst dabei {@code TERMIN_ABGESAGT} und nicht
     * {@code TERMIN_GEAENDERT}: Wer das Protokoll nach abgesagten Terminen durchsieht, soll
     * sie dort finden und nicht zwischen Ortsaenderungen suchen.
     */
    @Test
    void aendernKannDenTerminAbsagen() throws Exception {
        Long terminId = angelegterTermin(tag(30));

        aendern(Map.of("terminId", terminId, "version", version(terminId), "status", "ABGESAGT"))
                .andExpect(status().isNoContent());

        assertThat(terminZeile(terminId).get("status")).isEqualTo("ABGESAGT");
        assertThat(jdbc.queryForList("""
                SELECT aktion FROM profil.audit_log
                 WHERE entitaet = 'termin' AND entitaet_id = ? ORDER BY id
                """, String.class, terminId))
                .containsExactly("TERMIN_ANGELEGT", "TERMIN_ABGESAGT");
    }

    /** Auch der Abschluss laesst sich von Hand setzen - etwa, wenn frueher gespielt wurde. */
    @Test
    void aendernKannDenTerminAbschliessen() throws Exception {
        Long terminId = angelegterTermin(tag(31));

        aendern(Map.of("terminId", terminId, "version", version(terminId),
                "status", "ABGESCHLOSSEN"))
                .andExpect(status().isNoContent());

        assertThat(terminZeile(terminId).get("status")).isEqualTo("ABGESCHLOSSEN");
    }

    /**
     * Zurueck nach {@code GEPLANT} geht nicht - eine Absage ist endgueltig.
     *
     * <p>{@code 400} und nicht {@code 409}: Es ist kein Zustand, der sich mit der Zeit
     * aendert, sondern eine Eingabe, die es nie geben wird. Wer den Termin doch braucht,
     * entfernt ihn und legt ihn neu an.
     */
    @Test
    void aendernAufGeplantLiefert400() throws Exception {
        Long terminId = angelegterTermin(tag(32));

        aendern(Map.of("terminId", terminId, "version", version(terminId), "status", "GEPLANT"))
                .andExpect(status().isBadRequest());

        assertThat(terminZeile(terminId).get("status")).isEqualTo("GEPLANT");
    }

    // --------------------------------------------------------------------- Entfernen (A19)

    /** Ohne Verweise verschwindet die Zeile - und der Zeitpunkt wird wieder frei. */
    @Test
    void entfernenLoeschtDenTerminUndGibtDenZeitpunktFrei() throws Exception {
        LocalDate datum = tag(33);
        Long terminId = angelegterTermin(datum);

        entfernen(terminId).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM spieltag.termin WHERE id = ?", Integer.class, terminId))
                .isZero();
        anlegen(datum, "Sporthalle Nord")
                .andExpect(status().isCreated());
    }

    /**
     * Ein abgesagter Termin laesst sich entfernen - das ist der einzige Weg zurueck.
     *
     * <p>Er belegt seinen Zeitpunkt sonst dauerhaft weiter, denn {@code uq_termin_zeit} gilt
     * fuer ihn genauso. Absagen ist endgueltig; entfernen und neu anlegen ist die Heilung.
     */
    @Test
    void einAbgesagterTerminLaesstSichEntfernen() throws Exception {
        Long terminId = angelegterTermin(tag(34));
        absagen(terminId).andExpect(status().isNoContent());

        entfernen(terminId).andExpect(status().isNoContent());
    }

    /**
     * Mit Rueckmeldungen daran wird nicht geloescht, sondern abgesagt.
     *
     * <p>Fuenf Tabellen haengen mit {@code ON DELETE CASCADE} am Termin; ein ungepruefter
     * Aufruf raeumte den halben Spieltag ab, und die Rueckmeldungen sind der einzige Beleg
     * dafuer, wer zugesagt hatte.
     */
    @Test
    void entfernenMitTeilnahmeLiefert409() throws Exception {
        Long terminId = angelegterTermin(tag(35));
        teilnahmeAnlegen(terminId);

        String antwort = entfernen(terminId)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(karte(antwort).get("code")).isEqualTo("TERMIN_IN_VERWENDUNG");
        assertThat(terminZeile(terminId)).isNotNull();
    }

    /** Eine unbekannte Id liefert {@code 404}. */
    @Test
    void entfernenUnbekannterIdLiefert404() throws Exception {
        entfernen(999999999L).andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------- Gast-Stufe (A17)

    /**
     * Der Admin korrigiert die Selbsteinschaetzung eines Gastes - und nur sie.
     *
     * <p>Weder Zusage noch Meldezeit aendern sich; die Position in der Warteschlange bleibt.
     * Der Vorgang zaehlt aber als Teilnehmeraenderung (A15).
     */
    @Test
    void gastStufeAendernSetztNurDieStufe() throws Exception {
        Long terminId = angelegterTermin(tag(36));
        gastTeilnahmeAnlegen(terminId, "Testgast Klara", "SCHWACH");
        int vorher = teilnehmerVersion(terminId);

        gastStufe(terminId, "Testgast Klara", "STARK").andExpect(status().isNoContent());

        Map<String, Object> zeile = jdbc.queryForMap(
                "SELECT gast_stufe, zusage FROM spieltag.teilnahme WHERE termin_id = ?", terminId);
        assertThat(zeile.get("gast_stufe")).isEqualTo("STARK");
        assertThat(zeile.get("zusage")).as("Die Zusage bleibt unberuehrt").isEqualTo(true);
        assertThat(teilnehmerVersion(terminId)).isEqualTo(vorher + 1);
    }

    /** Das Protokoll haelt alten und neuen Wert fest. */
    @Test
    void gastStufeAendernProtokolliertBeideWerte() throws Exception {
        Long terminId = angelegterTermin(tag(37));
        gastTeilnahmeAnlegen(terminId, "Testgast Lena", "MITTEL");

        gastStufe(terminId, "Testgast Lena", "SCHWACH").andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("""
                SELECT details->>'stufeAlt' FROM profil.audit_log
                 WHERE aktion = 'GAST_STUFE_GEAENDERT' AND entitaet_id = ?
                """, String.class, terminId))
                .isEqualTo("MITTEL");
    }

    /** Ohne passende Teilnahme gibt es nichts zu korrigieren. */
    @Test
    void gastStufeOhnePassendeTeilnahmeLiefert404() throws Exception {
        Long terminId = angelegterTermin(tag(38));

        gastStufe(terminId, "Testgast Mia", "STARK").andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------- Auto-Abschluss (A18)

    /**
     * Ein geplanter Termin schliesst sich 30 Minuten nach seinem Beginn selbst ab (A18).
     *
     * <p><b>Der Auftrag wird hier direkt aufgerufen und nicht abgewartet.</b> Er laeuft im
     * Betrieb alle fuenf Minuten; ein Test, der darauf wartet, waere langsam und von der
     * Uhr abhaengig. Geprueft wird die Wirkung, nicht der Zeitplan.
     *
     * <p>Der Termin liegt einen Tag zurueck - damit ist der Fall unabhaengig davon, zu
     * welcher Tageszeit der Testlauf stattfindet.
     */
    @Test
    void abgelaufeneTermineWerdenAbgeschlossen() throws Exception {
        Long vergangen = jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit) VALUES (?, ?) RETURNING id
                """, Long.class, LocalDate.now().minusDays(1), UHRZEIT);
        Long kuenftig = angelegterTermin(tag(39));

        terminService.abgelaufeneAbschliessen();

        assertThat(terminZeile(vergangen).get("status")).isEqualTo("ABGESCHLOSSEN");
        assertThat(terminZeile(kuenftig).get("status"))
                .as("Was noch kommt, bleibt geplant")
                .isEqualTo("GEPLANT");
    }

    /**
     * Ein abgesagter Termin wird nicht abgeschlossen - er hat nicht stattgefunden.
     *
     * <p>Und der Zaehler {@code teilnehmer_version} bleibt unberuehrt: Der Abschluss aendert
     * den Teilnehmerkreis nicht. Ein Ausschlag setzte ab S5 grundlos Generierungskontingente
     * zurueck.
     */
    @Test
    void einAbgesagterTerminBleibtAbgesagt() throws Exception {
        Long terminId = jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit, status)
                VALUES (?, ?, 'ABGESAGT') RETURNING id
                """, Long.class, LocalDate.now().minusDays(2), UHRZEIT);

        terminService.abgelaufeneAbschliessen();

        assertThat(terminZeile(terminId).get("status")).isEqualTo("ABGESAGT");
        assertThat(teilnehmerVersion(terminId)).isZero();
    }

    // --------------------------------------------------------------------- Serie

    /**
     * Der Erfolgsfall: vier Termine im richtigen Wochentakt, der erste am Startdatum.
     *
     * <p><b>{@code nextOrSame} und nicht {@code next}:</b> Das Startdatum faellt hier selbst
     * auf den gewuenschten Wochentag und gehoert deshalb dazu. Mit {@code next} fehlte die
     * erste Woche - ein Fehler, der erst beim Nachzaehlen auffiele.
     */
    @Test
    void serieErzeugtIhreTermineAbDemStartdatum() throws Exception {
        LocalDate start = tag(20);

        Map<String, Object> antwort = serieErfolgreich(start, start.plusWeeks(3),
                start.getDayOfWeek().getValue());

        List<Map<String, Object>> angelegt = termineAus(antwort, "angelegteTermine");
        assertThat(angelegt).hasSize(4);
        assertThat(angelegt.getFirst().get("datum")).isEqualTo(start.toString());
        assertThat(antwort.get("uebersprungeneTermine")).isEqualTo(List.of());

        Long serieId = ((Number) antwort.get("serieId")).longValue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM spieltag.termin WHERE serie_id = ?", Integer.class, serieId))
                .isEqualTo(4);
    }

    /**
     * Ein belegter Zeitpunkt laesst die Serie nicht scheitern - er wird uebersprungen und
     * namentlich gemeldet (Weggabelung A).
     */
    @Test
    void serieUeberspringtBelegteZeitpunkteUndMeldetSie() throws Exception {
        LocalDate start = tag(40);
        LocalDate kollision = start.plusWeeks(1);
        angelegterTermin(kollision);

        Map<String, Object> antwort = serieErfolgreich(start, start.plusWeeks(3),
                start.getDayOfWeek().getValue());

        assertThat(termineAus(antwort, "angelegteTermine")).hasSize(3);
        assertThat(antwort.get("uebersprungeneTermine")).isEqualTo(List.of(kollision.toString()));
    }

    /**
     * Ueber der Obergrenze von 52 Terminen bricht der Vorgang ab - mit der errechneten Zahl
     * in der Meldung.
     *
     * <p>Der Riegel gilt einem Tippfehler im Enddatum: "2036" statt "2026" legte ueber
     * fuenfhundert Termine an, die einzeln wieder abzusagen waeren.
     */
    @Test
    void serieUeberDerObergrenzeLiefert400() throws Exception {
        LocalDate start = tag(60);

        String antwort = serie(start, start.plusWeeks(60), start.getDayOfWeek().getValue())
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> problem = karte(antwort);
        assertThat(problem.get("code")).isEqualTo("EINGABE_UNGUELTIG");
        assertThat(problem.get("detail").toString()).contains("61").contains("52");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spieltag.terminserie", Integer.class))
                .as("Erst rechnen, dann schreiben - sonst bliebe eine Serie ohne Termine zurueck")
                .isZero();
    }

    /**
     * {@code enddatum > startdatum} ist strikt: Eine Serie mit nur einem Termin gibt es
     * nicht, dafuer ist {@code /admin/termin/anlegen} da.
     */
    @Test
    void serieOhneZeitraumLiefert400() throws Exception {
        LocalDate start = tag(80);

        serie(start, start, start.getDayOfWeek().getValue())
                .andExpect(status().isBadRequest());
    }

    /** Faellt kein einziger passender Wochentag in den Zeitraum, entsteht keine leere Serie. */
    @Test
    void serieOhnePassendenWochentagLiefert400() throws Exception {
        LocalDate start = tag(90);

        serie(start, start.plusDays(1), start.plusDays(3).getDayOfWeek().getValue())
                .andExpect(status().isBadRequest());
    }

    /** Auch eine Serie in der Vergangenheit wird abgelehnt - dieselbe Regel wie beim Einzeltermin. */
    @Test
    void serieInDerVergangenheitLiefert400() throws Exception {
        LocalDate start = LocalDate.now().minusWeeks(5);

        serie(start, start.plusWeeks(3), start.getDayOfWeek().getValue())
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------- Protokoll

    /**
     * Die drei Adminvorgaenge stehen im Audit-Log, die Aenderung mit altem und neuem Wert.
     *
     * <p>{@code details} wird als echtes JSON-Objekt geschrieben, nicht als Text, der wie
     * JSON aussieht - deshalb greift der Pfadausdruck. Der Fallstrick hat am 29.08.2026
     * schon einmal zugeschlagen.
     */
    @Test
    void adminvorgaengeStehenImAuditLog() throws Exception {
        Long terminId = angelegterTermin(tag(15), "Sporthalle Nord");

        aendern(Map.of("terminId", terminId, "version", version(terminId), "ort", "Sporthalle Sued"))
                .andExpect(status().isNoContent());
        absagen(terminId).andExpect(status().isNoContent());

        List<String> aktionen = jdbc.queryForList("""
                SELECT aktion FROM profil.audit_log
                 WHERE entitaet = 'termin' AND entitaet_id = ?
                 ORDER BY id
                """, String.class, terminId);

        assertThat(aktionen).containsExactly("TERMIN_ANGELEGT", "TERMIN_GEAENDERT", "TERMIN_ABGESAGT");

        assertThat(jdbc.queryForObject("""
                SELECT details->'ort'->>'alt' FROM profil.audit_log
                 WHERE entitaet = 'termin' AND entitaet_id = ? AND aktion = 'TERMIN_GEAENDERT'
                """, String.class, terminId))
                .isEqualTo("Sporthalle Nord");
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /** Datum dieser Klasse mit einem Versatz in Tagen; haelt Abstand zu den anderen Klassen. */
    private static LocalDate tag(int versatz) {
        return LocalDate.now().plusDays(BASIS_TAGE + versatz);
    }

    private ResultActions anlegen(LocalDate datum, String ort) throws Exception {
        Map<String, Object> koerper = new LinkedHashMap<>();
        koerper.put("datum", datum.toString());
        koerper.put("uhrzeit", UHRZEIT.toString());
        koerper.put("ort", ort);
        return mockMvc.perform(post("/api/v1/admin/termin/anlegen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(koerper)));
    }

    private Map<String, Object> anlegenErfolgreich(LocalDate datum, String ort) throws Exception {
        String antwort = anlegen(datum, ort)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return karte(antwort);
    }

    /** Legt einen Termin ueber den Endpunkt an und liefert seine Id. */
    private Long angelegterTermin(LocalDate datum) throws Exception {
        return angelegterTermin(datum, null);
    }

    private Long angelegterTermin(LocalDate datum, String ort) throws Exception {
        return ((Number) anlegenErfolgreich(datum, ort).get("terminId")).longValue();
    }

    private ResultActions aendern(Map<String, Object> koerper) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/termin/aendern")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(koerper)));
    }

    private ResultActions absagen(Long terminId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/termin/absagen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d}".formatted(terminId)));
    }

    private ResultActions serie(LocalDate start, LocalDate ende, int wochentag) throws Exception {
        Map<String, Object> koerper = new LinkedHashMap<>();
        koerper.put("titel", "Testserie");
        koerper.put("wochentag", wochentag);
        koerper.put("uhrzeit", UHRZEIT.toString());
        koerper.put("startdatum", start.toString());
        koerper.put("enddatum", ende.toString());
        koerper.put("ort", "Sporthalle Nord");
        return mockMvc.perform(post("/api/v1/admin/serie/anlegen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(koerper)));
    }

    private Map<String, Object> serieErfolgreich(LocalDate start, LocalDate ende, int wochentag)
            throws Exception {
        String antwort = serie(start, ende, wochentag)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return karte(antwort);
    }

    /**
     * Liest den aktuellen Stand ueber den Leseendpunkt.
     *
     * <p>Ueber den Endpunkt und nicht per SQL: Genau diesen Weg schreibt der Vertrag vor -
     * lesen, aendern, mit der gelesenen Version zurueckschicken.
     */
    private long version(Long terminId) throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/termine/%d/lesen".formatted(terminId))
                        .cookie(new Cookie(COOKIE, adminSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) karte(antwort).get("version")).longValue();
    }

    private ResultActions entfernen(Long terminId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/termin/entfernen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d}".formatted(terminId)));
    }

    private ResultActions gastStufe(Long terminId, String gastName, String stufe) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/teilnahme/gast-stufe")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d,\"gastName\":\"%s\",\"stufe\":\"%s\"}"
                        .formatted(terminId, gastName, stufe)));
    }

    /** Eine Zusage des ersten Demoprofils - genug, damit der Termin als verwendet gilt. */
    private void teilnahmeAnlegen(Long terminId) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage)
                VALUES (?, (SELECT id FROM profil.spieler
                             WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1), true)
                """, terminId);
    }

    private void gastTeilnahmeAnlegen(Long terminId, String gastName, String stufe) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, gast_name, gast_stufe, zusage)
                VALUES (?, ?, ?, true)
                """, terminId, gastName, stufe);
    }

    private int teilnehmerVersion(Long terminId) {
        return jdbc.queryForObject(
                "SELECT teilnehmer_version FROM spieltag.termin WHERE id = ?", Integer.class, terminId);
    }

    private Map<String, Object> terminZeile(Long terminId) {
        return jdbc.queryForMap("SELECT * FROM spieltag.termin WHERE id = ?", terminId);
    }

    private Map<String, Object> karte(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> termineAus(Map<String, Object> antwort, String feld) {
        return (List<Map<String, Object>>) antwort.get(feld);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject(
                "SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }
}
