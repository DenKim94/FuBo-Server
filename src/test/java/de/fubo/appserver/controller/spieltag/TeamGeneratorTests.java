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
     * Eine Absage oeffnet das Kontingent <b>nicht</b> (A15, Aenderung vom 22.10.2026).
     *
     * <p>Wer absagt, verkleinert den Kreis und liefert keinen Grund fuer einen weiteren Lauf.
     * Die Absage erhoeht {@code teilnehmer_version} (die Einteilung veraltet), aber nicht
     * {@code kontingent_version}, die im Schluessel des Kontingents steht. Frueher teilten sich
     * beide Aufgaben einen Zaehler, und jede Absage verschaffte allen einen weiteren Lauf.
     */
    @Test
    void eineAbsageOeffnetDasKontingentNicht() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        int kontingentVorher = kontingentVersion(terminId);
        int teilnehmerVorher = teilnehmerVersion(terminId);

        rueckmeldung(spielerSitzung(7), terminId, false).andExpect(status().isNoContent());

        assertThat(kontingentVersion(terminId)).isEqualTo(kontingentVorher);
        assertThat(teilnehmerVersion(terminId)).isEqualTo(teilnehmerVorher + 1);
        assertThat(fehlercode(generieren(token, terminId).andExpect(status().isConflict())))
                .isEqualTo("KONTINGENT_ERSCHOEPFT");
    }

    /**
     * Eine neue Zusage oeffnet das Kontingent wieder (A15).
     *
     * <p><b>Nicht durch Zuruecksetzen, sondern durch einen neuen Schluessel:</b> Die Zusage
     * erhoeht {@code kontingent_version}, und damit passt keine bestehende Kontingentzeile
     * mehr. Genau das ist der Mechanismus, und genau das prueft dieser Fall.
     */
    @Test
    void eineNeueZusageOeffnetDasKontingent() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        rueckmeldung(spielerSitzung(8), terminId, true).andExpect(status().isNoContent());

        generieren(token, terminId).andExpect(status().isCreated());
    }

    /**
     * Eine Zusage nach einer Absage zaehlt als neue Zusage - die Absage selbst nicht.
     *
     * <p>Beide Schritte in einem Fall, weil erst ihr Zusammenspiel den Unterschied zeigt: Nach
     * der Absage bleibt das Kontingent zu, nach der erneuten Zusage ist es offen.
     */
    @Test
    void eineZusageNachEinerAbsageOeffnetDasKontingent() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        rueckmeldung(spielerSitzung(7), terminId, false).andExpect(status().isNoContent());
        generieren(token, terminId).andExpect(status().isConflict());

        rueckmeldung(spielerSitzung(7), terminId, true).andExpect(status().isNoContent());

        generieren(token, terminId).andExpect(status().isCreated());
    }

    /**
     * Eine wiederholte Zusage aendert nichts und oeffnet nichts.
     *
     * <p>Ohne diesen Fall liesse sich das Kontingent durch wiederholtes Zusagen aufladen.
     */
    @Test
    void eineWiederholteZusageOeffnetDasKontingentNicht() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        rueckmeldung(spielerSitzung(7), terminId, true).andExpect(status().isNoContent());

        assertThat(fehlercode(generieren(token, terminId).andExpect(status().isConflict())))
                .isEqualTo("KONTINGENT_ERSCHOEPFT");
    }

    /** Eine Skillaenderung durch den Admin oeffnet das Kontingent (A15). */
    @Test
    void eineSkillaenderungOeffnetDasKontingent() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        skillAendern(spielerId(2), 1);

        generieren(token, terminId).andExpect(status().isCreated());
    }

    /** Die Stufenaenderung eines Gastes durch den Admin oeffnet das Kontingent (A15, A17). */
    @Test
    void eineGastStufenaenderungOeffnetDasKontingent() throws Exception {
        Long terminId = terminMitZusagen(7);
        gastZusageAnlegen(terminId, "Testgast 2", "SCHWACH", 1);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/teilnahme/gast-stufe")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminId\":%d,\"gastName\":\"Testgast 2\",\"stufe\":\"STARK\"}"
                                .formatted(terminId)))
                .andExpect(status().isNoContent());

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
     * Ein Gast mit Zusage darf generieren (A15).
     *
     * <p>Der Endpunkt liegt bewusst nicht unter {@code /admin/}. Das Kontingent zaehlt fuer
     * ihn ueber den belegten Gastplatz - ohne {@code gastSlotId} in der Sitzung waeren beide
     * Akteurspalten leer und {@code ck_kontingent_akteur} liesse die Zeile nicht zu. Die
     * Zusage gibt er ueber den echten Endpunkt ab; sie ist seit dem 22.10.2026 Voraussetzung.
     */
    @Test
    void gastMitZusageDarfGenerieren() throws Exception {
        Long terminId = terminMitZusagen(8);
        String gast = gastSitzung("Testgast 1");
        rueckmeldung(gast, terminId, true).andExpect(status().isNoContent());

        generieren(gast, terminId).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM spieltag.generierung_kontingent
                 WHERE termin_id = ? AND akteur_gast_slot_id IS NOT NULL
                """, Integer.class, terminId))
                .as("Der Gast zaehlt ueber seinen Platz")
                .isEqualTo(1);
    }

    // --------------------------------------------------------------------- Berechtigung

    /**
     * Wer nicht zugesagt hat, darf nicht generieren (Aenderung vom 22.10.2026).
     *
     * <p>Neben der Antwort wird geprueft, dass nichts entstanden ist: weder ein Lauf noch eine
     * Kontingentzeile. Die Pruefung muss vor dem Verbuchen liegen.
     */
    @Test
    void werNichtZugesagtHatDarfNichtGenerieren() throws Exception {
        Long terminId = terminMitZusagen(8);

        ResultActions ergebnis = generieren(spielerSitzung(8), terminId)
                .andExpect(status().isForbidden());

        assertThat(fehlercode(ergebnis)).isEqualTo("KEINE_ZUSAGE");
        assertThat(anzahlLaeufe(terminId)).isZero();
        assertThat(anzahlKontingentzeilen(terminId)).isZero();
    }

    /** Wer abgesagt hat, darf nicht mehr generieren - auch nicht mit frueherer Zusage. */
    @Test
    void werAbgesagtHatDarfNichtGenerieren() throws Exception {
        Long terminId = terminMitZusagen(8);
        rueckmeldung(spielerSitzung(7), terminId, false).andExpect(status().isNoContent());

        assertThat(fehlercode(generieren(spielerSitzung(7), terminId)
                .andExpect(status().isForbidden()))).isEqualTo("KEINE_ZUSAGE");
    }

    /**
     * Die Zusage eines anderen Termins genuegt nicht.
     *
     * <p>Die Pruefung gilt dem Termin der Anfrage und nicht "irgendeinem kuenftigen".
     */
    @Test
    void dieZusageAnEinemAnderenTerminGenuegtNicht() throws Exception {
        Long terminId = terminMitZusagen(8);
        Long anderer = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 7));
        zusageAnlegen(anderer, spielerId(9), 1);

        assertThat(fehlercode(generieren(spielerSitzung(9), terminId)
                .andExpect(status().isForbidden()))).isEqualTo("KEINE_ZUSAGE");
    }

    /**
     * Wartende mit Zusage duerfen generieren.
     *
     * <p>Sie haben zugesagt; ob sie nachruecken, ergibt sich erst aus der Aufstellung. Sie
     * auszuschliessen hiesse, ihre Zusage nur dann gelten zu lassen, wenn es eng wird.
     */
    @Test
    void wartendeMitZusageDuerfenGenerieren() throws Exception {
        maxTeilnehmerSetzen(6);
        Long terminId = terminMitZusagen(8);

        Map<String, Object> einteilung = alsKarte(
                generieren(spielerSitzung(7), terminId).andExpect(status().isCreated()));

        assertThat(alleNamen(einteilung)).doesNotContain(spielerName(7));
    }

    /** Der Admin darf immer generieren, auch ohne selbst zuzusagen. */
    @Test
    void derAdminDarfOhneZusageGenerieren() throws Exception {
        Long terminId = terminMitZusagen(8);

        generieren(adminSitzung(), terminId).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM spieltag.generierung_kontingent
                 WHERE termin_id = ? AND akteur_spieler_id = ?
                """, Integer.class, terminId, adminSpielerId()))
                .as("Der Admin zaehlt mit eigenem Kontingent")
                .isEqualTo(1);
    }

    /** Ein Gast ohne Zusage darf nicht generieren, auch wenn er angemeldet ist. */
    @Test
    void einGastOhneZusageDarfNichtGenerieren() throws Exception {
        Long terminId = terminMitZusagen(8);

        assertThat(fehlercode(generieren(gastSitzung("Testgast 1"), terminId)
                .andExpect(status().isForbidden()))).isEqualTo("KEINE_ZUSAGE");
    }

    /** Ein Gast, der seine Zusage zurueckzieht, verliert das Recht wieder. */
    @Test
    void einGastNachAbsageDarfNichtGenerieren() throws Exception {
        Long terminId = terminMitZusagen(8);
        String gast = gastSitzung("Testgast 1");
        rueckmeldung(gast, terminId, true).andExpect(status().isNoContent());
        rueckmeldung(gast, terminId, false).andExpect(status().isNoContent());

        assertThat(fehlercode(generieren(gast, terminId).andExpect(status().isForbidden())))
                .isEqualTo("KEINE_ZUSAGE");
    }

    /** Ein unbekannter Termin bleibt {@code 404}; die Berechtigung kommt danach. */
    @Test
    void einUnbekannterTerminLiefert404StattKeineZusage() throws Exception {
        assertThat(fehlercode(generieren(spielerSitzung(8), 999_999_999L)
                .andExpect(status().isNotFound()))).isEqualTo("INHALT_NICHT_GEFUNDEN");
    }

    /**
     * Ein Aussenstehender erfaehrt nichts ueber den Zustand des Termins.
     *
     * <p>Die Berechtigung steht vor der Statuspruefung: Fuer einen abgesagten Termin bekommt
     * der Nicht-Teilnehmer {@code 403} und nicht {@code 409 TERMIN_GESCHLOSSEN}.
     */
    @Test
    void einAussenstehenderErfaehrtDenTerminstatusNicht() throws Exception {
        Long terminId = terminMitZusagen(8);
        jdbc.update("UPDATE spieltag.termin SET status = 'ABGESAGT', version = version + 1 WHERE id = ?",
                terminId);

        assertThat(fehlercode(generieren(spielerSitzung(8), terminId)
                .andExpect(status().isForbidden()))).isEqualTo("KEINE_ZUSAGE");
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

    /**
     * Auch eine Absage laesst die Einteilung veralten, obwohl sie das Kontingent nicht oeffnet.
     *
     * <p>Das Kennzeichen haengt an {@code teilnehmer_version}: Der Kreis ist ein anderer, und
     * jemand hat die Aufstellung vielleicht schon vorgelesen.
     */
    @Test
    void eineAbsageLaesstDieEinteilungVeralten() throws Exception {
        Long terminId = terminMitZusagen(8);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());

        rueckmeldung(spielerSitzung(7), terminId, false).andExpect(status().isNoContent());

        assertThat(teams(einzeln(spielerSitzung(0), terminId))).containsEntry("veraltet", true);
    }

    // --------------------------------------------------------------------- Restkontingent

    /**
     * Vor dem ersten Lauf steht das volle Kontingent in der Einzelansicht.
     *
     * <p>Die Grenze wird ausdruecklich auf 2 gesetzt, damit {@code erlaubt} und
     * {@code uebrig} nicht zufaellig mit der Vorgabe 1 zusammenfallen und ein Vertauschen
     * der beiden Werte unbemerkt bliebe.
     */
    @Test
    void restkontingentVorDemErstenLauf() throws Exception {
        anzTeamGeneratorSetzen(2);
        Long terminId = terminMitZusagen(8);

        erwarteKontingent(kontingentGenerierung(spielerSitzung(0), terminId), 2, 2);
    }

    /**
     * Jeder Lauf kostet einen; nach dem letzten steht {@code 0} da - und der naechste Lauf
     * endet tatsaechlich mit {@code 409 KONTINGENT_ERSCHOEPFT} (Zusicherung 1).
     */
    @Test
    void restkontingentSinktMitJedemLaufBisNullUndStimmtMitDemLaufUeberein() throws Exception {
        anzTeamGeneratorSetzen(2);
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        generieren(token, terminId).andExpect(status().isCreated());
        erwarteKontingent(kontingentGenerierung(token, terminId), 2, 1);

        generieren(token, terminId).andExpect(status().isCreated());
        erwarteKontingent(kontingentGenerierung(token, terminId), 2, 0);

        assertThat(fehlercode(generieren(token, terminId).andExpect(status().isConflict())))
                .isEqualTo("KONTINGENT_ERSCHOEPFT");
    }

    /** Die Auskunft ist lesend: Wiederholtes Lesen verbraucht nichts. */
    @Test
    void dasLesenDesRestkontingentsVerbrauchtNichts() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);

        kontingentGenerierung(token, terminId);
        kontingentGenerierung(token, terminId);

        assertThat(anzahlKontingentzeilen(terminId)).isZero();
        generieren(token, terminId).andExpect(status().isCreated());
    }

    /**
     * Die Absage eines Dritten aendert nichts (Zusicherung 4).
     *
     * <p>Der Fall ist das Gegenstueck zu {@code eineAbsageOeffnetDasKontingentNicht}: Dort
     * prueft der Lauf, hier die Anzeige - beide muessen dasselbe sagen.
     */
    @Test
    void eineAbsageAendertDasRestkontingentNicht() throws Exception {
        anzTeamGeneratorSetzen(2);
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);
        generieren(token, terminId).andExpect(status().isCreated());

        rueckmeldung(spielerSitzung(7), terminId, false).andExpect(status().isNoContent());

        erwarteKontingent(kontingentGenerierung(token, terminId), 2, 1);
    }

    /** Die neue Zusage eines Dritten oeffnet das Kontingent wieder ganz (Zusicherung 4). */
    @Test
    void eineNeueZusageSetztDasRestkontingentZurueck() throws Exception {
        anzTeamGeneratorSetzen(2);
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);
        generieren(token, terminId).andExpect(status().isCreated());
        generieren(token, terminId).andExpect(status().isCreated());
        erwarteKontingent(kontingentGenerierung(token, terminId), 2, 0);

        rueckmeldung(spielerSitzung(8), terminId, true).andExpect(status().isNoContent());

        erwarteKontingent(kontingentGenerierung(token, terminId), 2, 2);
    }

    /** Eine Skillaenderung durch den Admin oeffnet das Kontingent wieder (Zusicherung 4). */
    @Test
    void eineSkillaenderungSetztDasRestkontingentZurueck() throws Exception {
        Long terminId = terminMitZusagen(8);
        String token = spielerSitzung(0);
        generieren(token, terminId).andExpect(status().isCreated());
        erwarteKontingent(kontingentGenerierung(token, terminId), 1, 0);

        skillAendern(spielerId(2), 1);

        erwarteKontingent(kontingentGenerierung(token, terminId), 1, 1);
    }

    /** Eine Gast-Stufenaenderung durch den Admin oeffnet das Kontingent wieder. */
    @Test
    void eineGastStufenaenderungSetztDasRestkontingentZurueck() throws Exception {
        Long terminId = terminMitZusagen(7);
        gastZusageAnlegen(terminId, "Testgast 2", "SCHWACH", 1);
        String token = spielerSitzung(0);
        generieren(token, terminId).andExpect(status().isCreated());
        erwarteKontingent(kontingentGenerierung(token, terminId), 1, 0);

        mockMvc.perform(post("/api/v1/admin/teilnahme/gast-stufe")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminId\":%d,\"gastName\":\"Testgast 2\",\"stufe\":\"STARK\"}"
                                .formatted(terminId)))
                .andExpect(status().isNoContent());

        erwarteKontingent(kontingentGenerierung(token, terminId), 1, 1);
    }

    /**
     * Ein Gast zaehlt ueber seinen Gastplatz - und ein Spieler am selben Termin davon
     * unberuehrt.
     *
     * <p>Waere der Akteur beim Lesen anders gebildet als beim Verbuchen, stuende hier fuer
     * den Gast immer die volle Zahl: Der Platz ist {@code akteur_gast_slot_id}, die
     * Profil-Id ist {@code NULL}, und ein Vergleich mit {@code =} traefe die Zeile nicht.
     */
    @Test
    void einGastZaehltUeberSeinenPlatz() throws Exception {
        Long terminId = terminMitZusagen(8);
        String gast = gastSitzung("Testgast 1");
        rueckmeldung(gast, terminId, true).andExpect(status().isNoContent());
        erwarteKontingent(kontingentGenerierung(gast, terminId), 1, 1);

        generieren(gast, terminId).andExpect(status().isCreated());

        erwarteKontingent(kontingentGenerierung(gast, terminId), 1, 0);
        erwarteKontingent(kontingentGenerierung(spielerSitzung(0), terminId), 1, 1);
    }

    /**
     * Der Admin ohne Zusage hat ein eigenes Kontingent, das die anderen nicht beruehrt
     * (Weggabelung D).
     */
    @Test
    void derAdminHatEinEigenesRestkontingent() throws Exception {
        Long terminId = terminMitZusagen(8);
        String admin = adminSitzung();
        erwarteKontingent(kontingentGenerierung(admin, terminId), 1, 1);

        generieren(admin, terminId).andExpect(status().isCreated());

        erwarteKontingent(kontingentGenerierung(admin, terminId), 1, 0);
        erwarteKontingent(kontingentGenerierung(spielerSitzung(0), terminId), 1, 1);
    }

    /** Zwei Spieler beeinflussen sich nicht (Zusicherung: je Akteur). */
    @Test
    void dieKontingenteZweierAkteureBeeinflussenSichNicht() throws Exception {
        Long terminId = terminMitZusagen(8);
        generieren(spielerSitzung(0), terminId).andExpect(status().isCreated());

        erwarteKontingent(kontingentGenerierung(spielerSitzung(0), terminId), 1, 0);
        erwarteKontingent(kontingentGenerierung(spielerSitzung(1), terminId), 1, 1);
    }

    /**
     * Wer nicht generieren darf, bekommt {@code null} - nicht {@code uebrig = 0}.
     *
     * <p>Die Ausgabe darf sich vom Fall "erschoepft" unterscheiden: Dort hilft eine neue
     * Zusage, hier nicht. Die Bedingung ist dieselbe wie bei {@code 403 KEINE_ZUSAGE}.
     */
    @Test
    void ohneBerechtigungIstDasRestkontingentNull() throws Exception {
        Long terminId = terminMitZusagen(8);

        assertThat(kontingentGenerierung(spielerSitzung(8), terminId))
                .as("nie zugesagt").isNull();
        assertThat(kontingentGenerierung(gastSitzung("Testgast 1"), terminId))
                .as("Gast ohne Zusage").isNull();

        rueckmeldung(spielerSitzung(7), terminId, false).andExpect(status().isNoContent());
        assertThat(kontingentGenerierung(spielerSitzung(7), terminId))
                .as("wieder abgesagt").isNull();
    }

    /** Wartende mit Zusage duerfen generieren und haben deshalb ein Kontingent. */
    @Test
    void wartendeHabenEinRestkontingent() throws Exception {
        maxTeilnehmerSetzen(6);
        Long terminId = terminMitZusagen(8);

        erwarteKontingent(kontingentGenerierung(spielerSitzung(7), terminId), 1, 1);
    }

    /** An einem abgesagten oder abgeschlossenen Termin gibt es nichts zu sperren: {@code null}. */
    @Test
    void anEinemNichtGeplantenTerminIstDasRestkontingentNull() throws Exception {
        Long abgesagt = terminMitZusagen(8);
        jdbc.update("UPDATE spieltag.termin SET status = 'ABGESAGT', version = version + 1 WHERE id = ?",
                abgesagt);
        assertThat(kontingentGenerierung(spielerSitzung(0), abgesagt)).isNull();

        Long abgeschlossen = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 1));
        zusageAnlegen(abgeschlossen, spielerId(0), 1);
        jdbc.update("UPDATE spieltag.termin SET status = 'ABGESCHLOSSEN', version = version + 1 WHERE id = ?",
                abgeschlossen);
        assertThat(kontingentGenerierung(spielerSitzung(0), abgeschlossen)).isNull();
    }

    /**
     * Senkt der Admin die Grenze unter die verbrauchte Anzahl, steht {@code 0} da, nie eine
     * negative Zahl (Zusicherung 3).
     *
     * <p>Der Zustand wird ueber die Tabelle hergestellt, nicht ueber Laeufe: Die Grenze
     * gehoert vor den ersten Aufruf (Klassen-JavaDoc), und drei Laeufe unter einer Grenze von
     * drei, danach die Senkung, liesse sich ueber den Endpunkt nicht ohne Zwischenspeicher-
     * Eingriff aufbauen.
     */
    @Test
    void eineGesenkteGrenzeErgibtNullUndNieEinenNegativenWert() throws Exception {
        anzTeamGeneratorSetzen(1);
        Long terminId = terminMitZusagen(8);
        jdbc.update("""
                INSERT INTO spieltag.generierung_kontingent
                       (termin_id, akteur_spieler_id, akteur_gast_slot_id, kontingent_version, anzahl)
                VALUES (?, ?, NULL, 0, 3)
                """, terminId, spielerId(0));

        erwarteKontingent(kontingentGenerierung(spielerSitzung(0), terminId), 1, 0);
    }

    /**
     * Die Uebersicht traegt das Feld nicht: Eine Abfrage je Listeneintrag waere der Preis
     * (Zusicherung 6). Die Feldliste der Uebersicht ist in
     * {@code TerminControllerTests#listeTraegtKeineSkillwerte} vollstaendig festgelegt.
     */
    @Test
    void dieUebersichtTraegtKeinRestkontingent() throws Exception {
        terminMitZusagen(8);

        String antwort = mockMvc.perform(get("/api/v1/termine/lesen")
                        .cookie(new Cookie(COOKIE, spielerSitzung(0))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).doesNotContain("kontingentGenerierung");
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

    /** Muss wie {@link #maxTeilnehmerSetzen} vor dem ersten HTTP-Aufruf laufen. */
    private void anzTeamGeneratorSetzen(int wert) {
        jdbc.update("""
                UPDATE configs.app_config SET anz_team_generator = ?, version = version + 1 WHERE id = 1
                """, wert);
    }

    /** Das Feld {@code kontingentGenerierung} der Einzelansicht; ein Pflichtfeld, auch mit {@code null}. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> kontingentGenerierung(String token, Long terminId) throws Exception {
        Map<String, Object> termin = einzeln(token, terminId);
        assertThat(termin).as("Pflichtfeld, auch mit null").containsKey("kontingentGenerierung");
        return (Map<String, Object>) termin.get("kontingentGenerierung");
    }

    /** Prueft Werte und, dass das Objekt genau zwei Felder traegt (nur Zahlen der eigenen Sitzung). */
    private static void erwarteKontingent(Map<String, Object> kontingent, int erlaubt, int uebrig) {
        assertThat(kontingent).isNotNull().containsOnlyKeys("erlaubt", "uebrig");
        assertThat(((Number) kontingent.get("erlaubt")).intValue()).as("erlaubt").isEqualTo(erlaubt);
        assertThat(((Number) kontingent.get("uebrig")).intValue()).as("uebrig").isEqualTo(uebrig);
    }

    private int kontingentVersion(Long terminId) {
        return jdbc.queryForObject(
                "SELECT kontingent_version FROM spieltag.termin WHERE id = ?", Integer.class, terminId);
    }

    private int teilnehmerVersion(Long terminId) {
        return jdbc.queryForObject(
                "SELECT teilnehmer_version FROM spieltag.termin WHERE id = ?", Integer.class, terminId);
    }

    private int anzahlKontingentzeilen(Long terminId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM spieltag.generierung_kontingent WHERE termin_id = ?",
                Integer.class, terminId);
    }

    /** Aendert einen Skillwert ueber den echten Admin-Endpunkt. */
    private void skillAendern(Long spielerId, int angriff) throws Exception {
        mockMvc.perform(post("/api/v1/admin/user/bearbeiten")
                        .cookie(new Cookie(COOKIE, adminSitzung()))
                        .header("CF-Connecting-IP", "198.51.100.60")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spielerId\":%d,\"skills\":{\"ANGRIFF\":%d}}"
                                .formatted(spielerId, angriff)))
                .andExpect(status().isNoContent());
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
