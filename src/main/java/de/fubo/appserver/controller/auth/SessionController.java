package de.fubo.appserver.controller.auth;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.common.security.SessionCookieFactory;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.auth.SitzungInfo;
import de.fubo.appserver.service.auth.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verwaltung der laufenden Sitzung: Auskunft, Erneuerung und Abmeldung
 * (Abschnitt 10.4 der Umsetzungsanleitung, A14).
 *
 * <p><b>Alle drei Endpunkte sind bereits in der Stufe {@code PIN_VERIFIED} erlaubt.</b>
 * Der Entwurf in Abschnitt 10.4 nannte pauschal "angemeldet"; das waere zu eng. Laedt
 * jemand die Seite neu, nachdem er die PIN eingegeben, aber noch keinen Namen gewaehlt hat,
 * muss das Frontend erfahren, dass es die Namensauswahl anzeigen soll und nicht die
 * PIN-Eingabe. Mit einer Beschraenkung auf {@code PROFILE_AUTHENTICATED} bekaeme es an
 * dieser Stelle {@code 403} und muesste den Nutzer die PIN erneut eingeben lassen -
 * obwohl seine Sitzung gueltig ist. Dasselbe gilt fuer das Abmelden: Einen angefangenen
 * Login abzubrechen muss moeglich sein.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/auth")
public class SessionController {

    private final SessionService sessionService;
    private final SessionCookieFactory sessionCookieFactory;

    public SessionController(SessionService sessionService,
                             SessionCookieFactory sessionCookieFactory) {
        this.sessionService = sessionService;
        this.sessionCookieFactory = sessionCookieFactory;
    }

    /**
     * Liefert Stufe, Rolle, Anzeigename und Ablaufzeitpunkte der aufrufenden Sitzung.
     *
     * <p>Ohne gueltige Sitzung antwortet bereits die Filterchain mit {@code 401} - der
     * Aufruf erreicht diese Methode dann gar nicht. Das Frontend wertet genau das aus:
     * {@code 200} bedeutet angemeldet, {@code 401} bedeutet Login anzeigen.
     *
     * <p><b>Hinweis zum Pollen:</b> Wer diesen Endpunkt zyklisch abruft, sollte den Header
     * {@code X-FuBo-Kein-Refresh: true} setzen - sonst haelt allein die Abfrage der
     * Restlaufzeit die Sitzung am Leben, und der angezeigte Countdown liefe nie ab.
     *
     * @param sitzung aufrufende Sitzung aus dem Sicherheitskontext
     * @return {@code 200} mit der Sitzungsauskunft
     */
    @GetMapping(value = "/session/lesen", version = ApiVersionConfig.VERSION)
    public SitzungInfo sitzungLesen(@AuthenticationPrincipal AktiveSitzung sitzung) {
        return sessionService.auskunft(sitzung);
    }

    /**
     * Verlaengert das Leerlauf-Fenster ausdruecklich und tauscht dabei den Token aus (A14).
     *
     * <p>Die eigentliche Verlaengerung hat bereits die Filterchain vorgenommen - dieser
     * Aufruf ist ein Request wie jeder andere und verschiebt {@code gueltig_bis} damit
     * ohnehin nach hinten. Der eigene Endpunkt fuegt zwei Dinge hinzu: einen frischen Token
     * und eine Stelle, an der das Frontend die Bestaetigung des Nutzers gezielt abbilden
     * kann ("Sitzung verlaengern"), ohne einen fachlichen Aufruf abzusetzen.
     *
     * <p>Der Aufruf darf den Header {@code X-FuBo-Kein-Refresh} nicht tragen - er wuerde
     * genau das verhindern, worum es hier geht.
     *
     * @param sitzung aufrufende Sitzung aus dem Sicherheitskontext
     * @return {@code 204} ohne Inhalt, dazu das rotierte Session-Cookie
     */
    @PostMapping(value = "/session/erneuern", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> sitzungErneuern(@AuthenticationPrincipal AktiveSitzung sitzung) {
        String neuerToken = sessionService.erneuern(sitzung.id());

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.setzen(neuerToken).toString())
                .build();
    }

    /**
     * Meldet die Sitzung ab: Sie wird widerrufen, ein belegter Gastplatz freigegeben und
     * das Cookie geloescht.
     *
     * <p>Freigabe und Widerruf laufen in <b>einer</b> Transaktion im
     * {@code SessionService} - fielen sie auseinander, waere der Platz frei, die Sitzung
     * aber weiter gueltig. Der Aufruf ist fuer Nicht-Gaeste folgenlos, der Endpunkt muss
     * die Rolle also nicht unterscheiden.
     *
     * <p><b>Das Loeschen des Cookies ist Bequemlichkeit, nicht die Abmeldung.</b> Der
     * Widerruf in der Datenbank ist der wirksame Teil: Selbst wenn der Browser das Cookie
     * behielte, waere der Token sofort wertlos. Genau diese sofortige Widerrufbarkeit ist
     * der Grund fuer den serverseitigen Token statt eines JWT.
     *
     * @param sitzung aufrufende Sitzung aus dem Sicherheitskontext
     * @return {@code 204} ohne Inhalt, dazu das geloeschte Session-Cookie
     */
    @PostMapping(value = "/session/beenden", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> sitzungBeenden(@AuthenticationPrincipal AktiveSitzung sitzung) {
        sessionService.abmelden(sitzung.id());

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieFactory.loeschen().toString())
                .build();
    }
}
