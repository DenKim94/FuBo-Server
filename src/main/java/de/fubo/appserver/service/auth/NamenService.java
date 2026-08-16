package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.profil.Spieler;
import de.fubo.appserver.dto.profil.NameOption;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Zweite Stufe des Logins: Namensliste, Belegtstatus und Namensauswahl (A4, A6, A14).
 */
@Service
public class NamenService {

    private final SpielerRepository spielerRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;

    public NamenService(SpielerRepository spielerRepository,
                        SessionRepository sessionRepository,
                        SessionService sessionService) {
        this.spielerRepository = spielerRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
    }

    /**
     * Liefert alle aktiven Profile mit Belegtstatus, nach Namen sortiert.
     *
     * <p>Das ist zugleich die Umsetzung von "Status ONLINE/OFFLINE wird aus aktiven
     * Sessions abgeleitet": Es gibt keine gespeicherte Statusspalte, die auseinanderlaufen
     * koennte. Das Frontend pollt diesen Endpunkt (A6), ein Push-Kanal ist nicht noetig.
     */
    @Transactional(readOnly = true)
    public List<NameOption> namensliste() {
        return spielerRepository.findeNamensliste().stream()
                .map(eintrag -> new NameOption(eintrag.id(), eintrag.name(), eintrag.belegt()))
                .toList();
    }

    /**
     * Hebt eine Sitzung von {@code PIN_VERIFIED} auf {@code PROFILE_AUTHENTICATED} und
     * traegt das gewaehlte Profil ein. Der Token wird dabei ausgetauscht.
     *
     * <p><b>Warum die Token-Rotation?</b> Sie verhindert Session Fixation: Haette ein
     * Angreifer dem Opfer vorher ein von ihm erzeugtes Cookie untergeschoben, waere sein
     * Token nach der Anmeldung wertlos. Die {@code session.id} bleibt dabei erhalten - und
     * damit auch die Verknuepfung {@code gast_slot.session_id} (Abschnitt 8).
     *
     * <p><b>Bekannte Restluecke bei der Namensbelegung:</b> Zwischen der Pruefung und dem
     * {@code UPDATE} bleibt ein schmales Fenster, in dem zwei Anfragen denselben Namen
     * greifen koennten. Sauber schliessen liesse sich das nur mit einem partiellen
     * Unique-Index auf {@code session (spieler_id) WHERE widerrufen_am IS NULL} - der ist
     * im Schema nicht enthalten, weil abgelaufene Sitzungen dieselbe Bedingung erfuellen
     * und den Index dauerhaft blockieren wuerden. Entscheidung des Haupt-Entwicklers vom
     * 16.08.2026: Die Pruefung bleibt im Service, die Logik bleibt einfach. Bei zwanzig bis
     * dreissig Nutzern, die sich nacheinander anmelden, ist das Fenster praktisch
     * unerreichbar; die Folge waere ausserdem nur eine doppelte Anmeldung, kein Datenverlust.
     *
     * @param sessionId Id der aufrufenden Sitzung, aus dem Sicherheitskontext
     * @param spielerId Id des gewaehlten Profils
     * @return der neue Klartext-Token fuer das Cookie
     * @throws FachlicherFehler {@code 404}, wenn das Profil fehlt oder inaktiv ist;
     *                          {@code 409}, wenn der Name bereits belegt ist;
     *                          {@code 401}, wenn die Sitzung zwischenzeitlich ungueltig wurde
     */
    @Transactional
    public String nameWaehlen(Long sessionId, Long spielerId) {
        Spieler spieler = spielerRepository.findById(spielerId)
                .filter(Spieler::isAktiv)
                .orElseThrow(() -> new FachlicherFehler(Fehlercode.INHALT_NICHT_GEFUNDEN,
                        "Das gewaehlte Profil existiert nicht oder ist nicht aktiv."));

        if (sessionRepository.existiertAktiveSitzungFuer(spielerId)) {
            throw new FachlicherFehler(Fehlercode.NAME_BELEGT);
        }

        // Die WHERE-Klausel verlangt stage = 'PIN_VERIFIED' und eine nicht widerrufene
        // Sitzung. Null geaenderte Zeilen bedeuten also: Die Sitzung ist zwischen
        // Filterchain und Schreibzugriff ungueltig geworden - oder sie hat bereits eine
        // Identitaet. Beides ist fuer den Aufrufer dasselbe: neu anmelden.
        int geaendert = sessionRepository.aufProfileAuthenticatedSetzen(
                sessionId, spielerId, spieler.getRolle().name());

        if (geaendert == 0) {
            throw new FachlicherFehler(Fehlercode.SESSION_UNGUELTIG);
        }

        return sessionService.rotieren(sessionId);
    }
}
