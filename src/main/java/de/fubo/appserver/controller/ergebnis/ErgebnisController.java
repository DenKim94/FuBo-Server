package de.fubo.appserver.controller.ergebnis;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.spieltag.Ergebnis;
import de.fubo.appserver.dto.spieltag.ErgebnisErfassenRequest;
import de.fubo.appserver.service.ergebnis.ErgebnisService;
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
 * Die Ergebniserfassung (A21, S6 Abschnitt 2).
 *
 * <h2>Zum Pfad</h2>
 * <b>Singular</b>, anders als bei {@code termine/} und {@code teams/}: Es gibt je Termin genau
 * eines, abgesichert durch {@code uq_ergebnis_termin}.
 *
 * <p><b>Und bewusst nicht unter {@code /admin/}</b>, wie schon {@code teams/generieren}. Wer
 * das Ergebnis eintraegt, ist der, der mit dem Telefon auf dem Platz steht. Bliebe es beim
 * Admin, kaeme der Eintrag Tage spaeter oder gar nicht - und <b>"der zuerst eingetragene
 * Eintrag gilt" ergibt nur einen Sinn, wenn mehrere es versuchen duerfen</b>. Gaeste spielen
 * mit; sie auszuschliessen waere eine Regel ohne Grund.
 *
 * <p>Die Entscheidung faellt allein ueber den Pfad; eine Rollenpruefung im Controller waere
 * genau das, was {@code AGENT_SERVER.md} verbietet. Das Gegenstueck
 * {@code /admin/ergebnis/korrigieren} liegt in
 * {@code controller.admin.ErgebnisVerwaltungController} - <b>beide Pfade stehen namentlich in
 * {@code SecurityConfigTests}</b>, weil sie sich nur im Praefix unterscheiden.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; Pruefreihenfolge,
 * Transaktionsgrenze, Bilanz und Protokoll liegen im {@link ErgebnisService}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/ergebnis")
public class ErgebnisController {

    private final ErgebnisService ergebnisService;

    public ErgebnisController(ErgebnisService ergebnisService) {
        this.ergebnisService = ergebnisService;
    }

    /**
     * Traegt das Ergebnis eines Termins ein - der erste Eintrag gilt.
     *
     * <p><b>{@code 201} und kein {@code 200}:</b> Es entsteht eine Ressource, eine Zeile in
     * {@code spieltag.ergebnis}.
     *
     * <p><b>Kein {@code Location}-Header.</b> Das Ergebnis hat keine eigene Adresse; es reist
     * in der Einzelansicht des Termins mit.
     *
     * <p><b>Der Koerper ist kein Beiwerk:</b> Er traegt die {@code version}, ohne die sich
     * nicht korrigieren laesst, und {@code erfasstVon} - genau die Auskunft, die der
     * Zweitschnellste braucht, wenn er {@code 409 ERGEBNIS_VORHANDEN} bekommt.
     *
     * @param anfrage Termin, Sieger und {@code deutlich}
     * @param request fuer die Client-IP im Protokoll
     * @param sitzung aufrufende Sitzung; liefert den Erfasser
     * @return {@code 201} mit dem erfassten Ergebnis
     */
    @PostMapping(value = "/erfassen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Ergebnis> erfassen(@Valid @RequestBody ErgebnisErfassenRequest anfrage,
                                             HttpServletRequest request,
                                             @AuthenticationPrincipal AktiveSitzung sitzung) {

        Ergebnis ergebnis = Ergebnis.von(ergebnisService.erfassen(
                anfrage, sitzung, ClientIpErmittler.ermitteln(request)));

        return ResponseEntity.status(HttpStatus.CREATED).body(ergebnis);
    }
}
