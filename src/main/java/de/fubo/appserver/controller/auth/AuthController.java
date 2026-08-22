package de.fubo.appserver.controller.auth;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.common.security.SessionCookieFactory;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.domain.auth.Stage;
import de.fubo.appserver.dto.auth.PinLoginRequest;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.service.auth.PinService;
import de.fubo.appserver.service.auth.SessionService;
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
 * Erste Stufe des zweistufigen Logins: Pruefung der zentralen PIN (A3, A14).
 *
 * <p>Der Controller enthaelt bewusst keine Fachlogik ausser der Reihenfolge der Schritte.
 * Die Sperrpruefung liegt im {@link BruteForceService}, der Hash-Vergleich im
 * {@link PinService}, die Sitzung im {@link SessionService} und die Cookie-Attribute in
 * der {@link SessionCookieFactory}.
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/auth/...}. Das Segment {@code {version}} wird
 * von der Filterchain und von {@link ApiVersionConfig} ausgewertet; im Controller ist es
 * nur ein Platzhalter und wird nirgends eingelesen. Jede Methode traegt zusaetzlich ein
 * {@code version}-Attribut - erst dadurch entscheidet die Zuordnung anhand der Version.
 * Ohne das Attribut wuerde die Methode jede Version bedienen.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/auth")
public class AuthController {

    private final PinService pinService;
    private final BruteForceService bruteForceService;
    private final SessionService sessionService;
    private final SessionCookieFactory sessionCookieFactory;
    private final AuditService auditService;

    public AuthController(PinService pinService,
                          BruteForceService bruteForceService,
                          SessionService sessionService,
                          SessionCookieFactory sessionCookieFactory,
                          AuditService auditService) {
        this.pinService = pinService;
        this.bruteForceService = bruteForceService;
        this.sessionService = sessionService;
        this.sessionCookieFactory = sessionCookieFactory;
        this.auditService = auditService;
    }

    /**
     * Prueft die zentrale PIN und legt bei Erfolg eine Sitzung in der Stufe
     * {@link Stage#PIN_VERIFIED} an.
     *
     * <p>Die Reihenfolge ist nicht beliebig:
     * <ol>
     *   <li>Sperre pruefen - eine gesperrte Anfrage soll gar nicht erst gegen den
     *       BCrypt-Hash rechnen, sonst waere die Drosselung selbst der Angriffsvektor.</li>
     *   <li>PIN pruefen; bei Misserfolg zaehlen, protokollieren, ablehnen.</li>
     *   <li>Bei Erfolg den Zaehler der Adresse leeren, damit ein Vertipper keine
     *       Nachwirkung hat.</li>
     * </ol>
     *
     * <p><b>Zum Statuscode beim Fehlversuch, der die Sperre ausloest:</b> Die Antwort ist
     * weiterhin {@code 401 PIN_FALSCH}, nicht {@code 429}. Die Sperre wirkt ab dem
     * <i>naechsten</i> Aufruf. Andernfalls verriete der Statuscode, an welcher Stelle die
     * Zaehlung genau steht.
     *
     * <p><b>Zur bestehenden Sitzung:</b> Meldet sich jemand erneut an, obwohl noch ein
     * gueltiges Cookie vorliegt, wird die alte Sitzung abgemeldet. Ohne das bliebe sie bis
     * zum Ablauf gueltig - und bei einem Profil, das bereits einen Namen belegt, waere der
     * Name unnoetig lange blockiert (A6).
     *
     * <p>Abgemeldet, nicht nur widerrufen (Korrektur 22.08.2026): War die alte Sitzung eine
     * Gastsitzung, haelt sie einen der festen Gastplaetze. Ein blosser Widerruf liesse den
     * Platz bis zum naechtlichen Aufraeumlauf besetzt - bei vier Plaetzen faellt das sofort
     * auf. {@code SessionService#abmelden} gibt ihn in derselben Transaktion frei.
     *
     * @param anfrage           Klartext-PIN aus dem Anfragekoerper
     * @param request           fuer die Ermittlung der Client-IP
     * @param bestehendeSitzung Sitzung aus einem mitgesendeten, noch gueltigen Cookie;
     *                          {@code null}, wenn keine vorliegt
     * @return {@code 204} ohne Inhalt, dazu das Session-Cookie im {@code Set-Cookie}-Header
     */
    @PostMapping(value = "/pin/pruefen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> pinPruefen(@Valid @RequestBody PinLoginRequest anfrage,
                                           HttpServletRequest request,
                                           @AuthenticationPrincipal AktiveSitzung bestehendeSitzung) {

        String clientIp = ClientIpErmittler.ermitteln(request);
        bruteForceService.pruefeGesperrt(clientIp);

        if (!pinService.stimmt(anfrage.pin())) {
            boolean sperreAusgeloest = bruteForceService.fehlversuchZaehlen(clientIp);
            auditService.protokolliere(
                    clientIp,
                    sperreAusgeloest ? AuditAktion.PIN_GESPERRT : AuditAktion.PIN_FEHLVERSUCH,
                    Map.of("endpunkt", "/auth/pin/pruefen"));

            throw new FachlicherFehler(Fehlercode.PIN_FALSCH);
        }

        bruteForceService.zuruecksetzen(clientIp);

        if (bestehendeSitzung != null) {
            sessionService.abmelden(bestehendeSitzung.id());
        }

        String token = sessionService.anlegen(Stage.PIN_VERIFIED, null, null);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.setzen(token).toString())
                .build();
    }
}
