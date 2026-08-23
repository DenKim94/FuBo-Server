package de.fubo.appserver.service.auth;

import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Session;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.dto.auth.SitzungInfo;
import de.fubo.appserver.repository.auth.GastSlotRepository;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import de.fubo.appserver.service.config.ConfigService;
import de.fubo.appserver.utils.TokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Verwaltet serverseitige Sitzungen: Anlegen, Pruefen, Rotieren, Widerrufen, Aufraeumen.
 *
 * <p>Der Klartext-Token verlaesst den Server ausschliesslich als Rueckgabewert von
 * {@link #anlegen} und {@link #rotieren}; in der Datenbank steht nur sein SHA-256-Hash.
 * Der Service enthaelt kein SQL - die fachliche Bedingung der Sitzungspruefung steckt in
 * der WHERE-Klausel im Repository.
 */
@Service
public class SessionService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    /** Abgelaufene Sitzungen werden erst nach dieser Frist geloescht. */
    private static final int AUFBEWAHRUNG_TAGE = 1;

    private final SessionRepository sessionRepository;
    private final GastSlotRepository gastSlotRepository;
    private final SpielerRepository spielerRepository;
    private final ConfigService configService;

    public SessionService(SessionRepository sessionRepository,
                          GastSlotRepository gastSlotRepository,
                          SpielerRepository spielerRepository,
                          ConfigService configService) {
        this.sessionRepository = sessionRepository;
        this.gastSlotRepository = gastSlotRepository;
        this.spielerRepository = spielerRepository;
        this.configService = configService;
    }

    /**
     * Legt eine neue Sitzung an und liefert den Klartext-Token zurueck.
     *
     * @param stage     Login-Stufe; nach der PIN-Pruefung {@link Stage#PIN_VERIFIED}
     * @param spielerId Profil-Id oder {@code null}, solange keine Identitaet gewaehlt ist
     * @param rolle     Rolle oder {@code null} in der Stufe {@link Stage#PIN_VERIFIED}
     * @return der opake Token fuer das HttpOnly-Cookie - der einzige Ort, an dem er
     *         im Klartext existiert
     */
    @Transactional
    public String anlegen(Stage stage, Long spielerId, Rolle rolle) {
        AppConfig cfg = configService.lesen();
        OffsetDateTime jetzt = OffsetDateTime.now();

        String token = TokenGenerator.erzeugeToken();

        Session sitzung = new Session();
        sitzung.setTokenHash(TokenGenerator.hash(token));
        sitzung.setStage(stage);
        sitzung.setSpielerId(spielerId);
        sitzung.setRolle(rolle);
        sitzung.setGueltigBis(jetzt.plusMinutes(cfg.getSessionLeerlaufMinuten()));
        sitzung.setAbsolutGueltigBis(jetzt.plusHours(cfg.getSessionMaximalStunden()));

        // saveAndFlush statt save: Die Pruefung laeuft ueber nativen JDBC-Zugriff und
        // sieht nur, was tatsaechlich in der Datenbank steht. Bei IDENTITY setzt
        // Hibernate das INSERT zwar ohnehin sofort ab, um den Schluessel zu erhalten -
        // sich darauf zu verlassen waere aber eine unsichtbare Kopplung an die
        // Generierungsstrategie.
        sessionRepository.saveAndFlush(sitzung);

        return token;
    }

    /**
     * Prueft das Session-Cookie und verschiebt das Leerlauf-Fenster nach hinten.
     *
     * <p>Wird bei jedem Request aus der Filterchain aufgerufen. Ein leeres Ergebnis
     * bedeutet: unbekannt, widerrufen oder abgelaufen - der Aufrufer darf daraus keine
     * Rueckschluesse ziehen und antwortet einheitlich mit {@code 401}.
     *
     * @param token Klartext-Token aus dem Cookie
     */
    @Transactional
    public Optional<AktiveSitzung> pruefenUndVerlaengern(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int leerlauf = configService.lesen().getSessionLeerlaufMinuten();
        return sessionRepository.pruefenUndVerlaengern(TokenGenerator.hash(token), leerlauf);
    }

    /**
     * Prueft das Session-Cookie, <b>ohne</b> das Leerlauf-Fenster zu verschieben.
     *
     * <p>Fuer Hintergrundaufrufe des Frontends, die den Header
     * {@code X-FuBo-Kein-Refresh: true} tragen - allen voran das Pollen des Belegtstatus
     * (A6). Damit misst das gleitende Fenster die Untaetigkeit des <i>Nutzers</i> und nicht
     * die eines offenen Browser-Tabs (Abschnitt 10.8, entschieden am 22.08.2026).
     *
     * <p>Die Auskunft ist ansonsten identisch: Dieselben vier Bedingungen entscheiden ueber
     * Gueltigkeit, und ein leeres Ergebnis fuehrt genauso zu {@code 401}.
     *
     * @param token Klartext-Token aus dem Cookie
     */
    @Transactional(readOnly = true)
    public Optional<AktiveSitzung> pruefen(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.pruefen(TokenGenerator.hash(token));
    }

    /**
     * Tauscht den Token einer bestehenden Sitzung aus (Schutz vor Session Fixation).
     *
     * <p>Der Hash wird in der bestehenden Zeile ersetzt, statt eine neue Zeile anzulegen:
     * Die {@code id} bleibt erhalten und damit auch die Verknuepfung
     * {@code gast_slot.session_id}.
     *
     * @param sessionId Id der zu rotierenden Sitzung
     * @return der neue Klartext-Token
     * @throws IllegalStateException wenn die Sitzung nicht existiert oder widerrufen ist
     */
    @Transactional
    public String rotieren(Long sessionId) {
        String neuerToken = TokenGenerator.erzeugeToken();
        int geaendert = sessionRepository.tokenErsetzen(sessionId, TokenGenerator.hash(neuerToken));

        if (geaendert == 0) {
            throw new IllegalStateException(
                    "Sitzung " + sessionId + " existiert nicht oder ist bereits widerrufen.");
        }
        return neuerToken;
    }

    /**
     * Verlaengert eine Sitzung ausdruecklich und rotiert dabei den Token (A14).
     *
     * <p>Unterschied zur beilaeufigen Verlaengerung in {@link #pruefenUndVerlaengern}: Dort
     * wandert nur {@code gueltig_bis} mit, hier bekommt der Aufrufer zusaetzlich einen
     * neuen Token. Die Erneuerung ist der einzige Zeitpunkt, an dem der Nutzer bewusst
     * bestaetigt, dass er weiterarbeiten will - ein Tokenwechsel kostet dabei nichts und
     * begrenzt die Lebensdauer eines einzelnen Tokens.
     *
     * <p><b>Die harte Obergrenze verschiebt sich nicht.</b> {@code absolut_gueltig_bis}
     * bleibt unangetastet; die Erneuerung kann eine Sitzung also nicht endlos am Leben
     * halten. Genau dafuer existiert der zweite Timer.
     *
     * @param sessionId Id der aufrufenden Sitzung
     * @return der neue Klartext-Token
     */
    @Transactional
    public String erneuern(Long sessionId) {
        return rotieren(sessionId);
    }

    /**
     * Baut die Auskunft fuer {@code GET /auth/session/lesen}.
     *
     * <p>Die Sitzungsdaten selbst stammen aus dem Sicherheitskontext und wurden von der
     * Filterchain bereits gelesen - erneut nachzuschlagen waere ein zweiter Zugriff auf
     * dieselbe Zeile. Nachgelesen wird nur der Anzeigename eines Profils: Er steht in
     * {@code profil.spieler} und haette in der Sitzungspruefung, die bei <i>jedem</i>
     * Request laeuft, eine Verknuepfung erzwungen - fuer einen Wert, den genau ein Endpunkt
     * braucht.
     *
     * <p>Ein Gast hat kein Profil; sein Name steht in der Sitzung. In der Stufe
     * {@link Stage#PIN_VERIFIED} gibt es noch keine Identitaet und damit keinen Namen -
     * das Frontend erkennt daran, dass es die Namensauswahl anzeigen muss.
     *
     * @param sitzung geprueft aus dem Sicherheitskontext
     * @return Auskunft ohne Skillwerte, ohne Profil-Id und ohne Token
     */
    @Transactional(readOnly = true)
    public SitzungInfo auskunft(AktiveSitzung sitzung) {
        String anzeigeName = sitzung.gastName();

        if (anzeigeName == null && sitzung.spielerId() != null) {
            anzeigeName = spielerRepository.findById(sitzung.spielerId())
                    .map(Spieler::getName)
                    .orElse(null);
        }

        return new SitzungInfo(sitzung.stage(),
                sitzung.rolle(),
                anzeigeName,
                sitzung.gueltigBis(),
                sitzung.absolutGueltigBis());
    }

    /** Widerruft eine einzelne Sitzung. */
    @Transactional
    public void widerrufen(Long sessionId) {
        sessionRepository.widerrufen(sessionId);
    }

    /**
     * Meldet eine Sitzung ab: Ein belegter Gastplatz wird freigegeben, danach die Sitzung
     * widerrufen.
     *
     * <p><b>Beides gehoert in eine Transaktion.</b> Zwei getrennte Aufrufe koennten
     * auseinanderfallen - der Platz waere frei, die Sitzung aber weiter gueltig, und der
     * Gast zaehlte ohne Platz als angemeldet. Die Reihenfolge ist ebenfalls festgelegt: Der
     * Platz wird freigegeben, solange die Sitzung existiert, denn
     * {@code gast_slot.session_id} zeigt auf sie.
     *
     * <p>Der Aufruf ist fuer Sitzungen ohne Gastplatz folgenlos; der Endpunkt muss die
     * Rolle also nicht unterscheiden.
     *
     * @param sessionId Id der abzumeldenden Sitzung
     */
    @Transactional
    public void abmelden(Long sessionId) {
        gastSlotRepository.freigebenFuerSitzung(sessionId);
        sessionRepository.widerrufen(sessionId);
    }

    /**
     * Widerruft alle offenen Sitzungen, etwa nach einem Wechsel der zentralen PIN.
     * Genau diese sofortige Widerrufbarkeit ist der Grund fuer den serverseitigen
     * Token statt eines JWT.
     *
     * <p><b>Die Gastplaetze werden anschliessend freigegeben</b> (ergaenzt in S2b). Ohne
     * diesen Schritt blieben nach einem PIN-Wechsel bis zu vier Plaetze bis zum
     * naechtlichen Aufraeumlauf besetzt - von Sitzungen, die niemand mehr nutzen kann.
     * Bei vier Plaetzen faellt das sofort auf. Dieselbe Ueberlegung wie bei
     * {@link #abmelden(Long)}, nur fuer alle Sitzungen auf einmal.
     *
     * <p>Die Reihenfolge ist festgelegt und nicht umkehrbar: Die Freigabe erkennt ihre
     * Kandidaten daran, dass {@code widerrufen_am} gesetzt ist - sie muss also
     * <b>nach</b> dem Widerruf laufen.
     */
    @Transactional
    public void alleWiderrufen() {
        int anzahl = sessionRepository.alleWiderrufen();

        int freigegeben = gastSlotRepository.freigebenFuerAbgelaufeneSitzungen();
        if (freigegeben > 0) {
            LOG.info("Gastplaetze widerrufener Sitzungen freigegeben: {}", freigegeben);
        }
        LOG.info("Alle offenen Sitzungen widerrufen: {}", anzahl);
    }

    /** Widerruft alle offenen Sitzungen eines Profils, etwa bei dessen Deaktivierung. */
    @Transactional
    public void widerrufenFuerSpieler(Long spielerId) {
        sessionRepository.widerrufenFuerSpieler(spielerId);
    }

    /**
     * Entfernt abgelaufene Sitzungen. Zeilen werden nicht beim Logout geloescht, sondern
     * laufen ab; ohne diesen Job waechst die Tabelle unbegrenzt.
     *
     * <p><b>Die Freigabe der Gastplaetze steht bewusst davor</b> (offener Punkt 11,
     * erledigt am 22.08.2026) - und zwar aus zwei Gruenden. Fachlich: Eine Gastsitzung, die
     * ohne Abmeldung ablaeuft, haelt ihren Platz sonst weiter besetzt, und die vier Plaetze
     * liefen ueber die Zeit voll. Technisch: {@code fk_gast_slot_session} hat kein
     * {@code ON DELETE}; ein {@code DELETE} auf einer noch referenzierten Sitzung scheitert
     * mit einer Fremdschluesselverletzung und braeche den gesamten Aufraeumlauf ab.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void alteSitzungenEntfernen() {
        int freigegeben = gastSlotRepository.freigebenFuerAbgelaufeneSitzungen();
        if (freigegeben > 0) {
            LOG.info("Gastplaetze abgelaufener Sitzungen freigegeben: {}", freigegeben);
        }

        int anzahl = sessionRepository.loescheAelterAls(
                OffsetDateTime.now().minusDays(AUFBEWAHRUNG_TAGE));
        LOG.info("Abgelaufene Sitzungen entfernt: {}", anzahl);
    }
}
