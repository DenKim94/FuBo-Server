package de.fubo.appserver.controller.auth;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.security.SessionCookieFactory;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.auth.GastAnmeldungRequest;
import de.fubo.appserver.service.auth.GastService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gast-Login als Alternative zur Namensauswahl (A8, Abschnitt 8 der Umsetzungsanleitung).
 *
 * <p><b>Keine Tokenpruefung in dieser Methode.</b> Wer hier ankommt, hat die Filterchain
 * passiert; {@code POST /gast/anmelden} ist ausschliesslich in der Stufe
 * {@code PIN_VERIFIED} erlaubt. Eine bereits angemeldete Sitzung erhaelt {@code 403}, ohne
 * dass der Controller etwas dafuer tun muss.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/auth")
public class GastController {

    private final GastService gastService;
    private final SessionCookieFactory sessionCookieFactory;

    public GastController(GastService gastService, SessionCookieFactory sessionCookieFactory) {
        this.gastService = gastService;
        this.sessionCookieFactory = sessionCookieFactory;
    }

    /**
     * Meldet die Sitzung als Gast an und belegt einen der festen Gastplaetze.
     *
     * <p>Die Antwort traegt wie bei der Namensauswahl ein <b>neues</b> Cookie: Beim
     * Stufenwechsel wird der Token rotiert (Schutz vor Session Fixation).
     *
     * @param anfrage Anzeigename und optionale Selbsteinschaetzung
     * @param sitzung aufrufende Sitzung aus dem Sicherheitskontext; nie {@code null}, weil
     *                die Filterchain den Zugriff sonst gar nicht durchgelassen haette
     * @return {@code 204} ohne Inhalt, dazu das rotierte Session-Cookie
     */
    @PostMapping(value = "/gast/anmelden", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> alsGastAnmelden(@Valid @RequestBody GastAnmeldungRequest anfrage,
                                                @AuthenticationPrincipal AktiveSitzung sitzung) {

        String neuerToken = gastService.alsGastAnmelden(
                sitzung.id(), anfrage.bereinigterName(), anfrage.stufeOderVorgabe());

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.setzen(neuerToken).toString())
                .build();
    }
}
