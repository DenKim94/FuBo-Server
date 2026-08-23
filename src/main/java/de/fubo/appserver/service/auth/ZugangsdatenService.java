package de.fubo.appserver.service.auth;

import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.service.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Pflege der Anmeldedaten durch den angemeldeten Admin (A3, A22, S2b).
 *
 * <p>Die Klasse ist die Anwendungsfall-Schicht ueber {@link AdminService} (Admin-Passwort in
 * {@code profil.admin_konto}) und {@link PinService} (zentrale PIN in
 * {@code profil.zugangsdaten}). Beide wissen, <i>wie</i> ein Wert gesetzt wird; hier steht,
 * <i>was sonst noch dazugehoert</i> - Sitzungswiderruf und Protokolleintrag - und dass
 * beides in derselben Transaktion geschieht.
 *
 * <h2>Zwei Geheimnisse, zwei Reichweiten</h2>
 * <table border="1">
 *   <caption>Wirkung der beiden Aenderungen</caption>
 *   <tr><th>Aenderung</th><th>Widerrufene Sitzungen</th><th>Begruendung</th></tr>
 *   <tr>
 *     <td>Admin-Passwort</td>
 *     <td>nur die des Adminprofils</td>
 *     <td>Das Passwort betrifft ausschliesslich den Adminzugang. Spieler und Gaeste ohne
 *         Grund abzumelden waere ein Schaden ohne Nutzen.</td>
 *   </tr>
 *   <tr>
 *     <td>zentrale PIN</td>
 *     <td>ausnahmslos alle</td>
 *     <td>Sonst blieben Nutzer angemeldet, die nur die alte PIN kannten - der Wechsel waere
 *         wirkungslos.</td>
 *   </tr>
 * </table>
 * (Entschieden zu offenem Punkt 5 der S2b-Anleitung.)
 */
@Service
public class ZugangsdatenService {

    /** Betroffene Entitaet im Audit-Log beim Passwortwechsel. */
    private static final String ENTITAET_ADMIN_KONTO = "admin_konto";

    /** Betroffene Entitaet im Audit-Log beim Wechsel der zentralen PIN. */
    private static final String ENTITAET_ZUGANGSDATEN = "zugangsdaten";

    private final AdminService adminService;
    private final PinService pinService;
    private final SessionService sessionService;
    private final AuditService auditService;

    public ZugangsdatenService(AdminService adminService,
                               PinService pinService,
                               SessionService sessionService,
                               AuditService auditService) {
        this.adminService = adminService;
        this.pinService = pinService;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    /**
     * Setzt ein neues Admin-Passwort im angemeldeten Zustand (Abschnitt 6).
     *
     * <p>Die Pruefung des <i>alten</i> Passworts liegt beim Controller - dort, wo auch der
     * Brute-Force-Zaehler und das Protokoll des Fehlversuchs sitzen, weil beide einen
     * Rollback ueberleben muessen. Hier ist bereits entschieden, dass geaendert wird.
     *
     * <p><b>Auch die aufrufende Sitzung wird widerrufen.</b> Das ist Absicht: Nach einem
     * Passwortwechsel soll sich der Admin einmal mit dem neuen Passwort anmelden - sonst
     * merkt ein Tippfehler im neuen Passwort erst beim naechsten Anmelden, moeglicherweise
     * Wochen spaeter.
     *
     * @param neuesPasswort Klartext; die Laengengrenzen prueft die Bean Validation am DTO
     * @param clientIp      Adresse des Aufrufers, fuer das Protokoll
     */
    @Transactional
    public void adminPasswortAendern(String neuesPasswort, String clientIp) {
        adminService.passwortSetzen(neuesPasswort);

        Long adminSpielerId = adminService.adminSpielerId();
        sessionService.widerrufenFuerSpieler(adminSpielerId);

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.PASSWORT_GEAENDERT,
                ENTITAET_ADMIN_KONTO, (long) AdminService.ADMIN_KONTO_ID,
                Map.of("weg", "aendern"));
    }

    /**
     * Setzt eine neue zentrale PIN (Abschnitt 7, A3).
     *
     * <p>{@code PinService#setzen} traegt seit S2 den Hinweis, dass der Aufrufer
     * anschliessend alle Sitzungen widerrufen muss. Genau das geschieht hier - und in
     * derselben Transaktion: Fielen Setzen und Widerruf auseinander, blieben Nutzer mit der
     * alten PIN angemeldet, und der Wechsel waere fuer die Dauer ihrer Sitzung wirkungslos.
     *
     * <p>{@code zugangsdaten.geaendert_von} bekommt hier zum ersten Mal einen Wert; beim
     * Start-Bootstrap bleibt die Spalte leer, weil es zu dem Zeitpunkt noch kein Admin-Konto
     * gibt.
     *
     * <p><b>Folge fuer den Betrieb:</b> Nach der Aenderung sind alle abgemeldet und brauchen
     * die neue PIN ueber den externen Weg. Das gehoert als Hinweis in die Bestaetigung im
     * Frontend, nicht als Ueberraschung.
     *
     * @param neuePin  Klartext; das Format prueft die Bean Validation am DTO
     * @param clientIp Adresse des Aufrufers, fuer das Protokoll
     */
    @Transactional
    public void zentralePinAendern(String neuePin, String clientIp) {
        pinService.setzen(neuePin, AdminService.ADMIN_KONTO_ID);
        sessionService.alleWiderrufen();

        auditService.protokolliere(adminService.adminSpielerId(), clientIp,
                AuditAktion.PIN_GEAENDERT,
                ENTITAET_ZUGANGSDATEN, (long) PinService.ZUGANGSDATEN_ID,
                Map.of("endpunkt", "/admin/pin/aendern"));
    }
}
