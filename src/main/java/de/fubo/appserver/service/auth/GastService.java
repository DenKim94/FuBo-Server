package de.fubo.appserver.service.auth;

import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.repository.auth.GastSlotRepository;
import de.fubo.appserver.repository.auth.SessionRepository;
import de.fubo.appserver.repository.profil.SpielerRepository;
import de.fubo.appserver.service.config.ConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gast-Login: temporaere Identitaet ohne Profil (A8, A17).
 *
 * <p>Ein Gast durchlaeuft dieselben zwei Stufen wie ein Spieler - er kennt die zentrale
 * PIN, waehlt aber statt eines Profils einen selbst eingegebenen Namen und eine
 * Selbsteinschaetzung. Die Skillwerte dazu stammen aus {@code profil.gast_vorlage} und
 * werden erst bei der Teamgenerierung (S5) herangezogen; die Sitzung merkt sich nur die
 * Stufe.
 *
 * <p><b>Das Abmelden liegt bewusst nicht hier</b>, sondern im {@code SessionService}: Dort
 * gehoeren Freigabe des Platzes und Widerruf der Sitzung in dieselbe Transaktion, und der
 * Endpunkt zum Abmelden gilt fuer alle Rollen gleichermassen.
 */
@Service
public class GastService {

    private final SessionRepository sessionRepository;
    private final SpielerRepository spielerRepository;
    private final GastSlotRepository gastSlotRepository;
    private final SessionService sessionService;
    private final ConfigService configService;

    public GastService(SessionRepository sessionRepository,
                       SpielerRepository spielerRepository,
                       GastSlotRepository gastSlotRepository,
                       SessionService sessionService,
                       ConfigService configService) {
        this.sessionRepository = sessionRepository;
        this.spielerRepository = spielerRepository;
        this.gastSlotRepository = gastSlotRepository;
        this.sessionService = sessionService;
        this.configService = configService;
    }

    /**
     * Hebt eine Sitzung von {@code PIN_VERIFIED} auf {@code PROFILE_AUTHENTICATED} mit der
     * Rolle {@code GAST}, belegt einen Gastplatz und rotiert den Token.
     *
     * <p><b>Die Reihenfolge ist bedeutsam.</b> Zuerst wird die Sitzung zur Gastsitzung,
     * danach der Platz belegt - {@code gast_slot.session_id} zeigt auf die Sitzung, die
     * Zeile muss also bereits in ihrem Endzustand sein. Scheitert die Platzbelegung, rollt
     * die gemeinsame Transaktion auch den Stufenwechsel zurueck; die Sitzung bleibt in
     * {@code PIN_VERIFIED}, und der Gast kann es nach einer Abmeldung eines anderen Gastes
     * erneut versuchen.
     *
     * <p><b>Warum die Namenspruefung im Service liegt und keine Datenbankbedingung ist:</b>
     * Ein Unique-Index ueber {@code session (gast_name)} traefe auch abgelaufene Sitzungen
     * und blockierte den Namen dauerhaft; ein partieller Index auf
     * {@code WHERE widerrufen_am IS NULL} hat dasselbe Problem, weil eine abgelaufene
     * Sitzung ebenfalls nicht widerrufen ist. Es bleibt dieselbe schmale Restluecke wie bei
     * der Namensauswahl (siehe {@link NamenService#waehleName}); die Folge waere zwei Gaeste
     * mit gleichem Namen in der Liste, kein Datenverlust.
     *
     * @param sessionId Id der aufrufenden Sitzung aus dem Sicherheitskontext
     * @param gastName  bereits bereinigter Anzeigename
     * @param stufe     Selbsteinschaetzung des Gastes
     * @return der neue Klartext-Token fuer das Cookie
     * @throws FachlicherFehler {@code 409 NAME_BELEGT}, wenn der Name schon vergeben ist;
     *                          {@code 409 KEIN_GAST_SLOT_FREI}, wenn alle Plaetze belegt sind;
     *                          {@code 401 SESSION_UNGUELTIG}, wenn die Sitzung zwischenzeitlich
     *                          ungueltig wurde
     */
    @Transactional
    public String alsGastAnmelden(Long sessionId, String gastName, GastStufe stufe) {
        pruefeNameFrei(gastName);

        int geaendert = sessionRepository.aufGastSetzen(sessionId, gastName, stufe.name());
        if (geaendert == 0) {
            // Die WHERE-Klausel verlangt stage = 'PIN_VERIFIED' und eine nicht widerrufene
            // Sitzung. Null geaenderte Zeilen bedeuten also: zwischen Filterchain und
            // Schreibzugriff ungueltig geworden - oder die Sitzung hat bereits eine
            // Identitaet. Fuer den Aufrufer ist beides dasselbe: neu anmelden.
            throw new FachlicherFehler(Fehlercode.SESSION_UNGUELTIG);
        }

        int maxGaeste = configService.lesen().getAnzGuests();
        if (gastSlotRepository.freienSlotBelegen(sessionId, maxGaeste) == 0) {
            throw new FachlicherFehler(Fehlercode.KEIN_GAST_SLOT_FREI);
        }

        return sessionService.rotieren(sessionId);
    }

    /**
     * Lehnt einen Namen ab, der bereits von einer aktiven Gastsitzung oder von einem
     * angelegten Spielerprofil belegt ist.
     *
     * <p><b>Warum auch die Profile geprueft werden:</b> Ein Gast, der sich "Spieler 07"
     * nennt, waere in Teilnehmerliste und Teameinteilung nicht mehr von dem Spieler dieses
     * Namens zu unterscheiden. Geprueft wird gegen <i>alle</i> Profile, auch inaktive -
     * ein deaktiviertes Profil kann jederzeit wieder aktiviert werden, und dann stuenden
     * beide Namen nebeneinander.
     */
    private void pruefeNameFrei(String gastName) {
        if (sessionRepository.existiertAktiveGastSitzungMit(gastName)
                || spielerRepository.existsByNameIgnoreCase(gastName)) {
            throw new FachlicherFehler(Fehlercode.NAME_BELEGT,
                    "Dieser Name ist bereits vergeben. Bitte einen anderen waehlen.");
        }
    }
}
