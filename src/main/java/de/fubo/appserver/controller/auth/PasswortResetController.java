package de.fubo.appserver.controller.auth;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.error.FachlicherFehler;
import de.fubo.appserver.common.error.Fehlercode;
import de.fubo.appserver.domain.audit.AuditAktion;
import de.fubo.appserver.dto.auth.PasswortResetBestaetigenRequest;
import de.fubo.appserver.service.audit.AuditService;
import de.fubo.appserver.service.auth.BruteForceService;
import de.fubo.appserver.service.auth.PasswortResetService;
import de.fubo.appserver.utils.ClientIpErmittler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Zuruecksetzen des vergessenen Admin-Passworts (A22).
 *
 * <h2>Warum die Endpunkte unter {@code /auth/} liegen und nicht unter {@code /admin/}</h2>
 * Abschnitt 10 der S2b-Anleitung nannte {@code /admin/passwort/zuruecksetzen}. Das geht
 * nicht: {@code /api/*&#47;admin/**} verlangt in {@code SecurityConfig} die Rolle
 * {@code ADMIN} - und wer sein Passwort vergessen hat, hat sie gerade nicht. Die beiden
 * Endpunkte gehoeren zur <b>Anmeldung</b> und stehen damit neben Namensauswahl, Gast-Login
 * und Admin-Login: erreichbar ausschliesslich in der Stufe {@code PIN_VERIFIED}.
 * (Festgelegt am 23.08.2026; Abschnitt 10 der Anleitung ist nachgezogen.)
 *
 * <h2>Warum hinter der zentralen PIN</h2>
 * <ul>
 *   <li><b>A1 verlangt, Fremde vor dem Zugang auszuschliessen.</b> Die zentrale PIN ist der
 *       aeussere Zaun; ein Endpunkt davor waere die einzige Stelle der Anwendung, die ihn
 *       umgeht.</li>
 *   <li><b>Er verschickt E-Mails.</b> Ohne den Zaun koennte jeder Unbekannte beliebig oft
 *       Nachrichten an die Adresse des Admins ausloesen - die Drosselung begrenzte den
 *       Schaden, verhinderte ihn aber nicht.</li>
 *   <li><b>Der realistische Fall ist "Passwort vergessen, PIN bekannt".</b> Die zentrale
 *       PIN wird regelmaessig benutzt und extern verteilt; das Adminpasswort liegt selten
 *       gebraucht im Passwortsafe.</li>
 * </ul>
 * Der Preis ist bewusst in Kauf genommen: Wer <i>beides</i> vergisst, kommt ueber die
 * Anwendung nicht mehr hinein und braucht die Datenbank. Das gehoert in die
 * Betriebsdokumentation, nicht in die Anwendung.
 *
 * <p><b>Keine Tokenpruefung in diesen Methoden.</b> Wer hier ankommt, hat die Filterchain
 * bereits passiert.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/auth")
public class PasswortResetController {

    private final PasswortResetService passwortResetService;
    private final BruteForceService bruteForceService;
    private final AuditService auditService;

    public PasswortResetController(PasswortResetService passwortResetService,
                                   BruteForceService bruteForceService,
                                   AuditService auditService) {
        this.passwortResetService = passwortResetService;
        this.bruteForceService = bruteForceService;
        this.auditService = auditService;
    }

    /**
     * Fordert eine Bestaetigungs-PIN an und schickt sie an die hinterlegte Adresse.
     *
     * <p><b>Kein Anfragekoerper.</b> Es gibt genau einen Admin und genau eine hinterlegte
     * Adresse - ein Feld dafuer waere eine Auswahl ohne Alternative und zugleich eine
     * Einladung, fremde Adressen zu erproben.
     *
     * <p>Die Antwort verraet weder die Adresse noch die PIN. Auch der Fall "es gibt gar
     * keine Adresse" kann hier nicht auftreten: {@code admin_konto.email} ist
     * {@code NOT NULL} und entsteht im Start-Bootstrap.
     *
     * @param request fuer die Ermittlung der Client-IP
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/passwort/zuruecksetzen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> resetAnfordern(HttpServletRequest request) {
        passwortResetService.anfordern(ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }

    /**
     * Loest die Bestaetigungs-PIN ein und setzt das neue Passwort.
     *
     * <p>Die Reihenfolge entspricht dem PIN- und dem Admin-Login und ist aus denselben
     * Gruenden nicht beliebig:
     * <ol>
     *   <li><b>Bean Validation</b> laeuft vor allem anderen (am DTO). Ein zu kurzes
     *       Passwort kostet damit keinen der fuenf Versuche.</li>
     *   <li><b>Sperre pruefen</b> - eine gesperrte Anfrage soll gar nicht erst gegen den
     *       BCrypt-Hash rechnen, sonst waere die Drosselung selbst der Angriffsvektor.</li>
     *   <li><b>Versuch zaehlen und PIN pruefen</b>; bei Misserfolg zusaetzlich auf den
     *       Brute-Force-Zaehler zaehlen, protokollieren, ablehnen.</li>
     *   <li><b>Bei Erfolg</b> den Zaehler der Adresse leeren und den Vorgang einloesen.</li>
     * </ol>
     *
     * <p><b>Warum der Fehlversuch hier protokolliert wird und nicht im Service:</b> Der
     * Controller laeuft ausserhalb jeder Transaktion. Ein Audit-Eintrag im Service wuerde
     * mit der abgelehnten Anfrage zurueckgerollt - der Beleg dafuer, dass jemand PINs raet,
     * ginge genau dann verloren, wenn er gebraucht wird. Dieselbe Ueberlegung wie beim
     * PIN-Endpunkt in S2.
     *
     * <p><b>Der Fehlversuch zaehlt zusaetzlich auf den {@code BruteForceService}.</b> Wer
     * Bestaetigungs-PINs raet, soll anschliessend auch keine zentralen PINs mehr
     * durchprobieren koennen - dieselbe Ueberlegung wie beim Admin-Login.
     *
     * @param anfrage Bestaetigungs-PIN und neues Passwort
     * @param request fuer die Ermittlung der Client-IP
     * @return {@code 204} ohne Inhalt; das Cookie der aufrufenden Sitzung bleibt gueltig
     */
    @PostMapping(value = "/passwort/bestaetigen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> resetBestaetigen(@Valid @RequestBody PasswortResetBestaetigenRequest anfrage,
                                                 HttpServletRequest request) {

        String clientIp = ClientIpErmittler.ermitteln(request);
        bruteForceService.pruefeGesperrt(clientIp);

        Optional<Long> vorgang = passwortResetService.versuchPruefen(anfrage.bestaetigungsPin());

        if (vorgang.isEmpty()) {
            boolean sperreAusgeloest = bruteForceService.fehlversuchZaehlen(clientIp);
            auditService.protokolliere(
                    clientIp,
                    sperreAusgeloest ? AuditAktion.PIN_GESPERRT : AuditAktion.PASSWORT_RESET_FEHLVERSUCH,
                    Map.of("endpunkt", "/auth/passwort/bestaetigen"));

            throw new FachlicherFehler(Fehlercode.RESET_PIN_FALSCH);
        }

        bruteForceService.zuruecksetzen(clientIp);
        passwortResetService.passwortSetzen(vorgang.get(), anfrage.neuesPasswort(), clientIp);

        return ResponseEntity.noContent().build();
    }
}
