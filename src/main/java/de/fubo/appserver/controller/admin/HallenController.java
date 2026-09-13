package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.spieltag.TerminIdRequest;
import de.fubo.appserver.service.spieltag.HallenService;
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
 * Der Hallenmodus aus Sicht des Admins (A23; S7 Abschnitt 3).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/admin/halle/absagen}. Der Ort eines Endpunkts ist
 * die Autorisierungsentscheidung - alles unterhalb von {@code /api/*&#47;admin/**} verlangt die
 * Rolle {@code ADMIN}, eine eigene Regel je Endpunkt braucht es nicht. <b>Hier gab es dazu keine
 * Weggabelung:</b> A23 gibt die Handlung ausdruecklich dem Admin, und sie verlaesst das System.
 *
 * <p><b>Der Anfragekoerper ist {@link TerminIdRequest}</b> - derselbe Record wie beim Absagen
 * und Entfernen eines Termins. Ein dritter Record mit demselben einen Feld waere Ballast; die
 * Endpunktbeschreibungen im Vertrag halten die drei auseinander.
 *
 * <p><b>Ein eigener Controller und keine Methode im {@link TerminVerwaltungController}:</b> Das
 * ist der einzige Endpunkt des Servers, der eine Nachricht an einen Aussenstehenden ausloest,
 * und er ist nicht zurueckrollbar. Das soll beim Lesen der Paketstruktur auffallen. Er bleibt
 * vorerst allein - A23 kennt nur die Absage, nicht das Anlegen oder Verwalten einer Buchung.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; Statuspruefung,
 * Fristberechnung, Doppelversandschutz, Versand und Protokoll liegen im {@link HallenService}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class HallenController {

    private final HallenService hallenService;

    public HallenController(HallenService hallenService) {
        this.hallenService = hallenService;
    }

    /**
     * Sagt den gebuchten Hallentermin beim Betreiber ab (A23).
     *
     * <p><b>Antwortet mit {@code 204}.</b> Es entsteht keine Ressource, und der einzige Wert,
     * den der Aufrufer nicht schon kennt - der Zeitpunkt des Versands -, steht unmittelbar
     * danach als {@code halleAbgesagtAm} in {@code GET /termine/{terminId}/lesen}, das der
     * Adminbildschirm ohnehin neu liest.
     *
     * <p><b>Ein geplanter Termin wird mit abgesagt</b> (Entscheidung des Haupt-Entwicklers vom
     * 13.09.2026), ein bereits abgesagter nur noch gemeldet, ein abgeschlossener abgelehnt.
     * Begruendung am {@link HallenService}.
     *
     * <p><b>Der Versand laesst sich nicht zuruecknehmen.</b> Das gehoert in die
     * Bestaetigungsabfrage der Oberflaeche - zusammen mit dem Hinweis, dass die Nachricht sofort
     * hinausgeht.
     *
     * @param anfrage Id des Termins, dessen Halle abgesagt wird
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/halle/absagen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> hallenterminAbsagen(@Valid @RequestBody TerminIdRequest anfrage,
                                                    HttpServletRequest request,
                                                    @AuthenticationPrincipal AktiveSitzung sitzung) {

        hallenService.absagen(anfrage.terminId(), sitzung.spielerId(),
                ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }
}
