package de.fubo.appserver.service.auth;

import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.profil.SpielerVerwaltungService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <tr>
 *     <td>Anmeldename des Admins (S3)</td>
 *     <td><b>keine</b></td>
 *     <td>Vorgabe des Haupt-Entwicklers vom 29.08.2026. Der Name ist zwar ein Anmeldemerkmal,
 *         aber kein Geheimnis, dessen Bekanntwerden allein Zugang verschafft. Ein Widerruf
 *         wuerfe den Admin unmittelbar nach der Umbenennung aus seiner eigenen Sitzung; es
 *         genuegt, dass die Aenderung beim naechsten Login greift.</td>
 *   </tr>
 * </table>
 * (Die ersten beiden Zeilen entschieden zu offenem Punkt 5 der S2b-Anleitung.)
 */
@Service
public class ZugangsdatenService {

    private static final Logger LOG = LoggerFactory.getLogger(ZugangsdatenService.class);

    /** Betroffene Entitaet im Audit-Log beim Passwortwechsel. */
    private static final String ENTITAET_ADMIN_KONTO = "admin_konto";

    /** Betroffene Entitaet im Audit-Log beim Wechsel der zentralen PIN. */
    private static final String ENTITAET_ZUGANGSDATEN = "zugangsdaten";

    /** Betroffene Entitaet im Audit-Log beim Wechsel des Anmeldenamens. */
    private static final String ENTITAET_SPIELER = "spieler";

    private final AdminService adminService;
    private final PinService pinService;
    private final SessionService sessionService;
    private final SpielerVerwaltungService spielerVerwaltungService;
    private final AuditService auditService;

    public ZugangsdatenService(AdminService adminService,
                               PinService pinService,
                               SessionService sessionService,
                               SpielerVerwaltungService spielerVerwaltungService,
                               AuditService auditService) {
        this.adminService = adminService;
        this.pinService = pinService;
        this.sessionService = sessionService;
        this.spielerVerwaltungService = spielerVerwaltungService;
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
     * Aendert den Anmeldenamen des Admins (S3, Vorgabe des Haupt-Entwicklers vom 29.08.2026).
     *
     * <h2>Warum das hier steht und nicht in {@code /admin/user/bearbeiten}</h2>
     * Der Name des Adminprofils ist seit dem 29.08.2026 zugleich der Anmeldename fuer
     * {@code /auth/admin/anmelden}. Damit ist seine Aenderung eine <b>Zugangsdatenpflege</b>
     * und keine Stammdatenpflege - er gehoert neben Passwort und zentrale PIN, nicht neben
     * die Skillwerte. {@code /admin/user/bearbeiten} lehnt das Adminprofil deshalb
     * vollstaendig ab ({@code 409 PROFIL_GESCHUETZT}).
     *
     * <h2>Die Sitzung bleibt bestehen</h2>
     * Anders als bei der Passwortaenderung wird <b>nichts</b> widerrufen. Der Name ist kein
     * Geheimnis, dessen Bekanntwerden allein Zugang verschafft; ein Widerruf wuerfe den Admin
     * unmittelbar nach seiner eigenen Umbenennung aus der Sitzung. Es genuegt, dass die
     * Aenderung beim naechsten Login greift.
     *
     * <p><b>Ein aktuelles Passwort wird nicht verlangt</b> - dasselbe Muster wie bei
     * {@link #zentralePinAendern}, das die zentrale PIN ohne erneute Passworteingabe setzt.
     * Nur die Passwortaenderung selbst fragt das alte Passwort ab, und zwar weil dort das
     * Geheimnis ersetzt wird, mit dem man sich gerade ausgewiesen hat.
     *
     * <h2>Betriebshinweis, der in die Dokumentation gehoert</h2>
     * Nach der Umbenennung ist {@code ADMIN_NAME} in der {@code .env} veraltet. Fuer den
     * laufenden Betrieb ist das folgenlos - {@code AdminBootstrap} liest den Wert nur,
     * solange {@code profil.admin_konto} leer ist. Wird diese Zeile je neu aufgebaut (frische
     * Installation, Wiederherstellung ohne sie), sucht der Bootstrap wieder unter
     * {@code ADMIN_NAME} und braecht ab, weil das umbenannte Profil bereits die Rolle
     * {@code ADMIN} traegt. Deshalb steht der alte und der neue Name im Protokoll <b>und</b>
     * im Log.
     *
     * @param neuerName bereits getrimmter neuer Name
     * @param clientIp  Adresse des Aufrufers, fuer das Protokoll
     * @throws de.fubo.appserver.common.error.FachlicherFehler {@code 409 NAME_BELEGT}, wenn
     *         ein anderes Profil diesen Namen traegt
     */
    @Transactional
    public void adminNameAendern(String neuerName, String clientIp) {
        String alterName = spielerVerwaltungService.adminProfilUmbenennen(neuerName);

        Long adminSpielerId = adminService.adminSpielerId();

        LOG.warn("Anmeldename des Admins geaendert: '{}' -> '{}'. ADMIN_NAME in der .env ist "
                        + "damit veraltet und sollte nachgezogen werden.",
                alterName, neuerName);

        auditService.protokolliere(adminSpielerId, clientIp, AuditAktion.ADMIN_NAME_GEAENDERT,
                ENTITAET_SPIELER, adminSpielerId,
                Map.of("nameAlt", alterName, "nameNeu", neuerName));
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
