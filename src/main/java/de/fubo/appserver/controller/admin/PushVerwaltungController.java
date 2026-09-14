package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.push.Probeversand;
import de.fubo.appserver.service.push.PushService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Der Probeversand des Admins (S8 Abschnitt 10.2).
 *
 * <h2>Zum Pfad</h2>
 * {@code /api/{version}/admin/push/test}. Der Ort eines Endpunkts ist die
 * Autorisierungsentscheidung - alles unterhalb von {@code /api/*&#47;admin/**} verlangt die
 * Rolle {@code ADMIN}, eine eigene Regel je Endpunkt braucht es nicht. <b>Die bestehende
 * Adminregel deckt ihn ab</b>; der Eintrag fuer {@code /api/*&#47;push/**} gilt ihm nicht.
 *
 * <h2>Warum ein eigener Controller in {@code controller/admin}</h2>
 * Wie beim {@code HallenController}: Der Zugriffsweg entscheidet die Ablage, die Fachlogik
 * bleibt in {@code service/push}. Ihn in den {@code PushController} des
 * Fachbereichs {@code push} zu legen ginge nicht - dessen Pfad traegt kein
 * {@code /admin/}-Praefix, und damit waere er fuer jeden Angemeldeten offen.
 *
 * <p><b>Er bleibt vorerst allein.</b> Einen Admin-Endpunkt zum Setzen fremder
 * Personenschalter gibt es nicht und wird es nicht geben - A25f verbietet ihn ausdruecklich.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin/push")
public class PushVerwaltungController {

    private final PushService pushService;

    public PushVerwaltungController(PushService pushService) {
        this.pushService = pushService;
    }

    /**
     * Versendet eine Testbenachrichtigung an die eigenen Geraete des Admins.
     *
     * <h2>Was er beweist - und was nicht</h2>
     * Er prueft die drei Versandbedingungen <b>nicht</b> (Entscheidung vom 14.09.2026): Der
     * Admin ist Absender und Empfaenger in einer Person und hat den Versand ausdruecklich
     * angefordert. Ihn zu zwingen, erst den Anlagenschalter einzuschalten, um zu pruefen, ob
     * das Einschalten etwas bringt, drehte die Reihenfolge um.
     *
     * <p><b>Ein erfolgreicher Probeversand beweist deshalb nicht, dass Spieler etwas
     * bekommen.</b> Er beweist, dass VAPID-Schluessel, Verschluesselung und der Weg zum
     * Push-Dienst tragen - und das ist genau der Teil, den sonst nichts pruefen kann: Ein
     * Fehler in der Verschluesselung faellt nirgends auf, der Push-Dienst antwortet auch darauf
     * mit {@code 201}. <b>Das gehoert sichtbar auf den Adminbildschirm</b>, zusammen mit
     * {@code anlageAktiv} aus {@code /push/status/lesen}.
     *
     * <p><b>{@code empfaenger: 0} ist kein Fehler</b>: Der Admin hat auf diesem Konto kein
     * Geraet angemeldet. Das Adminprofil darf abonnieren - es ist zwar von der
     * Empfaengerabfrage der Erinnerung ausgenommen, aber nicht vom Abonnieren.
     *
     * <p><b>{@code 503}, wenn Push nicht eingerichtet ist</b> - dann gibt es nichts zu pruefen.
     *
     * @param sitzung aufrufende Adminsitzung
     * @return angesprochene und angenommene Geraete
     */
    @PostMapping(value = "/test", version = ApiVersionConfig.VERSION)
    public Probeversand probeversand(@AuthenticationPrincipal AktiveSitzung sitzung) {
        return pushService.probeversand(sitzung.spielerId());
    }
}
