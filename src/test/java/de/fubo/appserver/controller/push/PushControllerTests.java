package de.fubo.appserver.controller.push;

import de.fubo.appserver.database.TestcontainersConfiguration;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.service.auth.SessionService;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die fuenf Nutzerendpunkte unter {@code /api/v1/push} und den Probeversand
 * (A25, S8 Abschnitt 7; Pruefpunkte 4 bis 14 aus Abschnitt 12.3).
 *
 * <h2>Warum die Klasse {@code @Transactional} tragen darf</h2>
 * Kein Endpunkt dieser Klasse versendet etwas. {@code /push/schluessel/lesen} antwortet im
 * Testprofil mit {@code 503}, und {@code /admin/push/test} kommt hier nur als
 * <b>Berechtigungsfall</b> vor - mit einer Spielersitzung, die ihn gar nicht erreicht. Es laeuft
 * also nichts in einer eigenen Transaktion, und jeder Fall wird sauber zurueckgerollt. Was
 * ausserhalb der Transaktion stattfindet, prueft {@code PushVersandTests} - und traegt deshalb
 * kein {@code @Transactional}.
 *
 * <h2>Diese Klasse braucht keinen Zeitstreifen</h2>
 * {@code uq_termin_zeit UNIQUE (datum, uhrzeit)} ist global und zwingt jede Testklasse, die
 * Termine anlegt, zu einem eigenen Zeitfenster. <b>Diese hier legt keine an</b> - ein Abonnement
 * gehoert zu einer Person und einem Geraet, nicht zu einem Termin. Sollte ein kuenftiger Fall
 * einen Termin brauchen, ist das ein Hinweis auf eine eingeschlichene Abhaengigkeit und kein
 * Grund, hier einen Zeitstreifen nachzutragen.
 *
 * <h2>Das Testprofil hat bewusst keine VAPID-Schluessel</h2>
 * {@code src/test/resources/application.yml} laesst die drei Werte leer. Das ist kein Mangel,
 * sondern der Zustand, den Pruefpunkt 12 braucht: <b>ohne Schluessel {@code 503
 * PUSH_NICHT_KONFIGURIERT}</b>, und zwar an genau den zwei Endpunkten, die ohne sie nichts
 * Sinnvolles tun koennen. Die Klasse, die den Versand prueft, setzt sich ihre eigenen.
 *
 * <h2>Testdaten</h2>
 * Keine realen Personennamen - die Sitzungen haengen an den anonymisierten Beispielprofilen.
 * Die Endpoint-Adressen liegen unter {@code example.invalid} (RFC 2606) und sind nicht
 * aufloesbar; {@code p256dh} und {@code auth} sind die veroeffentlichten Beispielwerte aus
 * RFC 8291, Anhang A und gehoeren zu keinem Geraet.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PushControllerTests {

    private static final String COOKIE = "FUBO_SESSION";

    /** Erste Endpoint-Adresse; ihr SHA-256 ist der Schluessel, ueber den nachgesehen wird. */
    private static final String ENDPOINT =
            "https://push.example.invalid/wpush/v2/geraet-eins";

    private static final String ZWEITER_ENDPOINT =
            "https://push.example.invalid/wpush/v2/geraet-zwei";

    /** Oeffentlicher Schluessel eines Abonnements; 87 Zeichen base64url (RFC 8291, Anhang A). */
    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";

    /** {@code auth}-Geheimnis; 22 Zeichen base64url fuer 16 Byte. */
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    /** Ein Browserkennzeichen, aus dem die Heuristik "Firefox auf Android" macht. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Android 14; Mobile; rv:127.0) Gecko/127.0 Firefox/127.0";

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

    // ==================================================================== Anlegen

    /**
     * Pruefpunkt 4, erste Haelfte: Anlegen legt an - mit allen fuenf Werten.
     *
     * <p>Geprueft wird auch die Geraetebezeichnung: Sie entsteht aus dem {@code User-Agent} und
     * ist reine Anzeigehilfe fuer die spaetere Geraeteliste. <b>Kein Wert der Anwendung haengt an
     * ihr</b> - deshalb genuegt eine Heuristik, und deshalb steht sie hier in einem Fall mit und
     * nicht in einem eigenen.
     */
    @Test
    void anlegenLegtEineZeileAn() throws Exception {
        String token = sitzung(ersterSpieler());

        anlegen(token, ENDPOINT).andExpect(status().isOk());

        Map<String, Object> zeile = aboZeile(ENDPOINT);
        assertThat(zeile.get("spieler_id")).isEqualTo(ersterSpieler());
        assertThat(zeile.get("endpoint")).isEqualTo(ENDPOINT);
        assertThat(zeile.get("p256dh")).isEqualTo(P256DH);
        assertThat(zeile.get("auth")).isEqualTo(AUTH);
        assertThat(zeile.get("geraet_bezeichnung")).isEqualTo("Firefox auf Android");
        assertThat(zeile.get("deaktiviert_am")).isNull();
    }

    /**
     * Pruefpunkt 4, zweite Haelfte: Derselbe Endpoint zweimal ergibt <b>eine</b> Zeile.
     *
     * <p>Das ist keine Bequemlichkeit, sondern die einzige Form, die zum Browser passt: Die
     * Push-API des Browsers liefert bei jedem Seitenaufruf dieselbe Anmeldung zurueck, und das
     * Frontend schickt sie ohne zu wissen, ob sie schon bekannt ist. Ohne
     * {@code ON CONFLICT ... DO UPDATE} waechst die Tabelle bei jedem Besuch - oder der Aufruf
     * endet in einem {@code 500} wegen des Unique-Constraints.
     */
    @Test
    void derselbeEndpointZweimalErgibtGenauEineZeile() throws Exception {
        String token = sitzung(ersterSpieler());

        anlegen(token, ENDPOINT).andExpect(status().isOk());
        anlegen(token, ENDPOINT).andExpect(status().isOk());

        assertThat(aboAnzahl(ENDPOINT)).isEqualTo(1);
    }

    /**
     * Pruefpunkt 5: Ein deaktiviertes Abonnement wird durch erneutes Anlegen wieder aktiv, und
     * der Fehlversuchszaehler faellt auf {@code 0}.
     *
     * <p><b>Das ist der Normalfall nach einem {@code 410}</b>, kein Randfall: Der Push-Dienst
     * erklaert ein Abonnement fuer erloschen, die Anwendung deaktiviert es - und derselbe Browser
     * meldet sich beim naechsten Besuch neu an, oft mit derselben Adresse. Ohne das
     * Zuruecksetzen bliebe die Zeile fuer immer stumm, obwohl das Geraet wieder empfangsbereit
     * ist. Der Zaehler muss mit zurueck: Er misst die Gesundheit des Abonnements, und die
     * Neuanmeldung ist die Aussage, dass es wieder gesund ist.
     */
    @Test
    void einDeaktiviertesAboWirdDurchErneutesAnlegenWiederAktiv() throws Exception {
        String token = sitzung(ersterSpieler());
        anlegen(token, ENDPOINT).andExpect(status().isOk());

        jdbc.update("""
                UPDATE profil.push_abo
                   SET deaktiviert_am = now(), fehlversuche = 5, version = version + 1
                 WHERE endpoint_hash = ?
                """, TokenGenerator.hash(ENDPOINT));

        anlegen(token, ENDPOINT).andExpect(status().isOk());

        Map<String, Object> zeile = aboZeile(ENDPOINT);
        assertThat(zeile.get("deaktiviert_am")).as("wieder aktiv").isNull();
        assertThat(zahl(zeile, "fehlversuche")).as("der Zaehler faellt mit zurueck").isZero();
    }

    /**
     * Pruefpunkt 6: Derselbe Endpoint, anderer Spieler - die Zeile wechselt den Eigentuemer.
     *
     * <p>Der Grund steht in {@code V014}: <b>Die Adresse identifiziert eine Browserinstallation,
     * keine Person.</b> Auf einem geteilten Geraet meldet sich ein zweiter Spieler an; bliebe die
     * Zeile beim ersten, bekaeme der die Nachrichten weiter - auf einem Geraet, das er nicht mehr
     * benutzt, und mit Angaben zu einem Training, zu dem er nichts gesagt hat. Der
     * Unique-Constraint gilt deshalb global und nicht je Spieler.
     */
    @Test
    void derselbeEndpointMitAnderemSpielerWechseltDenEigentuemer() throws Exception {
        anlegen(sitzung(ersterSpieler()), ENDPOINT).andExpect(status().isOk());

        anlegen(sitzung(zweiterSpieler()), ENDPOINT).andExpect(status().isOk());

        assertThat(aboAnzahl(ENDPOINT)).isEqualTo(1);
        assertThat(aboZeile(ENDPOINT).get("spieler_id")).isEqualTo(zweiterSpieler());
    }

    // ==================================================================== Entfernen

    /**
     * Pruefpunkt 7: Entfernen mit einem fremden Endpoint laesst die Zeile stehen - und antwortet
     * trotzdem {@code 200}.
     *
     * <p><b>Die Bedingung {@code AND spieler_id = :spielerId} im DELETE ist die eigentliche
     * Pruefung</b>, nicht eine vorgeschaltete Abfrage: Sonst loeschte ein Spieler mit einer
     * geratenen Adresse das Abonnement eines anderen.
     *
     * <p>Warum trotzdem {@code 200} und nicht {@code 404}: Ein {@code 404} verriete, dass es die
     * Adresse gibt und sie jemand anderem gehoert - und das Frontend koennte damit nichts
     * anfangen. Der Aufruf bedeutet "dieses Geraet soll nichts mehr bekommen", und dieser Zustand
     * gilt danach.
     */
    @Test
    void entfernenMitFremdemEndpointLaesstDieZeileStehenUndAntwortet200() throws Exception {
        anlegen(sitzung(ersterSpieler()), ENDPOINT).andExpect(status().isOk());

        entfernen(sitzung(zweiterSpieler()), ENDPOINT).andExpect(status().isOk());

        assertThat(aboAnzahl(ENDPOINT)).as("die Zeile des anderen bleibt").isEqualTo(1);
        assertThat(aboZeile(ENDPOINT).get("spieler_id")).isEqualTo(ersterSpieler());
    }

    /**
     * Pruefpunkt 8: Ein unbekannter Endpoint wird mit {@code 200} beantwortet.
     *
     * <p>Derselbe Gedanke wie oben, und er hat einen alltaeglichen Anlass: Der Browser widerruft
     * seine Anmeldung selbst, das Frontend meldet den Widerruf hinterher - und findet nichts mehr
     * vor. Das ist kein Fehler, sondern der gewuenschte Zustand.
     */
    @Test
    void entfernenEinesUnbekanntenEndpointsAntwortet200() throws Exception {
        entfernen(sitzung(ersterSpieler()), ZWEITER_ENDPOINT).andExpect(status().isOk());

        assertThat(aboAnzahl(ZWEITER_ENDPOINT)).isZero();
    }

    // ==================================================================== Eingabepruefung

    /**
     * Pruefpunkt 9, erster Teil: Eine Adresse ohne {@code https://} wird abgelehnt, und die
     * Antwort nennt das Feld.
     *
     * <p>Die Adresse wandert unveraendert in einen {@code HttpRequest}. Ohne die Pruefung stuende
     * dort, was der Client geschickt hat - bis hin zu {@code file:} oder einer Adresse im eigenen
     * Netz. Der Feldname in der Antwort ist kein Beiwerk: Das Frontend kann ihn an das richtige
     * Eingabefeld haengen, und im Protokoll steht damit, welcher Wert falsch war.
     */
    @Test
    void einEndpointOhneHttpsLiefert400MitFeldangabe() throws Exception {
        String antwort = anlegen(sitzung(ersterSpieler()),
                "http://push.example.invalid/wpush/v2/unsicher")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("endpoint");
    }

    /**
     * Pruefpunkt 9, zweiter Teil: Ein zu kurzer {@code p256dh} wird abgelehnt.
     *
     * <p>Der Wert ist ein unkomprimierter P-256-Punkt: 65 Byte, in base64url 87 Zeichen. Die
     * Grenzen am DTO sind bewusst weiter gefasst (80 bis 120) - sie fangen Unsinn ab, ohne eine
     * kuenftige Kodierung auszuschliessen. <b>Ohne sie faende der Fehler erst beim Versand
     * statt</b>, dort als Ausnahme im Verschluesselungsschritt, und das Abonnement stuende
     * unbrauchbar in der Tabelle, bis fuenf Fehlversuche es deaktivieren.
     */
    @Test
    void einZuKurzerP256dhLiefert400MitFeldangabe() throws Exception {
        String antwort = anlegen(sitzung(ersterSpieler()), ENDPOINT, "BCVxsr7N", AUTH)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("p256dh");
    }

    /** Pruefpunkt 9, dritter Teil: Ein fehlendes {@code auth} wird abgelehnt. */
    @Test
    void einFehlendesAuthLiefert400MitFeldangabe() throws Exception {
        String antwort = mockMvc.perform(post("/api/v1/push/abo/anlegen")
                        .cookie(new Cookie(COOKIE, sitzung(ersterSpieler())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"%s","p256dh":"%s"}
                                """.formatted(ENDPOINT, P256DH)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"EINGABE_UNGUELTIG\"").contains("auth");
    }

    /**
     * Pruefpunkt 10: Ein Rumpf ohne {@code pushErwuenscht} liefert {@code 400} - und wird
     * <b>nicht</b> stillschweigend als {@code false} gelesen.
     *
     * <p>Derselbe Grund wie bei {@code hallenModusAktiv} in {@code KonfigurationControllerTests}:
     * Der Record fuehrt einen {@code Boolean} und keinen {@code boolean}. Ein primitiver
     * Wahrheitswert waere bei fehlendem Feld {@code false}, und ein Client, der das Feld nicht
     * kennt, schaltete bei jedem Speichern die Benachrichtigungen ab. Bei Zahlenfeldern faengt
     * das {@code @Min} den Fall ab; fuer einen Wahrheitswert gibt es keine Untergrenze.
     */
    @Test
    void einstellungOhnePushErwuenschtLiefert400() throws Exception {
        Long spielerId = ersterSpieler();

        mockMvc.perform(post("/api/v1/push/einstellung/aendern")
                        .cookie(new Cookie(COOKIE, sitzung(spielerId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(pushErwuenscht(spielerId))
                .as("der abgelehnte Aufruf hat nichts geschrieben")
                .isTrue();
    }

    // ==================================================================== Schalter und Status

    /** Der Personenschalter wird geschrieben und in beide Richtungen zurueckgemeldet. */
    @Test
    void einstellungAendernSchreibtDenPersonenschalter() throws Exception {
        Long spielerId = ersterSpieler();
        String token = sitzung(spielerId);

        assertThat(alsKarte(einstellung(token, false).andExpect(status().isOk()))
                .get("pushErwuenscht")).isEqualTo(false);
        assertThat(pushErwuenscht(spielerId)).isFalse();

        assertThat(alsKarte(einstellung(token, true).andExpect(status().isOk()))
                .get("pushErwuenscht")).isEqualTo(true);
        assertThat(pushErwuenscht(spielerId)).isTrue();
    }

    /**
     * Pruefpunkt 11: Der Status liefert beide Ebenen <b>getrennt</b> und aendert nichts.
     *
     * <p>Das ist die Antwort auf die erste der drei Eigenschaften dieses Meilensteins: <b>Ein
     * Fehler bleibt still.</b> "Es kommt nichts an" hat mindestens drei Ursachen - die Anlage
     * versendet nicht, die Person will nicht, das Geraet ist nicht angemeldet -, und alle drei
     * sehen von aussen gleich aus. Eine zusammengefasste Auskunft ("Push: aus") liesse die Frage
     * offen, an welchem der drei Schalter es liegt. Der dritte, das Abonnement selbst, kennt nur
     * der Browser - deshalb zwei Ebenen hier und die dritte dort.
     */
    @Test
    void statusLiefertBeideEbenenGetrennt() throws Exception {
        Long spielerId = ersterSpieler();
        jdbc.update("""
                UPDATE configs.app_config SET push_aktiv = false, version = version + 1
                 WHERE id = 1
                """);

        // Die oertliche Variable heisst nicht status: Der statische Import
        // MockMvcResultMatchers.status() liegt in einem anderen Namensraum und waere zwar
        // weiterhin aufloesbar - aber zwei Bedeutungen desselben Wortes in vier Zeilen liest
        // niemand gern zweimal.
        Map<String, Object> zustand = alsKarte(
                statusLesen(sitzung(spielerId)).andExpect(status().isOk()));

        assertThat(zustand.get("anlageAktiv")).as("Hauptschalter der Anlage").isEqualTo(false);
        assertThat(zustand.get("pushErwuenscht")).as("Schalter dieser Person").isEqualTo(true);
        assertThat(pushErwuenscht(spielerId)).as("Lesen aendert nichts").isTrue();
    }

    /**
     * Pruefpunkt 12: Ohne eingerichtete VAPID-Schluessel antwortet der Schluesselendpunkt mit
     * {@code 503 PUSH_NICHT_KONFIGURIERT}.
     *
     * <p><b>{@code 503} und nicht {@code 404} oder ein leerer Wert</b>: Der Endpunkt gibt es,
     * er ist nur voruebergehend nicht bedienbar - und der Grund liegt am Server, nicht an der
     * Anfrage. Das Frontend kann daran die Schaltflaeche ausblenden, statt einen
     * Browserdialog zu oeffnen, der zu nichts fuehrt. Der eigene Fehlercode macht den Fall im
     * Protokoll von einem echten Ausfall unterscheidbar.
     */
    @Test
    void schluesselLesenOhneVapidLiefert503() throws Exception {
        String antwort = mockMvc.perform(get("/api/v1/push/schluessel/lesen")
                        .cookie(new Cookie(COOKIE, sitzung(ersterSpieler()))))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertThat(antwort).contains("\"code\":\"PUSH_NICHT_KONFIGURIERT\"");
    }

    // ==================================================================== Berechtigungen

    /**
     * Pruefpunkt 13: Eine Gastsitzung bekommt an <b>jedem</b> der fuenf Pfade {@code 403} (A25d).
     *
     * <p><b>Hier kehrt sich das uebliche Fehlerbild der Filterchain um.</b> Sonst ist die
     * gefaehrliche Richtung "zu offen vergessen"; bei Push ist es die Voreinstellung selbst: Die
     * Kette endet auf {@code anyRequest().hasAnyRole("USER", "ADMIN", "GAST")}. Ohne eine eigene
     * Regel fuer {@code /api/*&#47;push/**} waeren die Pfade fuer Gaeste <b>offen</b> - und der
     * Fehler faende sich nicht im Code, sondern in seinem Fehlen.
     *
     * <p>Die Datenbank haelt dagegen: {@code push_abo.spieler_id} ist ein
     * {@code NOT NULL}-Fremdschluessel, eine Gastsitzung hat keine Zeile in {@code profil.spieler}.
     * <b>Das ergaebe aber einen {@code 500} statt eines {@code 403}</b> - ein Riegel, kein
     * Berechtigungsmodell.
     */
    @Test
    void eineGastsitzungBekommtAnAllenFuenfPfaden403() throws Exception {
        String gast = gastSitzung();

        mockMvc.perform(get("/api/v1/push/schluessel/lesen").cookie(new Cookie(COOKIE, gast)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/push/status/lesen").cookie(new Cookie(COOKIE, gast)))
                .andExpect(status().isForbidden());

        anlegen(gast, ENDPOINT).andExpect(status().isForbidden());
        entfernen(gast, ENDPOINT).andExpect(status().isForbidden());
        einstellung(gast, false).andExpect(status().isForbidden());

        assertThat(aboAnzahl(ENDPOINT)).as("nichts ist angelegt worden").isZero();
    }

    /**
     * Pruefpunkt 14: Der Probeversand ist Adminsache - eine Spielersitzung bekommt {@code 403}.
     *
     * <p>Er liegt unter {@code /api/v1/admin/push/test} und damit im Geltungsbereich von
     * {@code /api/*&#47;admin/**}. <b>Der Ort des Endpunkts ist die Berechtigungsentscheidung</b>,
     * es gibt keine zusaetzliche Pruefung im Controller - genau deshalb steht dieser Pfad
     * namentlich in einer Zusicherung und nicht nur am Platzhalterpfad in
     * {@code SecurityConfigTests}.
     */
    @Test
    void probeversandAlsUserLiefert403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/push/test")
                        .cookie(new Cookie(COOKIE, sitzung(ersterSpieler()))))
                .andExpect(status().isForbidden());
    }

    // ==================================================================== Aufrufe

    private ResultActions anlegen(String token, String endpoint) throws Exception {
        return anlegen(token, endpoint, P256DH, AUTH);
    }

    private ResultActions anlegen(String token, String endpoint, String p256dh, String auth)
            throws Exception {
        return mockMvc.perform(post("/api/v1/push/abo/anlegen")
                .cookie(new Cookie(COOKIE, token))
                .header("User-Agent", USER_AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"endpoint":"%s","p256dh":"%s","auth":"%s"}
                        """.formatted(endpoint, p256dh, auth)));
    }

    private ResultActions entfernen(String token, String endpoint) throws Exception {
        return mockMvc.perform(post("/api/v1/push/abo/entfernen")
                .cookie(new Cookie(COOKIE, token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"%s\"}".formatted(endpoint)));
    }

    private ResultActions einstellung(String token, boolean erwuenscht) throws Exception {
        return mockMvc.perform(post("/api/v1/push/einstellung/aendern")
                .cookie(new Cookie(COOKIE, token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pushErwuenscht\":%s}".formatted(erwuenscht)));
    }

    private ResultActions statusLesen(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/push/status/lesen").cookie(new Cookie(COOKIE, token)));
    }

    // ==================================================================== Auswertung

    private Map<String, Object> alsKarte(ResultActions ergebnis) throws Exception {
        return objectMapper.readValue(
                ergebnis.andReturn().getResponse().getContentAsString(), new TypeReference<>() {
                });
    }

    private Map<String, Object> aboZeile(String endpoint) {
        return jdbc.queryForMap("SELECT * FROM profil.push_abo WHERE endpoint_hash = ?",
                TokenGenerator.hash(endpoint));
    }

    private int aboAnzahl(String endpoint) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM profil.push_abo WHERE endpoint_hash = ?",
                Integer.class, TokenGenerator.hash(endpoint));
    }

    private boolean pushErwuenscht(Long spielerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT push_erwuenscht FROM profil.spieler WHERE id = ?", Boolean.class,
                spielerId));
    }

    /** Zahl aus einer Datenbankzeile; der Treiber liefert SMALLINT je nach Fassung verschieden. */
    private static int zahl(Map<String, Object> zeile, String name) {
        return ((Number) zeile.get(name)).intValue();
    }

    // ==================================================================== Sitzungen

    private String sitzung(Long spielerId) {
        return sessionService.anlegen(Stage.PROFILE_AUTHENTICATED, spielerId, Rolle.USER);
    }

    /**
     * Gastsitzung ohne Profilbezug - dieselbe Form wie in {@code SecurityConfigTests}.
     *
     * <p>Der Umweg ueber ein {@code UPDATE} statt eines Aufrufs von
     * {@code /auth/gast/anmelden}: Jener Endpunkt vergibt einen Gastplatz und haengt damit an der
     * Konfiguration. Dieser Fall prueft die Filterchain und soll nicht an der Zahl freier
     * Gastplaetze scheitern.
     */
    private String gastSitzung() {
        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);
        jdbc.update("""
                UPDATE profil.session
                   SET stage = 'PROFILE_AUTHENTICATED', rolle = 'GAST', gast_name = 'Testgast 1'
                 WHERE token_hash = ?
                """, TokenGenerator.hash(token));
        return token;
    }

    private Long ersterSpieler() {
        return spielerNach(0);
    }

    private Long zweiterSpieler() {
        return spielerNach(1);
    }

    /** Ein aktives Spielerprofil (keine realen Namen, keine Adminzeile). */
    private Long spielerNach(int uebersprungen) {
        return jdbc.queryForObject("""
                SELECT id FROM profil.spieler
                 WHERE rolle = 'USER' AND aktiv
                 ORDER BY name
                 LIMIT 1 OFFSET ?
                """, Long.class, uebersprungen);
    }
}
