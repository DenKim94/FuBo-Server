package de.fubo.appserver.controller.auth;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.security.SessionCookieFactory;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.auth.NameAuswahlRequest;
import de.fubo.appserver.dto.profil.NameOption;
import de.fubo.appserver.service.auth.NamenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Namensliste und Namensauswahl - die zweite Stufe des Logins (A4, A6, A14).
 *
 * <p><b>Zu den Pfaden:</b> {@code GET /api/{version}/auth/users/lesen} und
 * {@code POST /api/{version}/auth/user/waehlen}. Die Aktion steht als eigenes Segment im
 * Pfad, obwohl die HTTP-Methode sie bereits ausdrueckt - so bekommt jede Operation einen
 * eigenen, unabhaengig versionierbaren Pfad. Die frueheren Entwuerfe im Abschnitt 10.4 der
 * Umsetzungsanleitung nannten {@code /namen} und {@code /name}; massgeblich ist die
 * Filterchain, weil dort die Autorisierung haengt.
 *
 * <p><b>Keine Tokenpruefung in diesen Methoden.</b> Wer hier ankommt, hat die Filterchain
 * bereits passiert: {@code GET /users/lesen} ist ab {@code PIN_VERIFIED} erlaubt,
 * {@code POST /user/waehlen} ausschliesslich in {@code PIN_VERIFIED}. Eine bereits
 * angemeldete Sitzung erhaelt dort {@code 403}, ohne dass der Controller etwas dafuer tun
 * muss.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/auth")
public class NamenController {

    private final NamenService namenService;
    private final SessionCookieFactory sessionCookieFactory;

    public NamenController(NamenService namenService, SessionCookieFactory sessionCookieFactory) {
        this.namenService = namenService;
        this.sessionCookieFactory = sessionCookieFactory;
    }

    /**
     * Liefert die Namensliste mit Belegtstatus. Wird vom Frontend zyklisch abgerufen (A6).
     *
     * @return Liste aktiver Profile ohne Skillwerte
     */
    @GetMapping(value = "/users/lesen", version = ApiVersionConfig.VERSION)
    public List<NameOption> namensliste() {
        return namenService.namensliste();
    }

    /**
     * Waehlt ein Profil und hebt die Sitzung auf {@code PROFILE_AUTHENTICATED}.
     *
     * <p>Die Antwort traegt ein <b>neues</b> Cookie: Beim Stufenwechsel wird der Token
     * rotiert. Das Frontend muss dafuer nichts tun, solange es mit
     * {@code credentials: 'include'} aufruft - der Browser ersetzt das Cookie selbst.
     *
     * @param anfrage Id des gewaehlten Profils
     * @param sitzung aufrufende Sitzung aus dem Sicherheitskontext; nie {@code null}, weil
     *                die Filterchain den Zugriff sonst gar nicht durchgelassen haette
     * @return {@code 204} ohne Inhalt, dazu das rotierte Session-Cookie
     */
    @PostMapping(value = "/user/waehlen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> nameWaehlen(@Valid @RequestBody NameAuswahlRequest anfrage,
                                            @AuthenticationPrincipal AktiveSitzung sitzung) {

        String neuerToken = namenService.nameWaehlen(sitzung.id(), anfrage.spielerId());

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.setzen(neuerToken).toString())
                .build();
    }
}
