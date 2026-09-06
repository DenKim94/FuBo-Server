package de.fubo.appserver.controller.spieltag;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.spieltag.Teameinteilung;
import de.fubo.appserver.dto.spieltag.TerminIdRequest;
import de.fubo.appserver.service.spieltag.TeamGenerierungService;
import de.fubo.appserver.utils.ClientIpErmittler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Teamgenerierung am Termin (A15, A20a, A20b; S5 Abschnitt 9.3).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/teams/generieren} liegt bewusst <b>nicht</b> unter
 * {@code /admin/}. Jeder Angemeldete darf generieren, auch ein {@code GAST} - das ist A15, und
 * das Kontingent begrenzt es, nicht die Rolle. Die Entscheidung faellt allein ueber den Pfad;
 * eine Rollenpruefung im Controller waere genau das, was {@code AGENT_SERVER.md} verbietet.
 *
 * <p><b>Der Unterschied zu {@code /admin/teams/generieren} ist einzig das Praefix</b> (A24).
 * Das ist gewollt - dieselbe Handlung, zwei Zugangsstufen -, macht einen Tippfehler im Pfad
 * aber schwerer erkennbar als bei verschiedenen Namen. <b>Beide Pfade stehen deshalb
 * namentlich in {@code SecurityConfigTests}</b>; die Platzhalterpruefung
 * {@code /api/*&#47;admin/**} bemerkt ihn nicht.
 *
 * <p><b>Die Einteilung wird hier nur angestossen, nicht gelesen.</b> Gelesen wird sie als Feld
 * {@code teams} der Einzelansicht ({@code GET /termine/{terminId}/lesen}) - Weggabelung B,
 * entschieden entlang der Empfehlung: Das Dashboard zeigt Termin, Teilnehmerliste und Teams
 * zusammen.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; Kontingent, Seed,
 * Transaktionsgrenze und Protokoll liegen im {@link TeamGenerierungService}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/teams")
public class TeamController {

    private final TeamGenerierungService teamGenerierungService;

    public TeamController(TeamGenerierungService teamGenerierungService) {
        this.teamGenerierungService = teamGenerierungService;
    }

    /**
     * Erzeugt die Teameinteilung eines Termins.
     *
     * <p><b>{@code 201} und kein {@code 200}:</b> Es entsteht eine Ressource - eine Zeile in
     * {@code spieltag.team_generierung}, die den bisherigen Lauf abloest. Genau darin
     * unterscheidet sich dieser Endpunkt vom manuellen Lauf nach A24, der {@code 200} liefert,
     * weil dort nichts entsteht.
     *
     * <p><b>Kein {@code Location}-Header.</b> Die Einteilung hat keine eigene Adresse; sie
     * reist in der Einzelansicht des Termins mit.
     *
     * <p>Die Antwort ist dieselbe Form, die auch dort erscheint - wer gerade generiert hat,
     * sieht dasselbe wie der, der den Termin oeffnet.
     *
     * @param anfrage Id des betroffenen Termins
     * @param request fuer die Client-IP im Protokoll
     * @param sitzung aufrufende Sitzung; liefert den Akteur des Kontingents
     * @return {@code 201} mit der neuen Einteilung
     */
    @PostMapping(value = "/generieren", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Teameinteilung> generieren(@Valid @RequestBody TerminIdRequest anfrage,
                                                     HttpServletRequest request,
                                                     @AuthenticationPrincipal AktiveSitzung sitzung) {

        Teameinteilung einteilung = Teameinteilung.von(teamGenerierungService.generieren(
                anfrage.terminId(), sitzung, ClientIpErmittler.ermitteln(request)));

        return ResponseEntity.status(HttpStatus.CREATED).body(einteilung);
    }
}
