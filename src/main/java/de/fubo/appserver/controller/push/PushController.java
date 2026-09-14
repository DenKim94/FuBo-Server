package de.fubo.appserver.controller.push;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.push.AboAnlegenRequest;
import de.fubo.appserver.dto.push.AboEntfernenRequest;
import de.fubo.appserver.dto.push.AboZustand;
import de.fubo.appserver.dto.push.EinstellungAendernRequest;
import de.fubo.appserver.dto.push.PushEinstellung;
import de.fubo.appserver.dto.push.PushStatus;
import de.fubo.appserver.dto.push.VapidSchluesselInfo;
import de.fubo.appserver.service.push.PushService;
import de.fubo.appserver.utils.GeraeteBezeichnung;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Push-Benachrichtigungen aus Sicht des Spielers (A25c bis A25f; S8 Abschnitt 7.2).
 *
 * <h2>Zum Pfad und zur Berechtigung</h2>
 * {@code /api/{version}/push/...} - <b>nicht</b> unterhalb von {@code /admin/}: Jeder
 * angemeldete Spieler verwaltet seine eigenen Abonnements. <b>Hier kehrt sich das uebliche
 * Fehlerbild um</b>, und das ist der eine Punkt, den man bei diesem Controller kennen muss:
 * Ohne den ausdruecklichen Eintrag in der Filterchain fielen diese Pfade unter
 * {@code anyRequest().hasAnyRole("USER", "ADMIN", "GAST")} und waeren fuer Gaeste
 * <b>offen</b> - nicht gesperrt. A25d verlangt {@code 403}. Der Eintrag steht in
 * {@code SecurityConfig} und namentlich in {@code SecurityConfigTests}; ein vergessener
 * faellt nicht als abgewiesener Nutzer auf, sondern als stiller Zugang.
 *
 * <h2>Fuenf Endpunkte, drei Ebenen</h2>
 * Der Versand haengt an drei Bedingungen mit drei verschiedenen Entscheidern. Zwei davon
 * bedient dieser Controller: die <b>Person</b> ({@code /einstellung/aendern}) und das
 * <b>Geraet</b> ({@code /abo/anlegen}, {@code /abo/entfernen}). Die dritte - die Anlage -
 * gehoert dem Admin und steht im Konfigurationsformular; {@code /status/lesen} zeigt die
 * beiden serverseitigen nebeneinander.
 *
 * <p><b>Abschalten und Widerrufen sind zwei Handlungen</b>, und die Oberflaeche muss den
 * Unterschied benennen: Der Personenschalter gilt fuer alle Geraete des Spielers, der
 * Widerruf nur fuer das aufrufende. Wer stattdessen die Berechtigung im Browser entzieht,
 * braucht zum Wiedereinschalten einen neuen Dialog.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; die Ableitung der
 * Geraetebezeichnung aus dem {@code User-Agent} ist die einzige Ausnahme und liegt als
 * zustandsloser Helfer in {@code utils}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/push")
public class PushController {

    private final PushService pushService;

    public PushController(PushService pushService) {
        this.pushService = pushService;
    }

    /**
     * Liefert den oeffentlichen VAPID-Schluessel zum Abonnieren im Browser.
     *
     * <p><b>{@code 503}, wenn Push auf diesem Server nicht eingerichtet ist</b> - das heisst
     * "gibt es hier nicht", nicht "Fehler". Die Oberflaeche blendet den Bereich aus;
     * Wiederholen hilft nie.
     *
     * @return der Schluessel als base64url
     */
    @GetMapping(value = "/schluessel/lesen", version = ApiVersionConfig.VERSION)
    public VapidSchluesselInfo schluesselLesen() {
        return pushService.schluessel();
    }

    /**
     * Legt das Abonnement dieses Geraets an oder frischt es auf.
     *
     * <p><b>Der Client ruft diesen Endpunkt bei jedem Anwendungsstart</b>, nicht nur beim
     * ersten Zustimmen: Der Aufruf ist ueber den Hash der Endpoint-Adresse idempotent und
     * heilt ein Abonnement, das der Server nach einem {@code 410} deaktiviert hat, der Browser
     * aber noch fuehrt.
     *
     * <p><b>Die Geraetebezeichnung kommt aus dem {@code User-Agent}</b> und nicht aus dem
     * Anfragekoerper: Ein frei waehlbarer Anzeigename waere eine vom Client bestimmte
     * Zeichenkette in einer Oberflaeche - ohne Gewinn, da der Zweck allein das Wiedererkennen
     * des eigenen Geraets ist.
     *
     * @param anfrage Endpoint und die beiden Schluessel aus der Push-API des Browsers
     * @param request fuer die Ableitung der Geraetebezeichnung
     * @param sitzung aufrufende Sitzung; ihre {@code spielerId} ist der Eigentuemer
     * @return {@code aboVorhanden: true}
     */
    @PostMapping(value = "/abo/anlegen", version = ApiVersionConfig.VERSION)
    public AboZustand aboAnlegen(@Valid @RequestBody AboAnlegenRequest anfrage,
                                 HttpServletRequest request,
                                 @AuthenticationPrincipal AktiveSitzung sitzung) {

        return pushService.aboAnlegen(sitzung.spielerId(), anfrage,
                GeraeteBezeichnung.ermitteln(request));
    }

    /**
     * Widerruft das Abonnement dieses Geraets.
     *
     * <p><b>Ein unbekanntes oder fremdes Abonnement ergibt ebenfalls {@code 200}</b> - Loeschen
     * ist idempotent, und eine Fallunterscheidung im Client haette keinen Nutzen. Die Zeile
     * eines anderen Spielers bleibt dabei stehen; der Dienst prueft die Spieler-Id mit.
     *
     * @param anfrage Endpoint des zu widerrufenden Abonnements
     * @param sitzung aufrufende Sitzung
     * @return {@code aboVorhanden: false}
     */
    @PostMapping(value = "/abo/entfernen", version = ApiVersionConfig.VERSION)
    public AboZustand aboEntfernen(@Valid @RequestBody AboEntfernenRequest anfrage,
                                   @AuthenticationPrincipal AktiveSitzung sitzung) {

        return pushService.aboEntfernen(sitzung.spielerId(), anfrage);
    }

    /**
     * Setzt den eigenen Personenschalter (A25f).
     *
     * <p><b>Er wirkt auf beide Versandanlaesse.</b> Wer abschaltet, erfaehrt auch eine
     * Terminabsage erst beim Oeffnen der Anwendung - darauf ist beim Abschalten <b>einmal</b>
     * hinzuweisen; einen Schalter je Anlass gibt es bewusst nicht.
     *
     * <p><b>Er loescht keine Abonnements</b>, und es gibt bewusst keinen Admin-Endpunkt dafuer:
     * Die Spieler-Id kommt aus der Sitzung, nie aus dem Rumpf.
     *
     * @param anfrage gewuenschter Stand; das Feld ist Pflicht und nicht stillschweigend
     *                {@code false}
     * @param sitzung aufrufende Sitzung
     * @return der gespeicherte Stand
     */
    @PostMapping(value = "/einstellung/aendern", version = ApiVersionConfig.VERSION)
    public PushEinstellung einstellungAendern(@Valid @RequestBody EinstellungAendernRequest anfrage,
                                              @AuthenticationPrincipal AktiveSitzung sitzung) {

        return pushService.einstellungAendern(sitzung.spielerId(), anfrage.pushErwuenscht());
    }

    /**
     * Liefert die beiden serverseitigen Ebenen der drei Versandbedingungen.
     *
     * <p><b>{@code GET} und kein {@code POST}:</b> Es wird nichts geaendert, und es gibt keinen
     * Anfragekoerper - dieselbe Form wie {@code /auth/session/lesen} und {@code /bilanz/lesen}.
     *
     * <p><b>Die Geraeteebene fehlt</b>, und sie fehlt mit Absicht: Ein {@code GET} koennte das
     * aufrufende Geraet nicht identifizieren, ohne die Endpoint-Adresse in die URL zu
     * schreiben. Der Client liest sie lokal ueber {@code pushManager.getSubscription()}.
     *
     * @param sitzung aufrufende Sitzung
     * @return Anlagen- und Personenebene, getrennt
     */
    @GetMapping(value = "/status/lesen", version = ApiVersionConfig.VERSION)
    public PushStatus statusLesen(@AuthenticationPrincipal AktiveSitzung sitzung) {
        return pushService.status(sitzung.spielerId());
    }
}
