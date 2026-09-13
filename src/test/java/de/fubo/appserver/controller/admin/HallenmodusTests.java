package de.fubo.appserver.controller.admin;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
import de.fubo.appserver.support.MailErsatz;
import de.fubo.appserver.support.MailErsatzConfig;
import de.fubo.appserver.utils.Absagevorlage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den Hallenmodus: Absagepfad, Frist, Nachricht, Protokoll und Lesepfad
 * (A23; S7 Abschnitte 2 bis 5).
 *
 * <h2>Diese Klasse traegt bewusst kein {@code @Transactional}</h2>
 * Der wertvollste Fall dieses Meilensteins ist "der Versand scheitert, und es bleibt kein
 * Zustand zurueck". <b>In einer Test-Transaktion liesse er sich nicht pruefen:</b> Die
 * {@code @Transactional}-Methode des Dienstes nimmt an der umgebenden Transaktion teil und
 * markiert sie beim Scheitern nur als "rollback-only" - der Absagevermerk stuende danach
 * trotzdem in der Zeile, und die Gegenprobe pruefte das Gegenteil dessen, was sie soll.
 * Aufgeraeumt wird deshalb von Hand, wie in {@code PasswortResetControllerTests} und
 * {@code AuditServiceTests}.
 *
 * <p><b>Zwei Folgen, die man kennen muss.</b> Erstens werden die Termine dieser Klasse wirklich
 * geschrieben; {@link #aufraeumen()} entfernt sie vor und nach jedem Fall ueber ihre Uhrzeit.
 * Zweitens gilt das auch fuer {@code configs.app_config} - die Klasse merkt sich die drei
 * Hallenfelder beim Aufbau und stellt sie danach wieder her, sonst wirkte eine Vorlaufzeit von
 * 0 in die naechste Testklasse hinein.
 *
 * <h2>Eigener Zeitstreifen: 700 Tage vorwaerts, 19:00</h2>
 * {@code uq_termin_zeit UNIQUE (datum, uhrzeit)} ist <b>global</b>, und beide Achsen zaehlen.
 * Vergeben sind 40/18:15 ({@code TerminControllerTests}), 120/19:45
 * ({@code TerminVerwaltungControllerTests}), 200/17:30 ({@code TeilnehmerlisteTests}),
 * 300/16:05 ({@code SpielerControllerTests}), 500/20:15 ({@code TeamGeneratorTests}) und
 * 600 rueckwaerts/21:30 ({@code ErgebnisControllerTests}). <b>Die Uhrzeit 19:00 traegt hier
 * allein</b> - deshalb darf diese Klasse mehrere Tage belegen und auch die Nachbarschaft von
 * heute benutzen, wo die Frist es verlangt.
 *
 * <h2>Der Aufbau schaltet den Hallenmodus ein</h2>
 * {@code hallen_modus_aktiv} steht im Seed-Stand auf {@code false} ({@code V013}) und ist die
 * <b>erste</b> Pruefung des Absagepfades. Ohne das Einschalten im {@code @BeforeEach} liefe
 * jeder Fall dieser Klasse in {@code 409 HALLE_MODUS_INAKTIV} - und zwar mit derselben
 * Antwort, egal was er sonst prueft. Die beiden Faelle, die den Schalter selbst pruefen,
 * stellen ihn ausdruecklich wieder aus; der Ausgangswert wird beim Abbau zurueckgeschrieben.
 *
 * <h2>Der Aufbau je Fall ist deutlich einfacher als in S6</h2>
 * Termin anlegen, Adresse setzen, absagen - fertig. Es braucht weder Zusagen noch eine
 * Einteilung: <b>Die Hallenabsage kennt den Teilnehmerkreis nicht.</b>
 *
 * <h2>Das Fenster wird ueber den Termin gesteuert, nicht ueber die Uhr</h2>
 * Ein Termin in 700 Tagen laesst das Fenster bei jedem Vorlauf offen, ein Termin von morgen
 * schliesst es bei den vorgegebenen 48 Stunden, und ein vergangener Termin faellt selbst bei
 * Vorlauf 0 heraus. <b>Kein Fall haengt an der Tageszeit des Testlaufs</b>, und keiner braucht
 * eine gestellte Uhr.
 *
 * <p><b>Ein vergangener Termin wird als {@code ABGESAGT} angelegt</b> und nicht als
 * {@code GEPLANT}: Der Auftrag aus A18 laeuft alle fuenf Minuten und setzt geplante Termine
 * nach Beginn auf {@code ABGESCHLOSSEN}. Ohne Test-Transaktion sieht er die Zeile - der Fall
 * pruefte dann zufaellig mal die Frist und mal den Status.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MailErsatzConfig.class})
class HallenmodusTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Abstand zum heutigen Tag; erste Haelfte des Kollisionsschutzes. */
    private static final int BASIS_TAGE = 700;

    /** Uhrzeit aller Termine dieser Klasse; zweite Haelfte des Kollisionsschutzes. */
    private static final LocalTime UHRZEIT = LocalTime.of(19, 0);

    /** Test-IP aus RFC 5737; zugleich der Schluessel, ueber den aufgeraeumt wird. */
    private static final String CLIENT_IP = "198.51.100.81";

    private static final String HALLE = "hallenbetreiber@example.invalid";

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter WOCHENTAG =
            DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN);

    @Autowired
    private WebApplicationContext kontext;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private MailErsatz mailErsatz;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    /** Zaehlt die Termine eines Falls; jeder bekommt einen eigenen Tag. */
    private int terminZaehler;

    private String urspruenglicheEmail;
    private String urspruenglicheVorlage;
    private int urspruenglicherVorlauf;
    private boolean urspruenglicherModus;

    @BeforeEach
    void aufbauen() {
        mockMvc = MockMvcBuilders.webAppContextSetup(kontext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        mailErsatz.zuruecksetzen();
        terminZaehler = 0;

        urspruenglicheEmail = jdbc.queryForObject(
                "SELECT halle_email FROM configs.app_config WHERE id = 1", String.class);
        urspruenglicheVorlage = jdbc.queryForObject(
                "SELECT halle_absage_vorlage FROM configs.app_config WHERE id = 1", String.class);
        urspruenglicherVorlauf = jdbc.queryForObject(
                "SELECT halle_vorlauf_stunden FROM configs.app_config WHERE id = 1", Integer.class);
        urspruenglicherModus = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT hallen_modus_aktiv FROM configs.app_config WHERE id = 1", Boolean.class));

        // Der Hauptschalter steht im Seed-Stand auf false (V013) und ist die erste Pruefung des
        // Absagepfades. Ohne dieses Einschalten liefe jeder Fall dieser Klasse in
        // 409 HALLE_MODUS_INAKTIV - und zwar mit derselben Antwort, egal was er sonst prueft.
        hallenModusSetzen(true);
        aufraeumen();
    }

    /**
     * Ohne umgebende Transaktion bleibt alles stehen, was die Faelle anlegen - auch die
     * geaenderte Konfiguration. Beides wuerde in die naechste Testklasse hineinwirken.
     */
    @AfterEach
    void abbauen() {
        aufraeumen();
        jdbc.update("""
                UPDATE configs.app_config
                   SET halle_email           = ?,
                       halle_absage_vorlage  = ?,
                       halle_vorlauf_stunden = CAST(? AS smallint),
                       hallen_modus_aktiv    = ?,
                       version               = version + 1
                 WHERE id = 1
                """, urspruenglicheEmail, urspruenglicheVorlage, urspruenglicherVorlauf,
                urspruenglicherModus);
    }

    /**
     * Raeumt Termine und Protokolleintraege dieser Klasse ab.
     *
     * <p>Die Termine ueber ihre Uhrzeit: Sie ist der Zeitstreifen dieser Klasse und trifft
     * damit genau ihre Zeilen. <b>Auch vor jedem Fall</b>, damit ein abgebrochener Lauf den
     * naechsten nicht an {@code uq_termin_zeit} scheitern laesst.
     */
    private void aufraeumen() {
        jdbc.update("DELETE FROM profil.audit_log WHERE akteur_bezeichnung = ?", CLIENT_IP);
        jdbc.update("DELETE FROM spieltag.termin WHERE uhrzeit = ?", UHRZEIT);
    }

    // --------------------------------------------------------------------- Absagepfad

    /** Der Regelfall: {@code 204}, genau eine Nachricht, und der Vermerk steht in der Zeile. */
    @Test
    void sagtAbUndVersendetGenauEineNachricht() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen();

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(mailErsatz.nachrichten()).hasSize(1);
        assertThat(mailErsatz.nachrichten().getFirst().getTo()).containsExactly(HALLE);
        assertThat(stempel(terminId)).as("halle_abgesagt_am ist gesetzt").isNotNull();
    }

    /**
     * <b>Ein geplanter Termin wird mit abgesagt</b> (Entscheidung vom 13.09.2026) - und das
     * Protokoll fuehrt beide Wirkungen getrennt.
     *
     * <p>Das Detail {@code weg} unterscheidet diese Absage von der ueber
     * {@code /admin/termin/absagen}. Ohne es waere im Protokoll nicht mehr erkennbar, warum ein
     * Termin ohne einen eigenen Aufruf abgesagt wurde.
     */
    @Test
    void sagtDenGeplantenTerminMitAb() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen();

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(terminStatus(terminId)).isEqualTo("ABGESAGT");
        assertThat(auditDetail(terminId, "TERMIN_ABGESAGT", "weg")).isEqualTo("hallenabsage");
        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "terminMitAbgesagt")).isEqualTo("true");
    }

    /**
     * Ein bereits abgesagter Termin wird nur noch gemeldet.
     *
     * <p><b>Das ist kein Sonderfall, sondern der Weg fuer die Absage nach Fristende:</b>
     * {@code /admin/termin/absagen} kennt keine Frist (A19). Es entsteht kein zweiter
     * {@code TERMIN_ABGESAGT}-Eintrag - das Protokoll belegt vollzogene Aenderungen, und hier
     * hat sich am Status nichts geaendert.
     */
    @Test
    void meldetEinenBereitsAbgesagtenTerminOhneZweitenProtokolleintrag() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen(BASIS_TAGE, null, "ABGESAGT");

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(mailErsatz.nachrichten()).hasSize(1);
        assertThat(terminStatus(terminId)).isEqualTo("ABGESAGT");
        assertThat(auditDetail(terminId, "TERMIN_ABGESAGT", "weg"))
                .as("kein zweiter Eintrag fuer eine Absage, die es schon gab").isNull();
        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "terminMitAbgesagt")).isEqualTo("false");
    }

    /**
     * Ein abgeschlossener Termin hat stattgefunden - eine Absage waere unwahr.
     *
     * <p>{@code TERMIN_GESCHLOSSEN} und kein eigener Code: Der Code bedeutet "nimmt keine
     * Aenderung mehr an", und genau das trifft hier zu.
     */
    @Test
    void lehntEinenAbgeschlossenenTerminAb() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen(BASIS_TAGE, null, "ABGESCHLOSSEN");

        ResultActions antwort = absagen(terminId).andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("TERMIN_GESCHLOSSEN");
        assertThat(mailErsatz.nachrichten()).isEmpty();
        assertThat(stempel(terminId)).isNull();
    }

    /**
     * <b>Der wichtigste Fall dieses Meilensteins.</b> Der zweite Aufruf bekommt {@code 409},
     * und es geht <b>keine</b> zweite Nachricht hinaus.
     *
     * <p>Entschieden wird das in der Datenbank: Der bedingte {@code UPDATE} trifft beim zweiten
     * Mal keine Zeile mehr. "Erst lesen, dann schreiben, dann versenden" liesse zwei
     * gleichzeitige Klicks beide durch - und der Hallenbetreiber bekaeme zwei Mails.
     *
     * <p>Der Zeitstempel bleibt der des ersten Versands; ein zweiter Vermerk ueberschriebe ihn.
     */
    @Test
    void zweiterAufrufScheitertUndVersendetKeineZweiteNachricht() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen();

        absagen(terminId).andExpect(status().isNoContent());
        String ersterStempel = stempel(terminId);

        ResultActions antwort = absagen(terminId).andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("HALLE_BEREITS_ABGESAGT");
        assertThat(mailErsatz.nachrichten()).hasSize(1);
        assertThat(stempel(terminId)).isEqualTo(ersterStempel);
    }

    /** Eine unbekannte Id ist ein {@code 404}, kein {@code 409}. */
    @Test
    void lehntEineUnbekannteTerminIdAb() throws Exception {
        halleEmailSetzen(HALLE);

        absagen(999_999L).andExpect(status().isNotFound());

        assertThat(mailErsatz.nachrichten()).isEmpty();
    }

    /**
     * <b>Der Hauptschalter sperrt den Endpunkt</b> (A23, Ergaenzung vom 13.09.2026).
     *
     * <p>A23 verlangt, dass sich der Hallenmodus <i>serverseitig</i> abschalten laesst. Ein
     * Flag, das nur der Client auswertet, waere ein ausgeblendeter Knopf und keine Abschaltung -
     * ein alter Browser-Tab oder ein Bruno-Aufruf kaemen daran vorbei, und am Ende steht eine
     * Mail bei einem Aussenstehenden, die sich nicht zuruecknehmen laesst.
     *
     * <p>Der Fall setzt bewusst alles andere auf gruen: Adresse hinterlegt, Frist offen, Termin
     * geplant. <b>Nur der Schalter steht aus</b> - sonst bewiese die Ablehnung nichts.
     */
    @Test
    void lehntAbWennDerHallenmodusAusgeschaltetIst() throws Exception {
        halleEmailSetzen(HALLE);
        hallenModusSetzen(false);
        Long terminId = terminAnlegen();

        ResultActions antwort = absagen(terminId).andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("HALLE_MODUS_INAKTIV");
        assertThat(mailErsatz.nachrichten()).isEmpty();
        assertThat(stempel(terminId)).isNull();
        assertThat(terminStatus(terminId)).as("auch der Termin bleibt unberuehrt")
                .isEqualTo("GEPLANT");
    }

    /**
     * Die Folge der Pruefreihenfolge, die man kennen muss: <b>Bei ausgeschaltetem Modus liefert
     * auch eine unbekannte Termin-Id {@code HALLE_MODUS_INAKTIV} und nicht {@code 404}.</b>
     *
     * <p>Das ist kein Versehen, sondern der Grund, aus dem der Schalter vor der Terminsuche
     * steht: Ist die Funktion aus, spielt es keine Rolle, welchen Termin der Aufruf meint - und
     * eine Datenbankabfrage fuer einen Vorgang, der ohnehin abgelehnt wird, waere verschenkt.
     * <b>Der Fall haelt die Reihenfolge fest</b>, damit sie nicht unbemerkt kippt.
     */
    @Test
    void derSchalterWirdVorDerTerminsucheGeprueft() throws Exception {
        hallenModusSetzen(false);

        ResultActions antwort = absagen(999_999L).andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("HALLE_MODUS_INAKTIV");
    }

    // --------------------------------------------------------------------- Frist

    /**
     * Ein Termin von morgen bei 48 Stunden Vorlauf: Das Fenster ist zu.
     *
     * <p><b>{@code detail} nennt Zahl und Zeitpunkt</b> - "zu spaet" ohne beides zwingt den
     * Admin, die Konfiguration nachzuschlagen.
     */
    @Test
    void lehntAbWennDerVorlaufUnterschrittenIst() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen(1, null, "GEPLANT");

        ResultActions antwort = absagen(terminId).andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("HALLE_FRIST_ABGELAUFEN");
        assertThat(detail(antwort)).contains("48 Stunden");
        assertThat(detail(antwort))
                .contains(LocalDate.now().minusDays(1).format(DATUM));
        assertThat(mailErsatz.nachrichten()).isEmpty();
        assertThat(stempel(terminId)).isNull();
    }

    /**
     * Derselbe Termin, Vorlauf auf {@code 0}: <b>Die Regel kommt aus der Konfiguration</b>,
     * 48 ist nur der Vorgabewert. {@code 0} heisst "bis zum Anpfiff" und ist kein Fehlerfall.
     */
    @Test
    void versendetWennDerVorlaufAufNullSteht() throws Exception {
        halleEmailSetzen(HALLE);
        vorlaufSetzen(0);
        Long terminId = terminAnlegen(1, null, "GEPLANT");

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(mailErsatz.nachrichten()).hasSize(1);
    }

    /**
     * Ein vergangener Termin faellt <b>ohne eigene Pruefung</b> aus der Frist - die Differenz
     * ist dann negativ, und das gilt auch bei einem Vorlauf von {@code 0}.
     *
     * <p>Der Termin ist absichtlich {@code ABGESAGT} und nicht {@code GEPLANT}: Sonst koennte
     * der Auftrag aus A18 ihn zwischendurch abschliessen, und der Fall pruefte mal die Frist
     * und mal den Status.
     */
    @Test
    void lehntEinenVergangenenTerminOhneEigenePruefungAb() throws Exception {
        halleEmailSetzen(HALLE);
        vorlaufSetzen(0);
        Long terminId = terminAnlegen(-1, null, "ABGESAGT");

        ResultActions antwort = absagen(terminId).andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("HALLE_FRIST_ABGELAUFEN");
        assertThat(mailErsatz.nachrichten()).isEmpty();
    }

    // --------------------------------------------------------------------- Adresse und Vorlage

    /**
     * Ohne Empfaengeradresse gibt es kein Ziel. <b>Die leere Adresse ist ein Fehler, die leere
     * Vorlage nicht</b> - {@code halle_email} ist im Seed-Stand nicht gesetzt.
     */
    @Test
    void lehntAbWennKeineEmpfaengeradresseHinterlegtIst() throws Exception {
        halleEmailSetzen(null);
        Long terminId = terminAnlegen();

        ResultActions antwort = absagen(terminId).andExpect(status().isConflict());

        assertThat(fehlercode(antwort)).isEqualTo("HALLE_NICHT_KONFIGURIERT");
        assertThat(mailErsatz.nachrichten()).isEmpty();
        assertThat(stempel(terminId)).isNull();
    }

    /**
     * Eine geleerte Vorlage haelt den Versand nicht auf - der Server setzt seine Ersatzvorlage
     * ein (Entscheidung vom 13.09.2026).
     *
     * <p>Das Protokolldetail {@code ersatzvorlage} haelt fest, dass es die Ersatzfassung war:
     * Der Text selbst steht nicht im Log, und die Zeichenzahl allein saehe man ihm nicht an.
     */
    @Test
    void versendetMitDerErsatzvorlageWennKeineGepflegtIst() throws Exception {
        halleEmailSetzen(HALLE);
        vorlageSetzen(null);
        Long terminId = terminAnlegen();

        absagen(terminId).andExpect(status().isNoContent());

        String text = mailErsatz.nachrichten().getFirst().getText();
        assertThat(text).contains("Termin:").contains(Absagevorlage.ERSATZ);
        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "ersatzvorlage")).isEqualTo("true");
    }

    /**
     * <b>Der Waechter ueber die zweite Kopie des Textes.</b> Die Ersatzvorlage steht wortgleich
     * als Spaltenvorgabe in {@code V010}; Migrationen sind unveraenderlich, der Wortlaut laesst
     * sich dort also nicht nachziehen.
     *
     * <p>Ohne diesen Fall liefen die beiden Fassungen auseinander, ohne dass es jemandem
     * auffiele: Eine bestehende Installation schickte den einen Text, eine frische den anderen.
     */
    @Test
    void dieErsatzvorlageIstZeichengleichZumSpaltenvorgabewert() {
        String vorgabe = jdbc.queryForObject("""
                SELECT column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'configs'
                   AND table_name   = 'app_config'
                   AND column_name  = 'halle_absage_vorlage'
                """, String.class);

        assertThat(vorgabe).as("V010 setzt einen Spaltenvorgabewert").isNotNull();
        assertThat(alsText(vorgabe)).isEqualTo(Absagevorlage.ERSATZ);
    }

    /**
     * Die Konfiguration zeigt neben der gespeicherten Vorlage den Text, der tatsaechlich
     * hinausginge.
     *
     * <p><b>Der Fall steht hier und nicht in {@code KonfigurationControllerTests}</b>: Das Feld
     * beantwortet eine Frage des Hallenmodus, und der Zustand, den es sichtbar macht - die
     * geleerte Vorlage -, gehoert zum Aufbau dieser Klasse.
     */
    @Test
    void dieKonfigurationZeigtDieWirksameVorlage() throws Exception {
        vorlageSetzen(null);

        Map<String, Object> ohne = konfiguration();
        assertThat(ohne.get("halleAbsageVorlage")).isNull();
        assertThat(ohne.get("halleAbsageVorlageEffektiv")).isEqualTo(Absagevorlage.ERSATZ);

        vorlageSetzen("Der Termin faellt leider aus.");

        Map<String, Object> mit = konfiguration();
        assertThat(mit.get("halleAbsageVorlage")).isEqualTo("Der Termin faellt leider aus.");
        assertThat(mit.get("halleAbsageVorlageEffektiv")).isEqualTo("Der Termin faellt leider aus.");
    }

    // --------------------------------------------------------------------- Die Nachricht

    /**
     * Betreff und Datenblock tragen Datum und Uhrzeit, der Fliesstext steht darunter.
     *
     * <p><b>Der Betreff ist die einzige Zeile, die der Hallenbetreiber in seiner Uebersicht
     * sieht</b> - ohne Datum muesste er jede Nachricht oeffnen. Der Wochentag gehoert dazu,
     * obwohl das Datum ihn enthaelt: Er ist die Angabe, an der ein Mensch einen Terminirrtum
     * bemerkt.
     */
    @Test
    void betreffUndTextNennenDatumUhrzeitUndVorlage() throws Exception {
        halleEmailSetzen(HALLE);
        vorlageSetzen("Wir bitten um Stornierung.");
        LocalDate datum = LocalDate.now().plusDays(BASIS_TAGE);
        Long terminId = terminAnlegen(BASIS_TAGE, null, "GEPLANT");

        absagen(terminId).andExpect(status().isNoContent());

        SimpleMailMessage nachricht = mailErsatz.nachrichten().getFirst();
        assertThat(nachricht.getSubject())
                .isEqualTo("Absage Hallentermin am %s, 19:00 Uhr".formatted(datum.format(DATUM)));
        assertThat(nachricht.getText())
                .contains(datum.format(WOCHENTAG))
                .contains(datum.format(DATUM))
                .contains("19:00 Uhr")
                .contains("Wir bitten um Stornierung.");
    }

    /** Ist ein Ort hinterlegt, steht er im Datenblock. */
    @Test
    void nenntDenOrtImDatenblock() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen(BASIS_TAGE, "Sporthalle Nord", "GEPLANT");

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(mailErsatz.nachrichten().getFirst().getText()).contains("Sporthalle Nord");
    }

    /**
     * Fehlt der Ort, <b>entfaellt die Zeile ganz</b> - nicht "Ort: -" und nicht
     * "Ort: unbekannt". Der Hallenbetreiber weiss, um welche Halle es geht; er hat nur die eine.
     */
    @Test
    void laesstDieOrtszeileWegWennKeinOrtGesetztIst() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen(BASIS_TAGE, null, "GEPLANT");

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(mailErsatz.nachrichten().getFirst().getText()).doesNotContain("Ort:");
    }

    // --------------------------------------------------------------------- Fehlschlag

    /**
     * <b>Der zweite wertvolle Fall.</b> Scheitert der Versand, bleibt kein Zustand zurueck: Der
     * Vermerk steht vor dem Versand, beides in einer Transaktion, und die rollt zurueck.
     *
     * <p>Auch die mitlaufende Terminabsage faellt damit weg - der Termin ist danach wieder
     * {@code GEPLANT}, und der Aufruf laesst sich unveraendert wiederholen.
     */
    @Test
    void laesstBeiFehlgeschlagenemVersandKeinenZustandZurueck() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen();
        mailErsatz.laesstScheitern(true);

        ResultActions antwort = absagen(terminId).andExpect(status().isServiceUnavailable());

        assertThat(fehlercode(antwort)).isEqualTo("VERSAND_FEHLGESCHLAGEN");
        assertThat(stempel(terminId)).as("kein Vermerk ohne versandte Nachricht").isNull();
        assertThat(terminStatus(terminId)).as("auch die Terminabsage rollt mit zurueck")
                .isEqualTo("GEPLANT");
        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "empfaenger")).isNull();
    }

    // --------------------------------------------------------------------- Audit

    /**
     * Der Protokolleintrag nennt Empfaenger, Datum und Uhrzeit - <b>aber nicht den
     * Vorlagentext</b>.
     *
     * <p>Dieselbe Ausnahme, die {@code KONFIG_GEAENDERT} fuer die Vorlage macht: Ein
     * mehrzeiliger Text in jedem Eintrag blaeht die Tabelle auf, ohne etwas zu belegen, was
     * nicht auch die Konfiguration belegt. Die Empfaengeradresse dagegen gehoert hinein - sie
     * ist veraenderlich, und "an wen ist die Absage damals gegangen" ist genau die Frage, die
     * man spaeter stellt.
     */
    @Test
    void protokolliertDieAbsageOhneDenVorlagentext() throws Exception {
        halleEmailSetzen(HALLE);
        vorlageSetzen("Bitte stornieren Sie die Buchung.");
        LocalDate datum = LocalDate.now().plusDays(BASIS_TAGE);
        Long terminId = terminAnlegen(BASIS_TAGE, null, "GEPLANT");

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "empfaenger")).isEqualTo(HALLE);
        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "datum")).isEqualTo(datum.toString());
        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "uhrzeit"))
                .as("LocalTime.toString laesst die Sekunden weg, wenn sie null sind")
                .isEqualTo(UHRZEIT.toString());
        assertThat(auditDetail(terminId, "HALLE_ABGESAGT", "vorlageZeichen")).isEqualTo("33");
        assertThat(auditDetails(terminId, "HALLE_ABGESAGT"))
                .as("der Vorlagentext gehoert nicht ins Protokoll")
                .doesNotContain("Bitte stornieren Sie die Buchung.");
    }

    // --------------------------------------------------------------------- Lesepfad

    /**
     * Die Einzelansicht fuehrt den Absagezeitpunkt: vorher {@code null}, danach gesetzt.
     *
     * <p><b>Gelesen wird mit einer Spielersitzung</b>, nicht mit der des Admins: Das Feld ist
     * eine Tatsache ueber den Termin und darf jede Rolle sehen - es verraet nichts, was nicht
     * ohnehin gilt.
     */
    @Test
    void dieEinzelansichtZeigtDenAbsagezeitpunkt() throws Exception {
        halleEmailSetzen(HALLE);
        Long terminId = terminAnlegen();

        assertThat(einzeln(spielerSitzung(), terminId).get("halleAbgesagtAm"))
                .as("null ist der Normalzustand, kein Fehler").isNull();

        absagen(terminId).andExpect(status().isNoContent());

        assertThat(einzeln(spielerSitzung(), terminId).get("halleAbgesagtAm")).isNotNull();
    }

    /**
     * Die Einzelansicht nennt den spaetesten Absagezeitpunkt - <b>aber kein Feld
     * "darf ich noch absagen"</b>.
     *
     * <p>Das waere eine Berechtigungsaussage in einem rollenneutralen Antwortobjekt und
     * erschiene bei {@code USER} und {@code GAST} bedeutungslos mit. Der Adminbildschirm
     * rechnet die Frage aus diesem Zeitpunkt selbst; durchgesetzt wird sie ohnehin
     * serverseitig - die Client-Rechnung blendet nur einen Knopf aus.
     */
    @Test
    void dieEinzelansichtNenntDenSpaetestenAbsagezeitpunkt() throws Exception {
        vorlaufSetzen(24);
        LocalDate datum = LocalDate.now().plusDays(BASIS_TAGE);
        Long terminId = terminAnlegen(BASIS_TAGE, null, "GEPLANT");

        Map<String, Object> termin = einzeln(spielerSitzung(), terminId);

        Object roh = termin.get("halleAbsageMoeglichBis");
        assertThat(roh).as("ISO-Zeichenkette und kein Zeitstempel").isInstanceOf(String.class);
        assertThat(LocalDateTime.parse((String) roh))
                .isEqualTo(LocalDateTime.of(datum, UHRZEIT).minusHours(24));
        assertThat(termin).doesNotContainKey("halleAbsageMoeglich");
    }

    // --------------------------------------------------------------------- Aufbau

    /** Jeder Termin eines Falls bekommt einen eigenen Tag im Streifen dieser Klasse. */
    private Long terminAnlegen() {
        return terminAnlegen(BASIS_TAGE + terminZaehler++, null, "GEPLANT");
    }

    /**
     * Legt einen Termin per SQL an.
     *
     * <p>Ueber SQL und nicht ueber {@code /admin/termin/anlegen}: Jener Endpunkt lehnt einen
     * Zeitpunkt in der Vergangenheit ab, und genau den braucht der Fristfall.
     *
     * @param tage   Abstand zu heute; negativ fuer einen vergangenen Termin
     * @param ort    Spielort oder {@code null}
     * @param status Ausgangszustand; die Spalte traegt einen CHECK-Constraint
     */
    private Long terminAnlegen(long tage, String ort, String status) {
        return jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit, ort, status)
                VALUES (?, ?, ?, CAST(? AS varchar))
                RETURNING id
                """, Long.class, LocalDate.now().plusDays(tage), UHRZEIT, ort, status);
    }

    /**
     * Aendert ein Hallenfeld der Konfiguration - <b>mit erhoehter {@code version}</b>.
     *
     * <p>Die Regel gilt fuer jede {@code version}-Spalte, die per SQL geaendert wird. Ohne den
     * Nachtrag liefe ein spaeterer Schreibvorgang auf derselben Zeile in einen Sperrkonflikt,
     * den niemand verursacht hat.
     */
    private void halleEmailSetzen(String adresse) {
        jdbc.update("""
                UPDATE configs.app_config SET halle_email = ?, version = version + 1 WHERE id = 1
                """, adresse);
    }

    private void vorlageSetzen(String text) {
        jdbc.update("""
                UPDATE configs.app_config
                   SET halle_absage_vorlage = ?, version = version + 1
                 WHERE id = 1
                """, text);
    }

    private void hallenModusSetzen(boolean aktiv) {
        jdbc.update("""
                UPDATE configs.app_config
                   SET hallen_modus_aktiv = ?, version = version + 1
                 WHERE id = 1
                """, aktiv);
    }

    private void vorlaufSetzen(int stunden) {
        jdbc.update("""
                UPDATE configs.app_config
                   SET halle_vorlauf_stunden = CAST(? AS smallint), version = version + 1
                 WHERE id = 1
                """, stunden);
    }

    // --------------------------------------------------------------------- Aufrufe

    private ResultActions absagen(Long terminId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/halle/absagen")
                .cookie(new Cookie(COOKIE, adminSitzung()))
                .header("CF-Connecting-IP", CLIENT_IP)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminId\":%d}".formatted(terminId)));
    }

    private Map<String, Object> einzeln(String token, Long terminId) throws Exception {
        return alsKarte(mockMvc.perform(get("/api/v1/termine/%d/lesen".formatted(terminId))
                .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk()));
    }

    private Map<String, Object> konfiguration() throws Exception {
        return alsKarte(mockMvc.perform(get("/api/v1/admin/config/lesen")
                .cookie(new Cookie(COOKIE, adminSitzung())))
                .andExpect(status().isOk()));
    }

    // --------------------------------------------------------------------- Auswertung

    private Map<String, Object> alsKarte(ResultActions ergebnis) throws Exception {
        return objectMapper.readValue(
                ergebnis.andReturn().getResponse().getContentAsString(), new TypeReference<>() {
                });
    }

    /** Der Fehlercode aus dem Problem Detail; nie der Meldungstext - der ist Anzeigetext. */
    private String fehlercode(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("code");
    }

    private String detail(ResultActions ergebnis) throws Exception {
        return (String) alsKarte(ergebnis).get("detail");
    }

    /** Der Absagevermerk als Text; das spart eine Typabbildung fuer einen reinen Vergleich. */
    private String stempel(Long terminId) {
        return jdbc.queryForObject(
                "SELECT halle_abgesagt_am::text FROM spieltag.termin WHERE id = ?",
                String.class, terminId);
    }

    /**
     * Der Terminstatus aus der Datenbank.
     *
     * <p><b>Die Methode heisst nicht {@code status}</b>, obwohl das naheliegt: Eine gleichnamige
     * Methode der Klasse verdeckt den statischen Import
     * {@code MockMvcResultMatchers.status()} <b>vollstaendig</b> - Java sucht erst im eigenen
     * Typ und zieht den statischen Import gar nicht mehr heran, auch nicht bei abweichender
     * Signatur. Jeder Aufruf {@code status()} im selben Test scheitert dann mit
     * "Erforderlich: java.lang.Long, Ermittelt: keine Argumente".
     */
    private String terminStatus(Long terminId) {
        return jdbc.queryForObject(
                "SELECT status FROM spieltag.termin WHERE id = ?", String.class, terminId);
    }

    /**
     * Ein Detail aus dem juengsten Protokolleintrag zu diesem Termin, oder {@code null}, wenn
     * es keinen gibt.
     *
     * <p>{@code ->>} liefert den Wert als Text - auch bei Zahlen und Wahrheitswerten. <b>Der
     * jsonb-Existenzoperator {@code ?} waere hier nicht verwendbar</b>: Fuer JDBC ist das
     * Fragezeichen ein Bind-Platzhalter.
     */
    private String auditDetail(Long terminId, String aktion, String schluessel) {
        return jdbc.query("""
                SELECT details ->> CAST(? AS text)
                  FROM profil.audit_log
                 WHERE aktion = CAST(? AS varchar)
                   AND entitaet = 'termin'
                   AND entitaet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, zeile) -> rs.getString(1), schluessel, aktion, terminId)
                .stream().findFirst().orElse(null);
    }

    /** Die vollstaendigen Details als Text - fuer die Gegenprobe, was <b>nicht</b> drinsteht. */
    private String auditDetails(Long terminId, String aktion) {
        return jdbc.queryForObject("""
                SELECT details::text
                  FROM profil.audit_log
                 WHERE aktion = CAST(? AS varchar)
                   AND entitaet = 'termin'
                   AND entitaet_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, String.class, aktion, terminId);
    }

    /**
     * Macht aus einem SQL-Vorgabewert den reinen Text.
     *
     * <p>{@code information_schema} liefert ihn als Ausdruck: ein Zeichenkettenliteral mit
     * verdoppelten Hochkommata, gefolgt von einem Typumwandlungszusatz.
     */
    private static String alsText(String vorgabewert) {
        String wert = vorgabewert.trim();
        int ende = wert.lastIndexOf('\'');
        wert = wert.substring(wert.indexOf('\'') + 1, ende);
        return wert.replace("''", "'");
    }

    // --------------------------------------------------------------------- Sitzungen

    private String spielerSitzung() {
        Long spielerId = jdbc.queryForObject("""
                SELECT id FROM profil.spieler WHERE rolle = 'USER' AND aktiv ORDER BY name LIMIT 1
                """, Long.class);
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId, Rolle.USER);
    }

    private String adminSitzung() {
        Long adminSpielerId = jdbc.queryForObject(
                "SELECT spieler_id FROM profil.admin_konto WHERE id = 1", Long.class);
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, adminSpielerId, Rolle.ADMIN);
    }
}
