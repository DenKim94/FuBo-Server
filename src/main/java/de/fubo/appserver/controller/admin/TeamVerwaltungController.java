package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.admin.ManuelleEinteilung;
import de.fubo.appserver.dto.admin.ManuelleGenerierungRequest;
import de.fubo.appserver.service.spieltag.TeamGenerierungService;
import de.fubo.appserver.utils.ClientIpErmittler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Der manuelle Generierungslauf des Admins (A24; S5 Abschnitt 9.4).
 *
 * <h2>Ein eigener Endpunkt und nicht der bestehende mit optionalem {@code terminId}</h2>
 * <b>Der Ort ist die Autorisierungsentscheidung.</b> {@code POST /api/v1/teams/generieren}
 * steht bewusst allen Rollen offen (A15), A24 ist ausdruecklich eine Adminbefugnis. Ein
 * Endpunkt mit zwei Zugangsregeln je nach Koerperinhalt waere genau die Pruefung im
 * Controller, die {@code AGENT_SERVER.md} verbietet.
 *
 * <p>An der Filterchain aendert sich nichts - {@code /api/*&#47;admin/**} verlangt bereits
 * {@code ROLE_ADMIN}. <b>Der Pfad steht trotzdem namentlich in {@code SecurityConfigTests}:</b>
 * Die Platzhalterpruefung bliebe gruen, wenn jemand fuer einen echten Endpunkt eine offenere
 * Regel <i>davor</i> setzte - die erste passende Matcher-Regel gewinnt.
 *
 * <h2>Zweite Eingangstuer, kein zweiter Generator</h2>
 * Verzweigt wird allein die Herkunft der Aufstellung. Zielfunktion, beide Verfahren und der
 * Auswechselspieler bleiben unberuehrt; gerechnet wird dasselbe wie am Termin.
 *
 * <h2>Zum Paketschnitt</h2>
 * Der Controller liegt in {@code controller/admin}, seine DTOs ausnahmsweise ebenfalls in
 * {@code dto/admin} - anders als beim {@link TerminVerwaltungController}, dessen DTOs in
 * {@code dto/spieltag} stehen. Der Grund ist kein anderer Zugriff, sondern anderer Inhalt:
 * {@code ManuelleEinteilung} traegt {@code differenzTeamstaerke}, eine abgeleitete Kennzahl
 * der Teamstaerke, und A12 laesst solche Werte ausschliesslich unterhalb von
 * {@code /api/*&#47;admin/**} nach aussen.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin/teams")
public class TeamVerwaltungController {

    private final TeamGenerierungService teamGenerierungService;

    public TeamVerwaltungController(TeamGenerierungService teamGenerierungService) {
        this.teamGenerierungService = teamGenerierungService;
    }

    /**
     * Rechnet eine Teameinteilung auf einer frei gewaehlten Teilnehmermenge.
     *
     * <p><b>{@code 200} und kein {@code 201}:</b> Es entsteht keine Ressource, auf die ein
     * {@code Location}-Header zeigen koennte - {@code 201} behauptete etwas, das die Datenbank
     * nicht hergibt. Das Ergebnis wird nirgends gespeichert, erscheint bei keinem anderen
     * Nutzer und ist nach dem Verlassen der Seite weg.
     *
     * <p><b>Die Auswahlliste ist {@code GET /admin/user/lesen}</b> - sie liefert Id, Name und
     * Rolle. Ein neuer Listenendpunkt ist nicht noetig.
     *
     * <p><b>Genannte, aber ungueltige Auswahl wird abgelehnt, nicht still gefiltert</b> (2.5):
     * unbekannte oder gesperrte Id {@code 400}, Adminprofil {@code 409 PROFIL_GESCHUETZT}. Am
     * Termin ergibt sich die Menge, hier hat der Admin jeden Einzelnen benannt.
     *
     * @param anfrage benannte Profile und frei angelegte Gaeste
     * @param request fuer die Client-IP im Protokoll
     * @param sitzung aufrufende Adminsitzung
     * @return {@code 200} mit der Einteilung, dem verwendeten Verfahren und den Kosten
     */
    @PostMapping(value = "/generieren", version = ApiVersionConfig.VERSION)
    public ResponseEntity<ManuelleEinteilung> generieren(
            @Valid @RequestBody ManuelleGenerierungRequest anfrage,
            HttpServletRequest request,
            @AuthenticationPrincipal AktiveSitzung sitzung) {

        return ResponseEntity.ok(ManuelleEinteilung.von(teamGenerierungService.manuell(
                anfrage.nachDomaene(), sitzung, ClientIpErmittler.ermitteln(request))));
    }
}
