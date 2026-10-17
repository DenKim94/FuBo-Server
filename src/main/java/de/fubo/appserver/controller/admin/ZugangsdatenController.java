package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.common.security.SessionCookieFactory;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.dto.admin.AdminNameAendernRequest;
import de.fubo.appserver.dto.admin.AdminNameBestaetigenRequest;
import de.fubo.appserver.dto.admin.PasswortAendernRequest;
import de.fubo.appserver.dto.admin.PinAendernRequest;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.auth.AdminService;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.service.auth.NamensaenderungService;
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
import java.util.Optional;

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
 * <p><b>Passwort- und PIN-Aenderung melden den Aufrufer ab</b> und loeschen deshalb das
 * Session-Cookie. Das ist kein Nebeneffekt, sondern der Zweck: Wer ein Geheimnis wechselt, soll
 * sich einmal damit anmelden. <b>Die Namensaenderung nicht</b> - der Name ist kein Geheimnis.
 *
 * <p><b>Die Namensaenderung ist seit dem 04.10.2026 zweistufig:</b> {@code /name/aendern}
 * fordert sie an und verschickt eine Bestaetigungs-PIN, {@code /name/bestaetigen} uebernimmt
 * den Namen. Herleitung in {@link NamensaenderungService}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class ZugangsdatenController {

    private final ZugangsdatenService zugangsdatenService;
    private final NamensaenderungService namensaenderungService;
    private final AdminService adminService;
    private final BruteForceService bruteForceService;
    private final SessionCookieFactory sessionCookieFactory;
    private final AuditService auditService;

    public ZugangsdatenController(ZugangsdatenService zugangsdatenService,
                                  NamensaenderungService namensaenderungService,
                                  AdminService adminService,
                                  BruteForceService bruteForceService,
                                  SessionCookieFactory sessionCookieFactory,
                                  AuditService auditService) {
        this.zugangsdatenService = zugangsdatenService;
        this.namensaenderungService = namensaenderungService;
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
     * Fordert die Aenderung des Anmeldenamens an (A22; seit dem 04.10.2026 mit Bestaetigung).
     *
     * <h2>Der Name wird hier noch nicht geaendert</h2>
     * Der Dienst prueft ihn, speichert ihn am Vorgang und schickt eine Bestaetigungs-PIN an die
     * hinterlegte Adresse; uebernommen wird er erst in {@link #adminNameBestaetigen}. Bis zum
     * 04.10.2026 genuegte die Sitzung allein - wer an einem offenen Rechner sass, konnte den
     * Admin mit einem Aufruf aussperren, denn der Passwort-Reset holt das Passwort zurueck, nie
     * den Namen.
     *
     * <h2>Warum der Endpunkt hier steht und nicht bei der Spielerverwaltung</h2>
     * Technisch wird der Name des Adminprofils geschrieben - fachlich ist es der
     * <b>Anmeldename</b> fuer {@code /auth/admin/anmelden} und damit ein Anmeldemerkmal.
     * {@code /admin/user/bearbeiten} lehnt das Adminprofil deshalb vollstaendig ab.
     *
     * <p><b>{@code 202} statt {@code 204}:</b> Die Anfrage ist angenommen, ihr Ziel aber noch
     * nicht erreicht. Ein {@code 204} sagte "erledigt" - genau das, was bis zum 04.10.2026 galt
     * und jetzt nicht mehr stimmt.
     *
     * <p><b>Kein Brute-Force-Zaehler</b> - es wird nichts geraten. Begrenzt wird der Versand:
     * drei Anforderungen je Stunde und Adresse ({@code 429 NAME_AENDERUNG_GEDROSSELT}).
     *
     * <p><b>Betriebshinweis:</b> Nach einer Umbenennung ist {@code ADMIN_NAME} in der
     * {@code .env} veraltet. Der Dienst schreibt beim Einloesen alten und neuen Namen ins Log
     * und ins Protokoll.
     *
     * @param anfrage gewuenschter Anmeldename
     * @param request fuer die Ermittlung der Client-IP
     * @return {@code 202} ohne Inhalt; <b>ohne</b> Cookie-Aenderung
     */
    @PostMapping(value = "/name/aendern", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> adminNameAendern(@Valid @RequestBody AdminNameAendernRequest anfrage,
                                                 HttpServletRequest request) {

        namensaenderungService.anfordern(
                anfrage.bereinigterName(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.accepted().build();
    }

    /**
     * Loest die Bestaetigungs-PIN ein und uebernimmt den angeforderten Anmeldenamen (A22,
     * Ergaenzung vom 04.10.2026).
     *
     * <p>Der Ablauf entspricht {@code /auth/passwort/bestaetigen} und hat dieselben Gruende:
     * <ol>
     *   <li><b>Bean Validation</b> zuerst (am DTO) - eine PIN im falschen Format kostet keinen
     *       Versuch.</li>
     *   <li><b>Sperre pruefen</b>, bevor gegen den BCrypt-Hash gerechnet wird.</li>
     *   <li><b>Versuch zaehlen und PIN pruefen</b>; bei Misserfolg zusaetzlich auf den
     *       Brute-Force-Zaehler zaehlen, protokollieren, ablehnen.</li>
     *   <li><b>Bei Erfolg</b> den Zaehler der Adresse leeren und einloesen.</li>
     * </ol>
     *
     * <p><b>Der Fehlversuch wird hier protokolliert, nicht im Dienst</b> - der Controller laeuft
     * ausserhalb jeder Transaktion, und der Beleg soll die Ablehnung ueberleben.
     *
     * <p><b>Kein Sitzungswiderruf, kein geloeschtes Cookie</b> - der Name verschafft allein
     * keinen Zugang; die Aenderung greift beim naechsten Login.
     *
     * @param anfrage Bestaetigungs-PIN; bewusst ohne Namen
     * @param request fuer die Ermittlung der Client-IP
     * @return {@code 204} ohne Inhalt; <b>ohne</b> Cookie-Aenderung
     */
    @PostMapping(value = "/name/bestaetigen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> adminNameBestaetigen(@Valid @RequestBody AdminNameBestaetigenRequest anfrage,
                                                     HttpServletRequest request) {

        String clientIp = ClientIpErmittler.ermitteln(request);
        bruteForceService.pruefeGesperrt(clientIp);

        Optional<Long> vorgang = namensaenderungService.versuchPruefen(anfrage.bestaetigungsPin());

        if (vorgang.isEmpty()) {
            boolean sperreAusgeloest = bruteForceService.fehlversuchZaehlen(clientIp);
            auditService.protokolliere(
                    clientIp,
                    sperreAusgeloest ? AuditAktion.PIN_GESPERRT : AuditAktion.ADMIN_NAME_PIN_FEHLVERSUCH,
                    Map.of("endpunkt", "/admin/name/bestaetigen"));

            throw new FachlicherFehler(Fehlercode.NAME_PIN_FALSCH);
        }

        bruteForceService.zuruecksetzen(clientIp);
        namensaenderungService.einloesen(vorgang.get(), clientIp);

        return ResponseEntity.noContent().build();
    }
}
