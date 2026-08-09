package de.fubo.appserver.service.auth;

import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Rolle;
import de.fubo.appserver.domain.auth.Session;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.repository.auth.SessionRepository;
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
    private final ConfigService configService;

    public SessionService(SessionRepository sessionRepository, ConfigService configService) {
        this.sessionRepository = sessionRepository;
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

    /** Widerruft eine einzelne Sitzung (Logout). */
    @Transactional
    public void widerrufen(Long sessionId) {
        sessionRepository.widerrufen(sessionId);
    }

    /**
     * Widerruft alle offenen Sitzungen, etwa nach einem Wechsel der zentralen PIN.
     * Genau diese sofortige Widerrufbarkeit ist der Grund fuer den serverseitigen
     * Token statt eines JWT.
     */
    @Transactional
    public void alleWiderrufen() {
        int anzahl = sessionRepository.alleWiderrufen();
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
     * <p>TODO (Abschnitt 8): Zusaetzlich die Gast-Slots abgelaufener Gastsitzungen
     * freigeben, sonst laufen die vier Plaetze ueber die Zeit voll.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void alteSitzungenEntfernen() {
        int anzahl = sessionRepository.loescheAelterAls(
                OffsetDateTime.now().minusDays(AUFBEWAHRUNG_TAGE));
        LOG.info("Abgelaufene Sitzungen entfernt: {}", anzahl);
    }
}
