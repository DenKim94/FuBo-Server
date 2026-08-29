package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.common.security.SessionCookieFactory;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.dto.admin.AdminNameAendernRequest;
import de.fubo.appserver.dto.admin.PasswortAendernRequest;
import de.fubo.appserver.dto.admin.PinAendernRequest;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.auth.AdminService;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.service.auth.ZugangsdatenService;
import de.fubo.appserver.utils.ClientIpErmittler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Pflege der Anmeldedaten durch den angemeldeten Admin (A3, A22).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/admin/...} - der Adminbereich. Die Filterchain
 * verlangt fuer alles unterhalb von {@code /api/*&#47;admin/**} die Rolle {@code ADMIN};
 * eine eigene Regel je Endpunkt braucht es deshalb nicht.
 *
 * <p>Nicht zu verwechseln mit {@code /api/{version}/auth/admin/anmelden} und
 * {@code /auth/passwort/...}: Jene Endpunkte <i>verleihen</i> die Rolle beziehungsweise
 * arbeiten ohne sie, dieser Bereich <i>setzt sie voraus</i>.
 *
 * <p><b>Beide Endpunkte melden den Aufrufer ab</b> und loeschen deshalb das Session-Cookie.
 * Das ist kein Nebeneffekt, sondern der Zweck: Wer ein Geheimnis wechselt, soll sich einmal
 * damit anmelden.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class ZugangsdatenController {

    private final ZugangsdatenService zugangsdatenService;
    private final AdminService adminService;
    private final BruteForceService bruteForceService;
    private final SessionCookieFactory sessionCookieFactory;
    private final AuditService auditService;

    public ZugangsdatenController(ZugangsdatenService zugangsdatenService,
                                  AdminService adminService,
                                  BruteForceService bruteForceService,
                                  SessionCookieFactory sessionCookieFactory,
                                  AuditService auditService) {
        this.zugangsdatenService = zugangsdatenService;
        this.adminService = adminService;
        this.bruteForceService = bruteForceService;
        this.sessionCookieFactory = sessionCookieFactory;
        this.auditService = auditService;
    }

    /**
     * Aendert das Admin-Passwort. Verlangt das bisherige Passwort.
     *
     * <p>Der Ablauf entspricht dem Admin-Login, und aus denselben Gruenden: Sperre pruefen,
     * bevor gegen den BCrypt-Hash gerechnet wird; bei Misserfolg zaehlen, protokollieren,
     * ablehnen; bei Erfolg den Zaehler der Adresse leeren.
     *
     * <p><b>Der Zaehler ist derselbe wie am PIN- und am Admin-Login.</b> Es ist derselbe
     * Absender, der dieselbe Anwendung angreift. Ein offener Rechner mit gueltiger Sitzung
     * ist genau der Fall, in dem jemand das alte Passwort durchprobiert - die Drosselung
     * gehoert deshalb auch hierher.
     *
     * @param anfrage altes und neues Passwort
     * @param request fuer die Ermittlung der Client-IP
     * @return {@code 204} ohne Inhalt; die Antwort loescht das Session-Cookie
     */
    @PostMapping(value = "/passwort/aendern", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> adminPasswortAendern(@Valid @RequestBody PasswortAendernRequest anfrage,
                                                     HttpServletRequest request) {

        String clientIp = ClientIpErmittler.ermitteln(request);
        bruteForceService.pruefeGesperrt(clientIp);

        if (!adminService.passwortStimmt(anfrage.altesPasswort())) {
            boolean sperreAusgeloest = bruteForceService.fehlversuchZaehlen(clientIp);
            auditService.protokolliere(
                    clientIp,
                    sperreAusgeloest ? AuditAktion.PIN_GESPERRT : AuditAktion.ADMIN_LOGIN_FEHLVERSUCH,
                    Map.of("endpunkt", "/admin/passwort/aendern"));

            throw new FachlicherFehler(Fehlercode.ADMIN_PASSWORT_FALSCH);
        }

        bruteForceService.zuruecksetzen(clientIp);
        zugangsdatenService.adminPasswortAendern(anfrage.neuesPasswort(), clientIp);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.loeschen().toString())
                .build();
    }

    /**
     * Setzt die zentrale PIN neu (A3).
     *
     * <p><b>Keine Pruefung eines alten Werts</b> und damit auch keine Drosselung: Es wird
     * nichts geraten, sondern gesetzt. Die Berechtigung dazu hat die Filterchain bereits
     * festgestellt.
     *
     * <p><b>Nach dem Aufruf sind ausnahmslos alle Sitzungen widerrufen</b> - auch die von
     * Spielern und Gaesten, und auch die aufrufende. Das Frontend sollte vorher darauf
     * hinweisen; im Betrieb bedeutet es, dass alle Beteiligten die neue PIN ueber den
     * externen Weg brauchen.
     *
     * @param anfrage neue zentrale PIN
     * @param request fuer die Ermittlung der Client-IP
     * @return {@code 204} ohne Inhalt; die Antwort loescht das Session-Cookie
     */
    @PostMapping(value = "/pin/aendern", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> zentralePinAendern(@Valid @RequestBody PinAendernRequest anfrage,
                                                   HttpServletRequest request) {

        zugangsdatenService.zentralePinAendern(
                anfrage.neuePin(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.loeschen().toString())
                .build();
    }

    /**
     * Aendert den Anmeldenamen des Admins (S3, Vorgabe des Haupt-Entwicklers vom 29.08.2026).
     *
     * <h2>Warum der Endpunkt hier steht und nicht bei der Spielerverwaltung</h2>
     * Technisch wird der Name des Adminprofils geschrieben - fachlich ist es der
     * <b>Anmeldename</b> fuer {@code /auth/admin/anmelden} und damit ein Anmeldemerkmal.
     * Er gehoert neben Passwort und zentrale PIN. {@code /admin/user/bearbeiten} lehnt das
     * Adminprofil deshalb vollstaendig ab.
     *
     * <h2>Die Sitzung bleibt bestehen - anders als bei den beiden Endpunkten darueber</h2>
     * Kein Sitzungswiderruf, <b>kein geloeschtes Cookie</b>. Der Name ist kein Geheimnis,
     * dessen Bekanntwerden allein Zugang verschafft; ein Widerruf wuerfe den Admin unmittelbar
     * nach seiner eigenen Umbenennung aus der Sitzung. Es genuegt, dass die Aenderung beim
     * naechsten Login greift.
     *
     * <p><b>Kein altes Passwort noetig</b> - dasselbe Muster wie bei
     * {@link #zentralePinAendern}. Es wird nichts geraten, sondern gesetzt; die Berechtigung
     * dazu hat die Filterchain bereits festgestellt.
     *
     * <p><b>Betriebshinweis:</b> Nach der Aenderung ist {@code ADMIN_NAME} in der {@code .env}
     * veraltet. Fuer den laufenden Betrieb folgenlos - der Start-Bootstrap liest den Wert nur,
     * solange kein Admin-Konto existiert -, aber nach einem Neuaufbau von
     * {@code profil.admin_konto} braecht der Start ab. Der Dienst schreibt deshalb alten und
     * neuen Namen ins Log und ins Protokoll.
     *
     * @param anfrage neuer Anmeldename
     * @param request fuer die Ermittlung der Client-IP
     * @return {@code 204} ohne Inhalt; <b>ohne</b> Cookie-Aenderung
     */
    @PostMapping(value = "/name/aendern", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> adminNameAendern(@Valid @RequestBody AdminNameAendernRequest anfrage,
                                                 HttpServletRequest request) {

        zugangsdatenService.adminNameAendern(
                anfrage.bereinigterName(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }
}
