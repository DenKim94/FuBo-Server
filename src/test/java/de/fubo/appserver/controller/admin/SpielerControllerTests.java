package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.service.profil.ProfilStammdatenCache;
import de.fubo.appserver.utils.TokenGenerator;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Spielerverwaltung: anlegen, entfernen, sperren und freigeben aus
 * {@code S2b_UMSETZUNG.md} Abschnitt 8 sowie lesen und bearbeiten aus {@code S3_UMSETZUNG.md}
 * Abschnitte 2 und 3.
 *
 * <p>Der Pruefgegenstand ist weniger der Datenbankzugriff als die Abgrenzung: was das
 * Entfernen darf und wo es abzulehnen hat, dass eine Sperre <i>sofort</i> wirkt und nicht erst
 * mit dem Ablauf der Sitzung, und dass beim Bearbeiten das Weglassen eines Feldes etwas
 * anderes bedeutet als ein leerer Wert.
 *
 * <p>Die angelegten Profile tragen einen eigenen Namensraum ("Pruefspieler"), damit sie nicht
 * mit den Demodaten kollidieren - deren Profile heissen "Beispielspieler n".
 *
 * <h2>Der Zwischenspeicher wird vor jedem Fall verworfen - das ist zwingend</h2>
 * {@link ProfilStammdatenCache} ist ein Singleton und haelt seine Daten im Arbeitsspeicher;
 * eine Test-Transaktion rollt die <b>nicht</b> zurueck. Ohne das Verwerfen entstuende ein
 * besonders unangenehmer Fehler: Ein Fall legt "Pruefspieler A" an und liest die Uebersicht,
 * der Speicher fuellt sich mit diesem Profil, die Transaktion rollt zurueck - und der
 * naechste Fall saehe ein Profil, das es in der Datenbank nicht gibt. Dieselbe Ueberlegung
 * wie bei {@code bruteForceService.alleZuruecksetzen()} in {@code AdminControllerTests}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SpielerControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    /**
     * Uhrzeit der Termine, die diese Klasse fuer den Zaehler-Nachtrag anlegt.
     *
     * <p>{@code uq_termin_zeit} ist global: Diese Klasse teilt sich die Datenbank mit
     * {@code TerminControllerTests} (18:15), {@code TerminVerwaltungControllerTests} (19:45)
     * und {@code TeilnehmerlisteTests} (17:30) und braucht deshalb eine eigene Uhrzeit.
     */
    private static final LocalTime TERMIN_UHRZEIT = LocalTime.of(16, 5);

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

        // Siehe Klassen-JavaDoc: Der Speicher ueberlebt die Test-Transaktion.
        profilStammdatenCache.verwerfen();
    }

    // -------------------------------------------------------------------------- Lesen

    /**
     * Die Uebersicht enthaelt aktive <b>und</b> gesperrte Profile - der Unterschied zur
     * Namensliste und einer der drei Gruende fuer diesen Endpunkt.
     *
     * <p>Ohne die gesperrten koennte der Admin ein versehentlich gesperrtes Profil nicht
     * wiederfinden, um es freizugeben; genau dafuer kennt {@code blockieren} beide
     * Richtungen.
     */
    @Test
    void uebersichtEnthaeltAktiveUndGesperrteProfile() throws Exception {
        Long gesperrt = ersterSpieler();
        blockieren(gesperrt, true).andExpect(status().isNoContent());

        String antwort = uebersicht();

        assertThat(antwort)
                .as("Das gesperrte Profil muss enthalten sein")
                .contains("\"spielerId\":%d".formatted(gesperrt))
                .contains("\"aktiv\":false")
                .contains("\"aktiv\":true");
    }

    /**
     * Je Profil kommen die Skillwerte der aktiven Kategorien mit - der erste Endpunkt dieser
     * API, der Skillwerte ausliefert (A12). Erlaubt ist das nur unterhalb von
     * {@code /admin/}.
     */
    @Test
    void uebersichtLiefertDieSkillwerte() throws Exception {
        anlegen(vollstaendig("Pruefspieler L", Map.of("TORWART", 2)))
                .andExpect(status().isCreated());

        String antwort = uebersicht();

        Map<String, Object> skills = skillsVon(antwort, "Pruefspieler L");

        assertThat(skills).as("fuenf aktive Kategorien").hasSize(5);
        assertThat(skills).containsEntry("TORWART", 2);
    }

    /**
     * Das Adminprofil steht in dieser Liste, erkennbar an {@code rolle}, und zwar am Ende.
     *
     * <p>Kein Widerspruch zur Regel "das Adminprofil ist ein technisches Konto": Die Regel
     * gilt Abfragen, die <b>Mitspieler</b> aufzaehlen. Eine Profilverwaltung zaehlt den
     * Datenbestand auf - ohne die Zeile saehe der Admin 30 Profile, waehrend die Datenbank 31
     * enthaelt, und die Differenz waere nirgends erklaert.
     *
     * <p>{@code ORDER BY s.rolle DESC} stellt es ans Ende: {@code 'USER'} steht alphabetisch
     * nach {@code 'ADMIN'}.
     */
    @Test
    void uebersichtEnthaeltDasAdminprofilAmEnde() throws Exception {
        String antwort = uebersicht();

        List<Map<String, Object>> liste = alsListe(antwort);

        assertThat(liste).anyMatch(zeile -> "ADMIN".equals(zeile.get("rolle")));
        assertThat(liste.getLast().get("rolle"))
                .as("Das technische Konto steht hinter allen Spielerprofilen")
                .isEqualTo("ADMIN");
    }

    /**
     * Ein Profil ohne Zeile in einer Kategorie liefert eine kuerzere Karte - offener Punkt 20
     * aus S2 wird hier sichtbar.
     *
     * <p>Der Client erkennt die Luecke, indem er die Karte gegen {@code /admin/skills/lesen}
     * haelt; ein eigenes Feld im Vertrag braucht es dafuer nicht.
     */
    @Test
    void ungepflegtesProfilLiefertEineKuerzereSkillkarte() throws Exception {
        anlegen(vollstaendig("Pruefspieler M")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler M");

        jdbc.update("DELETE FROM profil.spieler_skill WHERE spieler_id = ? AND kategorie = 'TORWART'", id);
        profilStammdatenCache.verwerfen();

        String antwort = uebersicht();

        Map<String, Object> skills = skillsVon(antwort, "Pruefspieler M");

        assertThat(skills).as("vier statt fuenf Kategorien").hasSize(4);
        assertThat(skills).doesNotContainKey("TORWART");
    }

    /**
     * <b>Der Belegtstatus wird nicht zwischengespeichert.</b> Das ist die Abweichung von
     * Abschnitt 2.3 der Anleitung und der Grund dafuer.
     *
     * <p>Der Fall liest die Uebersicht zweimal, ohne dazwischen ein Profil zu aendern - der
     * Zwischenspeicher wird also <i>nicht</i> verworfen. Trotzdem muss die zweite Antwort die
     * inzwischen angelegte Sitzung zeigen. Waere der Belegtstatus mitgespeichert, bliebe er
     * bis zur naechsten Profilaenderung falsch, und genau die Eigenschaft ginge verloren,
     * wegen der er ueberhaupt abgeleitet und nicht gespeichert wird (A6): dass er nicht
     * veralten kann.
     */
    @Test
    void belegtstatusWirdNichtZwischengespeichert() throws Exception {
        Long spieler = ersterSpieler();

        assertThat(belegtVon(uebersicht(), spieler))
                .as("noch keine Sitzung auf diesem Profil")
                .isFalse();

        sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spieler, Rolle.USER);

        assertThat(belegtVon(uebersicht(), spieler))
                .as("ohne Verwerfen des Zwischenspeichers muss der Status trotzdem stimmen")
                .isTrue();
    }

    /**
     * Gegenprobe zum Fall darueber: Die Stammdaten <i>werden</i> zwischengespeichert, und
     * jede Aenderung verwirft ihn. Ohne das Verwerfen saehe der Admin nach dem Speichern
     * seine eigenen alten Werte.
     */
    @Test
    void aenderungVerwirftDenZwischenspeicher() throws Exception {
        String vorher = uebersicht();
        assertThat(vorher).doesNotContain("Pruefspieler N");

        anlegen(vollstaendig("Pruefspieler N")).andExpect(status().isCreated());

        assertThat(uebersicht())
                .as("Das Anlegen muss den Speicher verworfen haben")
                .contains("Pruefspieler N");
    }

    // --------------------------------------------------------------------- Bearbeiten

    /** Umbenennen wirkt in der Uebersicht und in der Namensauswahl. */
    @Test
    void bearbeitenAendertDenNamen() throws Exception {
        anlegen(vollstaendig("Pruefspieler B1")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler B1");

        bearbeiten("{\"spielerId\":%d,\"name\":\"Pruefspieler B2\"}".formatted(id))
                .andExpect(status().isNoContent());

        assertThat(uebersicht()).contains("Pruefspieler B2").doesNotContain("Pruefspieler B1");
        assertThat(namensliste()).contains("Pruefspieler B2");
    }

    /**
     * Eine Teilmenge setzt genau diese Kategorien - die uebrigen bleiben unberuehrt. Ohne
     * diese Eigenschaft muesste das Formular immer alle fuenf Werte senden.
     */
    @Test
    void bearbeitenSetztNurDieGenanntenKategorien() throws Exception {
        anlegen(vollstaendig("Pruefspieler B3")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler B3");
        int angriffVorher = skillwert(id, "ANGRIFF");

        bearbeiten("{\"spielerId\":%d,\"skills\":{\"TORWART\":3}}".formatted(id))
                .andExpect(status().isNoContent());

        assertThat(skillwert(id, "TORWART")).isEqualTo(3);
        assertThat(skillwert(id, "ANGRIFF"))
                .as("nicht genannte Kategorien bleiben")
                .isEqualTo(angriffVorher);
    }

    /**
     * <b>Eine leere Skillkarte loescht nichts.</b> Ein Loeschen von Skillzeilen ist in dieser
     * API nicht vorgesehen - der Teamgenerator braucht vollstaendige Werte.
     */
    @Test
    void leereSkillkarteLoeschtNichts() throws Exception {
        anlegen(vollstaendig("Pruefspieler B4")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler B4");

        bearbeiten("{\"spielerId\":%d,\"name\":\"Pruefspieler B5\",\"skills\":{}}".formatted(id))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler_skill WHERE spieler_id = ?", Integer.class, id))
                .as("alle fuenf Zeilen stehen noch")
                .isEqualTo(5);
    }

    /**
     * Ein Aufruf ohne jede Angabe wird abgelehnt - auch mit leerer Skillkarte. Er taete
     * nichts, hinterliesse aber einen Protokolleintrag.
     */
    @Test
    void aufrufOhneAenderungLiefert400() throws Exception {
        Long id = ersterSpieler();

        String antwort = bearbeiten("{\"spielerId\":%d}".formatted(id))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"");

        bearbeiten("{\"spielerId\":%d,\"skills\":{}}".formatted(id))
                .andExpect(status().isBadRequest());
    }

    /**
     * Ein angegebener, aber leerer Name ist eine Eingabe und kein Weglassen - sonst
     * ueberschriebe er den Namen mit einer leeren Zeichenkette.
     */
    @Test
    void leererNameLiefert400() throws Exception {
        bearbeiten("{\"spielerId\":%d,\"name\":\"   \"}".formatted(ersterSpieler()))
                .andExpect(status().isBadRequest());
    }

    /** Die Meldung nennt die zulaessigen Schluessel - der Trigger allein braechte einen 500. */
    @Test
    void unbekannteKategorieLiefert400MitDenZulaessigenSchluesseln() throws Exception {
        String antwort = bearbeiten(
                "{\"spielerId\":%d,\"skills\":{\"FLUGKOPFBALL\":3}}".formatted(ersterSpieler()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("FLUGKOPFBALL").contains("TORWART").contains("ANGRIFF");
    }

    /**
     * Der Torwart-Bereich (0 bis 3) ist kein Sonderfall im Code - er kommt aus
     * {@code profil.skill_kategorie}. Die Meldung nennt Kategorie und Bereich.
     */
    @Test
    void torwartUeberDreiLiefert400MitDemBereich() throws Exception {
        String antwort = bearbeiten(
                "{\"spielerId\":%d,\"skills\":{\"TORWART\":4}}".formatted(ersterSpieler()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("TORWART").contains("0").contains("3");
    }

    /** Der Name eines anderen Profils ist belegt. */
    @Test
    void fremderNameLiefert409() throws Exception {
        anlegen(vollstaendig("Pruefspieler B6")).andExpect(status().isCreated());
        anlegen(vollstaendig("Pruefspieler B7")).andExpect(status().isCreated());

        String antwort = bearbeiten("{\"spielerId\":%d,\"name\":\"Pruefspieler B7\"}"
                .formatted(spielerId("Pruefspieler B6")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"NAME_BELEGT\"");
    }

    /**
     * <b>Der eigene Datensatz zaehlt nicht als Kollision.</b> Ohne diese Ausnahme scheiterte
     * jede Korrektur der Schreibweise am eigenen Namen - {@code existsByNameIgnoreCase}
     * traefe die Zeile, die gerade geaendert wird.
     */
    @Test
    void eigenerNameInAndererSchreibweiseIstErlaubt() throws Exception {
        anlegen(vollstaendig("Pruefspieler B8")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler B8");

        bearbeiten("{\"spielerId\":%d,\"name\":\"pruefspieler b8\"}".formatted(id))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT name FROM profil.spieler WHERE id = ?", String.class, id))
                .isEqualTo("pruefspieler b8");
    }

    /**
     * Das Adminprofil wird hier <b>vollstaendig</b> abgelehnt - Name wie Skillwerte
     * (Entscheidung vom 29.08.2026). Sein Name ist zugleich der Anmeldename und wird ueber
     * {@code /admin/name/aendern} geaendert.
     */
    @Test
    void adminprofilBearbeitenLiefert409() throws Exception {
        Long admin = adminSpielerId();

        assertThat(bearbeiten("{\"spielerId\":%d,\"name\":\"Neuer Adminname\"}".formatted(admin))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString())
                .contains("\"code\":\"PROFIL_GESCHUETZT\"");

        bearbeiten("{\"spielerId\":%d,\"skills\":{\"ANGRIFF\":4}}".formatted(admin))
                .andExpect(status().isConflict());
    }

    /** Eine Id, die es nicht gibt. */
    @Test
    void bearbeitenMitUnbekannterIdLiefert404() throws Exception {
        bearbeiten("{\"spielerId\":999999,\"name\":\"Pruefspieler B9\"}")
                .andExpect(status().isNotFound());
    }

    /**
     * Der Protokolleintrag nennt alten und neuen Namen sowie die gesetzten Skillwerte -
     * <b>nicht</b> die vorherigen. Der Eintrag beantwortet "wer hat wann was geaendert",
     * nicht "wie war es vorher".
     */
    @Test
    void bearbeitenWirdProtokolliert() throws Exception {
        anlegen(vollstaendig("Pruefspieler C1")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler C1");

        bearbeiten("{\"spielerId\":%d,\"name\":\"Pruefspieler C2\",\"skills\":{\"TORWART\":1}}"
                .formatted(id)).andExpect(status().isNoContent());

        Map<String, Object> eintrag = jdbc.queryForMap("""
                SELECT details->>'nameAlt' AS name_alt,
                       details->>'nameNeu' AS name_neu,
                       details->'skills'->>'TORWART' AS torwart
                  FROM profil.audit_log
                 WHERE aktion = 'PROFIL_GEAENDERT' AND entitaet_id = ?
                """, id);

        assertThat(eintrag.get("name_alt")).isEqualTo("Pruefspieler C1");
        assertThat(eintrag.get("name_neu")).isEqualTo("Pruefspieler C2");
        assertThat(eintrag.get("torwart"))
                .as("Die Spalte details muss jsonb sein, nicht Text")
                .isEqualTo("1");
    }

    // ----------------------------------------------------------------------- Anlegen

    /** Mit vollstaendigen Werten entsteht ein aktives Profil der Rolle USER. */
    @Test
    void anlegenMitVollstaendigenSkillsLegtDasProfilAn() throws Exception {
        String antwort = anlegen(vollstaendig("Pruefspieler A", Map.of("ANGRIFF", 5)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"name\":\"Pruefspieler A\"").contains("\"spielerId\":");

        Long id = spielerId("Pruefspieler A");
        Long anzahl = jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler_skill WHERE spieler_id = ?", Long.class, id);

        assertThat(anzahl).as("ein Wert je aktiver Kategorie").isEqualTo(5L);
        assertThat(skillwert(id, "ANGRIFF")).isEqualTo(5);

        Map<String, Object> profil = jdbc.queryForMap(
                "SELECT rolle, aktiv FROM profil.spieler WHERE id = ?", id);
        assertThat(profil.get("rolle")).isEqualTo(Rolle.USER.name());
        assertThat(profil.get("aktiv")).isEqualTo(true);
    }

    /**
     * Der Fall aus der Vorgabe vom 30.08.2026: zwei von fuenf Kategorien.
     *
     * <p>Bis dahin entstand daraus ein Profil, dessen uebrige Werte die Vorgabe der Stufe
     * {@code MITTEL} trugen - eine Behauptung ueber einen Spieler, die niemand aufgestellt hat.
     * Die Meldung nennt die fehlenden Schluessel, damit der Aufrufer nicht raten muss.
     */
    @Test
    void unvollstaendigeSkillsLiefern400() throws Exception {
        String antwort = anlegen("{\"name\":\"Pruefspieler B\",\"skills\":{\"ANGRIFF\":4,\"SPIELSTAERKE\":2}}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"")
                .contains("Unvollständige Eingabe")
                .contains("LAUFSTAERKE")
                .contains("TORWART")
                .contains("VERTEIDIGUNG");

        assertThat(existiert("Pruefspieler B")).as("kein halbes Profil zurueck").isFalse();
    }

    /**
     * Fehlt das Feld ganz, gilt dasselbe - es gibt keine Vorgabewerte mehr.
     *
     * <p><b>Der Koerper steht hier absichtlich woertlich da und nicht ueber
     * {@link #vollstaendig(String)}.</b> Genau das ist der Pruefgegenstand: ein Aufruf ohne
     * {@code skills}. Wer ihn beim naechsten Umbau auf den Helfer umstellt, dreht die Aussage
     * des Falls um - er wuerde dann {@code 201} liefern und trotzdem gruen aussehen, weil der
     * Name des Tests nichts erzwingt.
     */
    @Test
    void anlegenOhneSkillsLiefert400() throws Exception {
        String antwort = anlegen("{\"name\":\"Pruefspieler B0\"}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("Unvollständige Eingabe");
        assertThat(existiert("Pruefspieler B0")).isFalse();
    }

    /** Und eine leere Karte ebenso: Sie ist eine Angabe, nur eben keine vollstaendige. */
    @Test
    void anlegenMitLeererSkillkarteLiefert400() throws Exception {
        anlegen("{\"name\":\"Pruefspieler B00\",\"skills\":{}}")
                .andExpect(status().isBadRequest());

        assertThat(existiert("Pruefspieler B00")).isFalse();
    }

    /**
     * Gemessen wird an den <b>aktiven</b> Kategorien, nicht an einer festen Zahl.
     *
     * <p>Wird eine Kategorie abgeschaltet, faellt sie aus der Pflicht - sonst liesse sich nach
     * dem Abschalten kein Profil mehr anlegen, und der Fehler waere von einem Tippfehler nicht
     * zu unterscheiden.
     */
    @Test
    void abgeschalteteKategorieGehoertNichtZurPflicht() throws Exception {
        jdbc.update("UPDATE profil.skill_kategorie SET aktiv = false WHERE schluessel = 'TORWART'");

        anlegen("{\"name\":\"Pruefspieler B000\",\"skills\":"
                + "{\"ANGRIFF\":3,\"VERTEIDIGUNG\":3,\"SPIELSTAERKE\":3,\"LAUFSTAERKE\":3}}")
                .andExpect(status().isCreated());

        Long anzahl = jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler_skill WHERE spieler_id = ?",
                Long.class, spielerId("Pruefspieler B000"));
        assertThat(anzahl).as("vier statt fuenf").isEqualTo(4L);
    }

    /** Kleinschreibung ist zulaessig - ein 400 dafuer waere Schikane ohne Sicherheitsgewinn. */
    @Test
    void kategorienDuerfenKleingeschriebenWerden() throws Exception {
        anlegen("{\"name\":\"Pruefspieler C\",\"skills\":"
                + "{\"angriff\":6,\"verteidigung\":3,\"spielstaerke\":3,"
                + "\"laufstaerke\":3,\"torwart\":1}}")
                .andExpect(status().isCreated());

        assertThat(skillwert(spielerId("Pruefspieler C"), "ANGRIFF")).isEqualTo(6);
    }

    /** Der Service prueft gegen profil.skill_kategorie und nennt die betroffene Kategorie. */
    @Test
    void unbekannteKategorieLiefert400() throws Exception {
        String antwort = anlegen("{\"name\":\"Pruefspieler D\",\"skills\":{\"KOPFBALL\":4}}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("KOPFBALL");
        assertThat(existiert("Pruefspieler D")).as("Erst pruefen, dann schreiben").isFalse();
    }

    /**
     * Jede Kategorie hat ihren eigenen Bereich: Torwart geht nur bis 3. Der Trigger in der
     * Datenbank bliebe die letzte Instanz, braechte aber einen 500 statt einer Meldung, die
     * die Kategorie nennt.
     */
    @Test
    void torwartWertUeberDreiLiefert400() throws Exception {
        String antwort = anlegen("{\"name\":\"Pruefspieler E\",\"skills\":{\"TORWART\":4}}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("TORWART").contains("0 bis 3");
        assertThat(existiert("Pruefspieler E")).isFalse();
    }

    /**
     * Der Name ist eindeutig - unabhaengig von Gross- und Kleinschreibung.
     *
     * <p><b>Der zweite Aufruf schickt bewusst gar keine Skillwerte und bekommt trotzdem
     * {@code 409} und nicht {@code 400}.</b> Seit dem 30.08.2026 sind die Werte Pflicht; dass
     * hier der Namenskonflikt gewinnt, liegt an der Reihenfolge im Dienst: Erst der Name, dann
     * die Skills. Das ist beabsichtigt - ein belegter Name ist das erste Hindernis, und ihn erst
     * nach einer vollstaendigen Skilleingabe zu melden waere unfreundlich. Wer die Reihenfolge
     * umdreht, bricht diesen Fall.
     */
    @Test
    void belegterNameLiefert409() throws Exception {
        anlegen(vollstaendig("Pruefspieler F")).andExpect(status().isCreated());

        String antwort = anlegen("{\"name\":\"pruefspieler f\"}")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"NAME_BELEGT\"");
    }

    // ---------------------------------------------------------------------- Entfernen

    /** Das Profil verschwindet, die Skillwerte gehen per ON DELETE CASCADE mit. */
    @Test
    void entfernenLoeschtProfilUndSkillwerte() throws Exception {
        anlegen(vollstaendig("Pruefspieler G")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler G");

        entfernen(id).andExpect(status().isNoContent());

        assertThat(existiert("Pruefspieler G")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler_skill WHERE spieler_id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM profil.audit_log WHERE aktion = 'PROFIL_ENTFERNT' AND entitaet_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    /**
     * Offene Sitzungen sind fluechtig und werden mit abgeraeumt - sonst scheiterte das
     * {@code DELETE} an {@code fk_session_spieler}.
     */
    @Test
    void entfernenRaeumtDieSitzungenDesProfilsMitAb() throws Exception {
        anlegen(vollstaendig("Pruefspieler H")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler H");
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, id, Rolle.USER);

        entfernen(id).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM profil.session WHERE token_hash = ?",
                Integer.class, TokenGenerator.hash(token))).isZero();
    }

    /**
     * Sobald ein Beleg auf das Profil verweist, bleibt es bestehen. Das Audit-Log genuegt
     * dafuer - ein Loeschen vernichtete Belege, auf die sich andere Datensaetze berufen.
     */
    @Test
    void entfernenEinesVerwendetenProfilsLiefert409() throws Exception {
        anlegen(vollstaendig("Pruefspieler I")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler I");

        jdbc.update("""
                INSERT INTO profil.audit_log (akteur_spieler_id, akteur_bezeichnung, aktion)
                     VALUES (?, 'pruef', 'ADMIN_ANGEMELDET')
                """, id);

        String antwort = entfernen(id)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"PROFIL_IN_VERWENDUNG\"");
        assertThat(existiert("Pruefspieler I")).isTrue();
    }

    /** Ohne Adminprofil kaeme niemand mehr in den Adminbereich. */
    @Test
    void adminprofilLaesstSichWederEntfernenNochSperren() throws Exception {
        String beimEntfernen = entfernen(adminSpielerId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(beimEntfernen).contains("\"code\":\"PROFIL_GESCHUETZT\"");

        String beimSperren = blockieren(adminSpielerId(), true)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        assertThat(beimSperren).contains("\"code\":\"PROFIL_GESCHUETZT\"");
    }

    /** Unbekannte Id: 404, nicht 400 - die Eingabeform war ja in Ordnung. */
    @Test
    void unbekanntesProfilLiefert404() throws Exception {
        entfernen(999_999L).andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------- Blockieren

    /**
     * Die Sperre wirkt sofort: Die offenen Sitzungen sind widerrufen, und der Name
     * verschwindet aus der Auswahl. Ohne den Widerruf bliebe der Gesperrte bis zum Ablauf
     * seiner Sitzung angemeldet - die Sperre wirkte gerade dann nicht, wenn sie gebraucht wird.
     */
    @Test
    void blockierenWiderruftDieSitzungenUndVerstecktDenNamen() throws Exception {
        anlegen(vollstaendig("Pruefspieler J")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler J");
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, id, Rolle.USER);

        blockieren(id, true).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT aktiv FROM profil.spieler WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT widerrufen_am IS NOT NULL FROM profil.session WHERE token_hash = ?",
                Boolean.class, TokenGenerator.hash(token))).isTrue();

        assertThat(namensliste()).doesNotContain("Pruefspieler J");
    }

    /**
     * Die Gegenrichtung. Ohne sie kaeme der Admin an ein versehentlich gesperrtes Profil bis
     * S3 nicht mehr heran.
     */
    @Test
    void freigebenMachtDasProfilWiederWaehlbar() throws Exception {
        anlegen(vollstaendig("Pruefspieler K")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler K");

        blockieren(id, true).andExpect(status().isNoContent());
        blockieren(id, false).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT aktiv FROM profil.spieler WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(namensliste()).contains("Pruefspieler K");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM profil.audit_log
                 WHERE aktion = 'PROFIL_FREIGEGEBEN' AND entitaet_id = ?
                """, Integer.class, id)).isEqualTo(1);
    }

    /** Wiederholbar: Zweimal sperren aendert nichts und ist trotzdem kein Fehler. */
    @Test
    void zweimalSperrenIstFolgenlos() throws Exception {
        anlegen(vollstaendig("Pruefspieler L")).andExpect(status().isCreated());
        Long id = spielerId("Pruefspieler L");

        blockieren(id, true).andExpect(status().isNoContent());
        blockieren(id, true).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT aktiv FROM profil.spieler WHERE id = ?", Boolean.class, id))
                .isFalse();
    }

    /**
     * Ein fehlendes Feld darf nicht stillschweigend als "freigeben" gelten - deshalb ist
     * {@code blockieren} ein {@code Boolean} und kein {@code boolean}.
     */
    @Test
    void fehlendeRichtungLiefert400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/user/blockieren")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.45")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spielerId\":1}"))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------- Berechtigung

    /** Der Adminbereich ist fuer Spieler geschlossen. */
    @Test
    void spielerDarfKeineProfileAnlegen() throws Exception {
        String token = sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, ersterSpieler(), Rolle.USER);

        mockMvc.perform(post("/api/v1/admin/user/anlegen")
                        .cookie(new Cookie(COOKIE, token))
                        .header("CF-Connecting-IP", "198.51.100.46")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pruefspieler M\"}"))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    // --------------------------------------------------------------------- Teilnehmer-Version

    /**
     * Eine Skillaenderung erhoeht die {@code teilnehmer_version} kuenftiger Termine (A15).
     *
     * <p><b>Der Pflichtpunkt aus S3</b>, Abschnitt 3.5: Dort stand der Vorgang mangels
     * {@code spieltag}-Dienst nur als Kommentar. Eine geaenderte Bewertung aendert nicht, wer
     * mitspielt, wohl aber die Grundlage jeder Teameinteilung - dieselben Namen ergeben eine
     * andere Aufteilung. Ohne diesen Ausloeser bliebe eine gespeicherte Einteilung ab S5 als
     * aktuell gekennzeichnet, obwohl sie es nicht mehr ist.
     *
     * <p>Der Termin liegt bewusst weit in der Zukunft und zu einer eigenen Uhrzeit:
     * {@code uq_termin_zeit} ist global, und diese Klasse teilt sich die Datenbank mit den
     * Terminklassen.
     */
    @Test
    void skillaenderungErhoehtDieTeilnehmerVersionKuenftigerTermine() throws Exception {
        Long spielerId = ersterSpieler();
        Long terminId = terminMitZusage(LocalDate.now().plusDays(300), spielerId);

        bearbeiten("{\"spielerId\":%d,\"skills\":{\"ANGRIFF\":5}}".formatted(spielerId))
                .andExpect(status().isNoContent());

        assertThat(teilnehmerVersion(terminId)).isEqualTo(1);
    }

    /**
     * Ein vergangener Termin bleibt unberuehrt - er ist gespielt worden.
     *
     * <p>Ebenso ein Termin, an dem der Spieler <b>abgesagt</b> hat: Er steht dort nicht in der
     * Einteilung, seine Staerke aendert daran nichts.
     */
    @Test
    void skillaenderungLaesstVergangeneUndAbgesagteTermineUnberuehrt() throws Exception {
        Long spielerId = ersterSpieler();
        Long vergangen = terminMitZusage(LocalDate.now().minusDays(300), spielerId);
        Long abgesagt = jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit) VALUES (?, ?) RETURNING id
                """, Long.class, LocalDate.now().plusDays(301), TERMIN_UHRZEIT);
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage) VALUES (?, ?, false)
                """, abgesagt, spielerId);

        bearbeiten("{\"spielerId\":%d,\"skills\":{\"ANGRIFF\":6}}".formatted(spielerId))
                .andExpect(status().isNoContent());

        assertThat(teilnehmerVersion(vergangen)).as("gespielt ist gespielt").isZero();
        assertThat(teilnehmerVersion(abgesagt)).as("wer abgesagt hat, spielt nicht mit").isZero();
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /** Legt einen Termin mit einer Zusage dieses Spielers an und liefert die Termin-Id. */
    private Long terminMitZusage(LocalDate datum, Long spielerId) {
        Long terminId = jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit) VALUES (?, ?) RETURNING id
                """, Long.class, datum, TERMIN_UHRZEIT);
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage) VALUES (?, ?, true)
                """, terminId, spielerId);
        return terminId;
    }

    private int teilnehmerVersion(Long terminId) {
        return jdbc.queryForObject(
                "SELECT teilnehmer_version FROM spieltag.termin WHERE id = ?", Integer.class, terminId);
    }

    private ResultActions bearbeiten(String koerper) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/user/bearbeiten")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.50")
                .contentType(MediaType.APPLICATION_JSON)
                .content(koerper));
    }

    /** Ruft die Adminuebersicht ab und liefert den rohen Antwortkoerper. */
    private String uebersicht() throws Exception {
        return mockMvc.perform(get("/api/v1/admin/user/lesen")
                        .cookie(new Cookie(COOKIE, adminSitzung())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Liest die Antwort als Liste von Karten.
     *
     * <p>Ueber Jackson und nicht ueber Zeichenkettenvergleiche: Ein {@code contains} auf dem
     * rohen JSON traefe auch Treffer in einer anderen Zeile und meldete eine falsche
     * Zuordnung als Erfolg.
     */
    private List<Map<String, Object>> alsListe(String antwort) throws Exception {
        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    /** Die Karte eines Profils, gesucht ueber seinen Namen. */
    private Map<String, Object> profilVon(String antwort, String name) throws Exception {
        return alsListe(antwort).stream()
                .filter(zeile -> name.equals(zeile.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein Profil '%s' in der Antwort.".formatted(name)));
    }

    /** Die Skillkarte eines Profils. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> skillsVon(String antwort, String name) throws Exception {
        return (Map<String, Object>) profilVon(antwort, name).get("skills");
    }

    /** Der Belegtstatus eines Profils, gesucht ueber seine Id. */
    private boolean belegtVon(String antwort, Long spielerId) throws Exception {
        return alsListe(antwort).stream()
                .filter(zeile -> spielerId.intValue() == ((Number) zeile.get("spielerId")).intValue())
                .findFirst()
                .map(zeile -> (Boolean) zeile.get("belegt"))
                .orElseThrow(() -> new AssertionError("Kein Profil mit der Id %d.".formatted(spielerId)));
    }

    /**
     * Baut einen vollstaendigen Anfragekoerper fuer das Anlegen.
     *
     * <p>Seit dem 30.08.2026 verlangt {@code /admin/user/anlegen} einen Wert je aktiver
     * Kategorie. Die meisten Faelle dieser Klasse brauchen nur <i>irgendein</i> Profil und
     * interessieren sich nicht fuer die Werte - fuer die ist dieser Helfer da.
     *
     * <p><b>Die Kategorien kommen aus der Datenbank, nicht aus einer Liste im Test.</b> Sie sind
     * datengetrieben; ein fest verdrahteter Helfer muesste bei jeder Datenaenderung mitgepflegt
     * werden und pruefte dann nur noch sich selbst. Die Werte liegen bewusst in der Mitte des
     * jeweiligen Bereichs - 3, fuer den engeren Torwart-Bereich 2.
     *
     * @param name         Anzeigename des Profils
     * @param abweichungen Kategorien, die einen bestimmten Wert bekommen sollen
     */
    private String vollstaendig(String name, Map<String, Integer> abweichungen) {
        String skills = jdbc.queryForList("""
                SELECT schluessel FROM profil.skill_kategorie WHERE aktiv ORDER BY reihenfolge
                """, String.class).stream()
                .map(schluessel -> "\"%s\":%d".formatted(schluessel,
                        abweichungen.getOrDefault(schluessel, "TORWART".equals(schluessel) ? 2 : 3)))
                .collect(Collectors.joining(","));

        return "{\"name\":\"%s\",\"skills\":{%s}}".formatted(name, skills);
    }

    /** Vollstaendiger Koerper ohne besondere Werte. */
    private String vollstaendig(String name) {
        return vollstaendig(name, Map.of());
    }

    private ResultActions anlegen(String koerper) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/user/anlegen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.47")
                .contentType(MediaType.APPLICATION_JSON)
                .content(koerper));
    }

    private ResultActions entfernen(Long id) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/user/entfernen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.48")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spielerId\":%d}".formatted(id)));
    }

    private ResultActions blockieren(Long id, boolean sperren) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/user/blockieren")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", "198.51.100.49")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spielerId\":%d,\"blockieren\":%s}".formatted(id, sperren)));
    }

    private String namensliste() throws Exception {
        return mockMvc.perform(get("/api/v1/auth/users/lesen")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String adminSitzung() {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId(), Rolle.ADMIN);
    }

    private Long adminSpielerId() {
        return jdbc.queryForObject("SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
    }

    private Long ersterSpieler() {
        return jdbc.queryForObject(
                "SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1",
                Long.class);
    }

    private Long spielerId(String name) {
        return jdbc.queryForObject("SELECT id FROM profil.spieler WHERE name = ?", Long.class, name);
    }

    private boolean existiert(String name) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM profil.spieler WHERE name = ?", Integer.class, name) > 0;
    }

    private int skillwert(Long spielerId, String kategorie) {
        return jdbc.queryForObject(
                "SELECT wert FROM profil.spieler_skill WHERE spieler_id = ? AND kategorie = ?",
                Integer.class, spielerId, kategorie);
    }
}
