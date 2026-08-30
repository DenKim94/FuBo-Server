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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die beiden lesenden Terminendpunkte aus {@code S4_UMSETZUNG.md}, Abschnitt 2.
 *
 * <h2>Warum die Termine hier per SQL entstehen und nicht ueber den Adminendpunkt</h2>
 * Der Lesepfad soll unabhaengig vom Schreibpfad pruefbar bleiben: Ein Fehler beim Anlegen
 * duerfte nicht zwoelf Lesetests mitreissen. Ausserdem braucht diese Klasse einen Termin in
 * der <i>Vergangenheit</i> - und genau den lehnt {@code /admin/termin/anlegen} ab.
 * Dasselbe gilt fuer die Teilnahmen: Der Endpunkt dafuer entsteht erst mit S4-Paket 5.
 *
 * <h2>Eigener Zeitstreifen je Testklasse</h2>
 * {@code uq_termin_zeit UNIQUE (datum, uhrzeit)} ist <b>global</b>. Zwei Testklassen, die
 * beide "morgen um 20:00" anlegen, kollidieren - auch wenn jede fuer sich zurueckgerollt
 * wird, laufen sie nicht zwingend nacheinander. Diese Klasse arbeitet deshalb ausschliesslich
 * um {@link #BASIS_TAGE} herum und ausschliesslich zur {@link #UHRZEIT};
 * {@code TerminVerwaltungControllerTests} hat beides anders.
 *
 * <h2>Testdaten</h2>
 * Gastnamen sind neutral ("Testgast ..."), Profile stammen aus den Demodaten. Keine realen
 * Personennamen. Fuer Zeitgrenzen kein {@code Thread.sleep} - die Termine liegen weit genug
 * von "jetzt" entfernt, dass die Zeitzone der Anwendung keine Rolle spielt.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TerminControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Abstand zum heutigen Tag; haelt diese Klasse von den uebrigen fern. */
    private static final int BASIS_TAGE = 40;

    /** Uhrzeit aller Termine dieser Klasse - zweite Haelfte des Kollisionsschutzes. */
    private static final LocalTime UHRZEIT = LocalTime.of(18, 15);

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

    // --------------------------------------------------------------------- Liste

    /**
     * Ohne Stichtag enthaelt die Liste nur, was noch kommt.
     *
     * <p>Der Vorgabewert haelt die Antwort klein; die Historie bleibt ueber {@code ab}
     * erreichbar, und S6 wird sie fuer die Ergebnisanzeige brauchen.
     */
    @Test
    void listeEnthaeltNurKuenftigeTermine() throws Exception {
        Long vergangen = terminAnlegen(LocalDate.now().minusDays(BASIS_TAGE), null);
        Long kuenftig = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE), null);

        List<Long> ids = idsAus(liste(spielerSitzung(), null));

        assertThat(ids).contains(kuenftig).doesNotContain(vergangen);
    }

    /** Mit einem Stichtag in der Vergangenheit wird die Historie sichtbar. */
    @Test
    void stichtagOeffnetDieHistorie() throws Exception {
        Long vergangen = terminAnlegen(LocalDate.now().minusDays(BASIS_TAGE), null);

        List<Long> ids = idsAus(liste(spielerSitzung(),
                LocalDate.now().minusDays(BASIS_TAGE + 1).toString()));

        assertThat(ids).contains(vergangen);
    }

    /**
     * Die Liste kommt nach Datum und Uhrzeit sortiert - dieselbe Reihenfolge, in der das
     * Dashboard sie anzeigt.
     */
    @Test
    void listeIstNachZeitpunktSortiert() throws Exception {
        Long spaeter = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 7), null);
        Long frueher = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 1), null);

        List<Long> ids = idsAus(liste(spielerSitzung(), null));

        assertThat(ids.indexOf(frueher)).isLessThan(ids.indexOf(spaeter));
    }

    /**
     * Abgesagte Termine verschwinden nicht.
     *
     * <p>Der Spieler soll sehen, dass sein Training ausfaellt, statt einen Termin
     * vorzufinden, der spurlos verschwunden ist; {@code status} unterscheidet sie.
     */
    @Test
    void abgesagteTermineBleibenInDerListe() throws Exception {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 2), null);
        jdbc.update("UPDATE spieltag.termin SET status = 'ABGESAGT' WHERE id = ?", terminId);

        Map<String, Object> termin = ausListe(liste(spielerSitzung(), null), terminId);

        assertThat(termin.get("status")).isEqualTo("ABGESAGT");
    }

    /**
     * Zusagen werden gezaehlt, Absagen nicht - und die eigene Rueckmeldung kommt mit.
     *
     * <p>Das ist der Grund fuer {@code count(*) FILTER (WHERE tn.zusage)} statt
     * {@code count(tn.id)}: Letzteres zaehlte die Absage des zweiten Profils mit.
     */
    @Test
    void listeZaehltNurZusagenUndLiefertDieEigeneRueckmeldung() throws Exception {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 3), null);
        Long eigenes = spielerId(0);
        Long fremdes = spielerId(1);

        teilnahmeAnlegen(terminId, eigenes, true);
        teilnahmeAnlegen(terminId, fremdes, false);

        Map<String, Object> termin = ausListe(
                liste(sitzungFuer(eigenes), null), terminId);

        assertThat(zahl(termin, "zusagen")).isEqualTo(1);
        assertThat(termin.get("eigeneRueckmeldung")).isEqualTo(true);
    }

    /**
     * Wer abgesagt hat, sieht {@code false}; wer gar nicht geantwortet hat, sieht
     * {@code null}.
     *
     * <p><b>Der Kern des dreiwertigen Feldes.</b> Ein {@code if (eigeneRueckmeldung)} im
     * Frontend behandelte beide gleich und zeigte eine Absage an, wo niemand geantwortet hat.
     */
    @Test
    void eigeneRueckmeldungUnterscheidetDreiZustaende() throws Exception {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 4), null);
        Long abgesagt = spielerId(0);
        Long stumm = spielerId(1);

        teilnahmeAnlegen(terminId, abgesagt, false);

        assertThat(ausListe(liste(sitzungFuer(abgesagt), null), terminId).get("eigeneRueckmeldung"))
                .isEqualTo(false);
        assertThat(ausListe(liste(sitzungFuer(stumm), null), terminId))
                .containsEntry("eigeneRueckmeldung", null);
    }

    /**
     * Die Rueckmeldung eines Fremden bleibt fremd.
     *
     * <p>Der Filter der Aggregation vergleicht {@code spieler_id} und {@code gast_name} mit
     * den Werten der aufrufenden Sitzung. Waere er einmal versehentlich weggelassen, lieferte
     * die Abfrage irgendeine Rueckmeldung - und der Fehler saehe aus wie ein
     * Anzeigeproblem.
     */
    @Test
    void fremdeRueckmeldungErscheintNichtAlsEigene() throws Exception {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 5), null);
        Long fremdes = spielerId(0);
        Long eigenes = spielerId(1);

        teilnahmeAnlegen(terminId, fremdes, true);

        Map<String, Object> termin = ausListe(liste(sitzungFuer(eigenes), null), terminId);

        assertThat(zahl(termin, "zusagen")).isEqualTo(1);
        assertThat(termin).containsEntry("eigeneRueckmeldung", null);
    }

    /**
     * Ein Gast sieht die Termine - und seine eigene Rueckmeldung.
     *
     * <p>Weggabelung F: Ein Gast, der Termine nicht sieht, kann nicht zusagen; A8 und A17
     * waeren ohne Zweck. Sein Anker ist der Gastname, nicht eine Profil-Id.
     */
    @Test
    void gastSiehtTermineUndSeineEigeneRueckmeldung() throws Exception {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 6), null);
        String gastName = "Testgast Cesar";
        gastTeilnahmeAnlegen(terminId, gastName, true);

        Map<String, Object> termin = ausListe(liste(gastSitzung(gastName), null), terminId);

        assertThat(termin.get("eigeneRueckmeldung")).isEqualTo(true);
        assertThat(zahl(termin, "zusagen")).isEqualTo(1);
    }

    /**
     * Die Antwort traegt keine Skillwerte und keine Teilnehmernamen (A12).
     *
     * <p>Der Pruefpunkt seit S3 ist nicht "kommen Skillwerte vor", sondern "kommen sie
     * ausserhalb von {@code /admin/} vor". Dieser Endpunkt liegt ausserhalb; die
     * Feldliste wird deshalb vollstaendig geprueft und nicht nur auf einzelne Namen.
     */
    @Test
    void listeTraegtKeineSkillwerte() throws Exception {
        terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 8), "Sporthalle Nord");

        List<Map<String, Object>> termine = liste(spielerSitzung(), null);

        assertThat(termine).isNotEmpty();
        assertThat(termine.getFirst().keySet()).containsExactlyInAnyOrder(
                "terminId", "serieId", "datum", "uhrzeit", "ort", "status",
                "teilnehmerVersion", "zusagen", "eigeneRueckmeldung");
    }

    // --------------------------------------------------------------------- Einzelansicht

    /**
     * Die Einzelansicht liefert zusaetzlich die {@code version} - den Wert, den
     * {@code /admin/termin/aendern} zurueckverlangt.
     */
    @Test
    void einzelansichtLiefertDieVersion() throws Exception {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 9), "Sporthalle Nord");

        Map<String, Object> termin = einzeln(spielerSitzung(), terminId);

        assertThat(zahl(termin, "terminId")).isEqualTo(terminId.intValue());
        assertThat(termin.get("ort")).isEqualTo("Sporthalle Nord");
        assertThat(termin.get("version")).isNotNull();
    }

    /**
     * Die Teilnehmerliste fehlt noch - sie gehoert zu S4-Paket 7.
     *
     * <p>Der Fall steht hier, damit das Nachziehen auffaellt: Sobald das Feld dazukommt,
     * schlaegt er fehl und wird zur Zusicherung umgedreht, statt unbemerkt gruen zu bleiben.
     */
    @Test
    void einzelansichtTraegtNochKeineTeilnehmerliste() throws Exception {
        Long terminId = terminAnlegen(LocalDate.now().plusDays(BASIS_TAGE + 10), null);

        assertThat(einzeln(spielerSitzung(), terminId)).doesNotContainKey("teilnehmer");
    }

    /** Eine unbekannte Id liefert {@code 404}, nicht eine leere Antwort. */
    @Test
    void einzelansichtUnbekannterIdLiefert404() throws Exception {
        mockMvc.perform(get("/api/v1/termine/999999999/lesen")
                        .cookie(new Cookie(COOKIE, spielerSitzung())))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------- Zugriff

    /** Ohne Sitzung ist der Bereich gesperrt - die Filterchain greift auch hier. */
    @Test
    void ohneSitzungLiefert401() throws Exception {
        mockMvc.perform(get("/api/v1/termine/lesen"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Eine Sitzung in {@code PIN_VERIFIED} reicht nicht.
     *
     * <p>Sie darf nur die Namensliste lesen und eine Identitaet waehlen. Termine gehoeren
     * zur zweiten Stufe - {@code 403}, nicht {@code 401}: Die Sitzung ist gueltig, es fehlt
     * die Stufe.
     */
    @Test
    void pinVerifiedDarfKeineTermineLesen() throws Exception {
        mockMvc.perform(get("/api/v1/termine/lesen")
                        .cookie(new Cookie(COOKIE, sessionService.anlegen(Stage.PIN_VERIFIED, null, null))))
                .andExpect(status().isForbidden());
    }

    /** Ein unlesbarer Stichtag ist eine Eingabe- und keine Serverstoerung. */
    @Test
    void unlesbarerStichtagLiefert400() throws Exception {
        mockMvc.perform(get("/api/v1/termine/lesen")
                        .param("ab", "01.09.2026")
                        .cookie(new Cookie(COOKIE, spielerSitzung())))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------- Hilfsmittel

    /** Legt einen Termin unmittelbar in der Datenbank an und liefert seine Id. */
    private Long terminAnlegen(LocalDate datum, String ort) {
        return jdbc.queryForObject("""
                INSERT INTO spieltag.termin (datum, uhrzeit, ort)
                VALUES (?, ?, ?)
                RETURNING id
                """, Long.class, datum, UHRZEIT, ort);
    }

    private void teilnahmeAnlegen(Long terminId, Long spielerId, boolean zusage) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, spieler_id, zusage)
                VALUES (?, ?, ?)
                """, terminId, spielerId, zusage);
    }

    private void gastTeilnahmeAnlegen(Long terminId, String gastName, boolean zusage) {
        jdbc.update("""
                INSERT INTO spieltag.teilnahme (termin_id, gast_name, gast_stufe, zusage)
                VALUES (?, ?, 'MITTEL', ?)
                """, terminId, gastName, zusage);
    }

    private List<Map<String, Object>> liste(String token, String ab) throws Exception {
        var anfrage = get("/api/v1/termine/lesen").cookie(new Cookie(COOKIE, token));
        if (ab != null) {
            anfrage = anfrage.param("ab", ab);
        }
        String antwort = mockMvc.perform(anfrage)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    private Map<String, Object> einzeln(String token, Long terminId) throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/termine/%d/lesen".formatted(terminId))
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(antwort, new TypeReference<>() {
        });
    }

    /** Sucht einen Termin in der Liste; schlaegt fehl, wenn er nicht darin steht. */
    private static Map<String, Object> ausListe(List<Map<String, Object>> termine, Long terminId) {
        return termine.stream()
                .filter(t -> ((Number) t.get("terminId")).longValue() == terminId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Termin " + terminId + " fehlt in der Liste"));
    }

    private static List<Long> idsAus(List<Map<String, Object>> termine) {
        return termine.stream().map(t -> ((Number) t.get("terminId")).longValue()).toList();
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

    private String spielerSitzung() {
        return sitzungFuer(spielerId(0));
    }

    private String sitzungFuer(Long spielerId) {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId, Rolle.USER);
    }

    /**
     * Meldet einen Gast ueber den echten Endpunkt an und liefert dessen Token.
     *
     * <p>Ueber den Endpunkt und nicht ueber {@code SessionService#anlegen}: Nur so entstehen
     * {@code gast_name} und {@code gast_stufe} in der Sitzungszeile, und genau daran haengt
     * die Rueckmeldung eines Gastes. <b>Der Token rotiert dabei</b> - der zurueckgegebene ist
     * der neue, der eingesetzte ist danach wertlos.
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

    /** Zahl aus der JSON-Antwort; Jackson liefert je nach Groesse Integer oder Long. */
    private static int zahl(Map<String, Object> karte, String feld) {
        return ((Number) karte.get(feld)).intValue();
    }
}
