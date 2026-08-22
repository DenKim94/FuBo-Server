package de.fubo.appserver.controller.auth;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.common.security.SessionCookieFactory;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.auth.AdminLoginRequest;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.auth.AdminService;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.utils.ClientIpErmittler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Adminzugang als zweite Login-Stufe (A22) - die Alternative zur Namensauswahl.
 *
 * <p><b>Zum Pfad:</b> {@code POST /api/{version}/auth/admin/anmelden}. Nicht zu verwechseln
 * mit {@code /api/{version}/admin/**}: Das ist der spaetere Adminbereich (S3), der
 * {@code ROLE_ADMIN} <i>voraussetzt</i>. Dieser Endpunkt hier <i>verleiht</i> die Rolle und
 * ist deshalb ausschliesslich in der Stufe {@code PIN_VERIFIED} erreichbar - genau wie die
 * Namensauswahl und der Gast-Login.
 *
 * <p><b>Keine Tokenpruefung in dieser Methode.</b> Wer hier ankommt, hat die Filterchain
 * bereits passiert.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/auth")
public class AdminController {

    private final AdminService adminService;
    private final BruteForceService bruteForceService;
    private final SessionCookieFactory sessionCookieFactory;
    private final AuditService auditService;

    public AdminController(AdminService adminService,
                           BruteForceService bruteForceService,
                           SessionCookieFactory sessionCookieFactory,
                           AuditService auditService) {
        this.adminService = adminService;
        this.bruteForceService = bruteForceService;
        this.sessionCookieFactory = sessionCookieFactory;
        this.auditService = auditService;
    }

    /**
     * Prueft das Admin-Passwort und hebt die Sitzung auf {@code PROFILE_AUTHENTICATED} mit
     * der Rolle {@code ADMIN}.
     *
     * <p>Der Ablauf ist derselbe wie beim PIN-Endpunkt, und aus denselben Gruenden:
     * <ol>
     *   <li>Sperre pruefen - eine gesperrte Anfrage soll gar nicht erst gegen den
     *       BCrypt-Hash rechnen, sonst waere die Drosselung selbst der Angriffsvektor.</li>
     *   <li>Passwort pruefen; bei Misserfolg zaehlen, protokollieren, ablehnen.</li>
     *   <li>Bei Erfolg den Zaehler der Adresse leeren und die Anmeldung protokollieren.</li>
     * </ol>
     *
     * <p><b>Der Brute-Force-Zaehler ist derselbe wie am PIN-Endpunkt</b> - bewusst. Es ist
     * derselbe Absender, der dieselbe Anwendung angreift; wer fuenf Admin-Passwoerter raet,
     * soll anschliessend auch keine PINs mehr durchprobieren koennen. Sichtbare Folge: Die
     * Ablehnung traegt den Code {@code PIN_GESPERRT}, obwohl sie hier auf ein Passwort
     * antwortet. Das ist kein Versehen, sondern die ehrliche Bezeichnung derselben Sperre.
     * Der Preis: Wer sich beim Admin-Passwort fuenfmal vertippt, wartet auch beim
     * PIN-Login eine Minute.
     *
     * <p><b>Zum Statuscode beim ausloesenden Fehlversuch:</b> Wie beim PIN-Endpunkt bleibt es
     * bei {@code 401}; die Sperre wirkt ab dem <i>naechsten</i> Aufruf. Andernfalls verriete
     * der Statuscode, an welcher Stelle die Zaehlung genau steht.
     *
     * @param anfrage Klartext-Passwort aus dem Anfragekoerper
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Sitzung aus dem Sicherheitskontext; nie {@code null}, weil
     *                die Filterchain den Zugriff sonst gar nicht durchgelassen haette
     * @return {@code 204} ohne Inhalt, dazu das rotierte Session-Cookie
     */
    @PostMapping(value = "/admin/anmelden", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> adminAnmelden(@Valid @RequestBody AdminLoginRequest anfrage,
                                              HttpServletRequest request,
                                              @AuthenticationPrincipal AktiveSitzung sitzung) {

        String clientIp = ClientIpErmittler.ermitteln(request);
        bruteForceService.pruefeGesperrt(clientIp);

        if (!adminService.passwortStimmt(anfrage.passwort())) {
            boolean sperreAusgeloest = bruteForceService.fehlversuchZaehlen(clientIp);
            auditService.protokolliere(
                    clientIp,
                    sperreAusgeloest ? AuditAktion.PIN_GESPERRT : AuditAktion.ADMIN_LOGIN_FEHLVERSUCH,
                    Map.of("endpunkt", "/auth/admin/anmelden"));

            throw new FachlicherFehler(Fehlercode.ADMIN_PASSWORT_FALSCH);
        }

        bruteForceService.zuruecksetzen(clientIp);

        String neuerToken = adminService.sitzungAufAdminHeben(sitzung.id());

        auditService.protokolliere(
                adminService.adminSpielerId(), clientIp, AuditAktion.ADMIN_ANGEMELDET,
                null, null, Map.of("endpunkt", "/auth/admin/anmelden"));

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.setzen(neuerToken).toString())
                .build();
    }
}
