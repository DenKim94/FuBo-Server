package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.spieltag.ErgebnisKorrigierenRequest;
import de.fubo.appserver.service.ergebnis.ErgebnisService;
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
 * Die Ergebniskorrektur durch den Admin (A21, S6 Abschnitt 3).
 *
 * <h2>Warum ein eigener Controller</h2>
 * Der Ort eines Endpunkts <i>ist</i> die Autorisierungsentscheidung: {@code /api/*&#47;admin/**}
 * verlangt {@code ROLE_ADMIN}, alles Uebrige nicht. Das Erfassen und das Korrigieren liegen
 * deshalb in zwei Klassen mit zwei Praefixen - ein Controller mit zwei Zugangsregeln je nach
 * Methode waere die verbotene Pruefung im Controller.
 *
 * <p>Die Klasse liegt in {@code controller.admin} wie die uebrigen Verwaltungscontroller:
 * <b>{@code admin} ist ein Zugriffs-, kein Datenbereich.</b> Die Fachlogik bleibt im
 * {@link ErgebnisService} unter {@code service.ergebnis} - dieselbe Aufteilung wie bei
 * {@code TerminVerwaltungController} und {@code TeamVerwaltungController}.
 *
 * <p><b>Der Pfad steht namentlich in {@code SecurityConfigTests}</b>: Er unterscheidet sich
 * von {@code /ergebnis/erfassen} im Wesentlichen nur im Praefix, und die Platzhalterpruefung
 * {@code /api/*&#47;admin/**} bemerkt einen Tippfehler darin nicht.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin/ergebnis")
public class ErgebnisVerwaltungController {

    private final ErgebnisService ergebnisService;

    public ErgebnisVerwaltungController(ErgebnisService ergebnisService) {
        this.ergebnisService = ergebnisService;
    }

    /**
     * Korrigiert ein bereits erfasstes Ergebnis.
     *
     * <p><b>{@code 204} und kein Koerper:</b> Es entsteht nichts, und geaendert hat der Admin
     * genau das, was er gesendet hat. Die neue {@code version} bekommt er beim naechsten Lesen
     * des Termins - anders als beim Erfassen, wo der Koerper die <i>erste</i> Version traegt,
     * die der Client sonst nirgends haette.
     *
     * <p><b>Ein Voll-Update, kein feldweises:</b> {@code deutlich} ist ein {@code boolean},
     * und feldweise waere {@code false} nicht von "nicht angegeben" zu unterscheiden - ein
     * gesetzter Haken liesse sich nie wieder entfernen.
     *
     * <p>Auch eine Korrektur, die nichts aendert, wird angenommen und protokolliert: "ich habe
     * nachgesehen und es stimmt so" ist eine Handlung.
     *
     * @param anfrage Termin, neuer Sieger, {@code deutlich} und die gelesene {@code version}
     * @param request fuer die Client-IP im Protokoll
     * @param sitzung aufrufende Adminsitzung
     * @return {@code 204} ohne Koerper
     */
    @PostMapping(value = "/korrigieren", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> korrigieren(
            @Valid @RequestBody ErgebnisKorrigierenRequest anfrage,
            HttpServletRequest request,
            @AuthenticationPrincipal AktiveSitzung sitzung) {

        ergebnisService.korrigieren(anfrage, sitzung, ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }
}
