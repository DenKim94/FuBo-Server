package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.admin.SpielerAngelegt;
import de.fubo.appserver.dto.admin.SpielerAnlegenRequest;
import de.fubo.appserver.dto.admin.SpielerBlockierenRequest;
import de.fubo.appserver.dto.admin.SpielerIdRequest;
import de.fubo.appserver.service.profil.SpielerVerwaltungService;
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
 * Verwaltung von Spielerprofilen durch den Admin (A13, S2b Abschnitt 8).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/admin/user/...}. Die Filterchain verlangt fuer
 * alles unterhalb von {@code /api/*&#47;admin/**} die Rolle {@code ADMIN}; eine eigene Regel
 * je Endpunkt braucht es deshalb nicht.
 *
 * <p>Die Bearbeitung bestehender Profile - Name und Skillwerte aendern - gehoert laut
 * Anleitung nach S3 und fehlt hier bewusst. Was S2b abdeckt, ist der Bestand: anlegen,
 * entfernen, sperren, freigeben.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; Pruefungen,
 * Transaktionsgrenze und Protokoll liegen im {@link SpielerVerwaltungService}. Anders als
 * beim Passwort-Reset muss hier nichts im Controller protokolliert werden: Es gibt keinen
 * Fehlversuch, der einen Rollback ueberleben muesste.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class SpielerController {

    private final SpielerVerwaltungService spielerVerwaltungService;

    public SpielerController(SpielerVerwaltungService spielerVerwaltungService) {
        this.spielerVerwaltungService = spielerVerwaltungService;
    }

    /**
     * Legt ein Spielerprofil an.
     *
     * <p>Antwortet mit {@code 201} und der neuen Id. <b>Ohne {@code Location}-Header</b>: Es
     * gibt bis S3 keinen Endpunkt, der ein einzelnes Profil ausliefert - ein Verweis auf
     * eine Adresse, die es nicht gibt, waere eine leere Zusage.
     *
     * @param anfrage Name und optionale Skillwerte
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung; nie {@code null}, weil die Filterchain den
     *                Zugriff sonst gar nicht durchgelassen haette
     * @return {@code 201} mit Id und uebernommenem Namen
     */
    @PostMapping(value = "/user/anlegen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<SpielerAngelegt> spielerAnlegen(@Valid @RequestBody SpielerAnlegenRequest anfrage,
                                                          HttpServletRequest request,
                                                          @AuthenticationPrincipal AktiveSitzung sitzung) {

        SpielerAngelegt angelegt = spielerVerwaltungService.anlegen(
                anfrage.nameGetrimmt(),
                anfrage.skillsOderLeer(),
                sitzung.spielerId(),
                ClientIpErmittler.ermitteln(request));

        return ResponseEntity.status(HttpStatus.CREATED).body(angelegt);
    }

    /**
     * Entfernt ein Spielerprofil endgueltig.
     *
     * <p>Gelingt nur, solange am Profil nichts haengt ausser seinen Skillwerten. Andernfalls
     * {@code 409 PROFIL_IN_VERWENDUNG} - dann ist {@link #spielerBlockieren} der richtige Weg.
     *
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/user/entfernen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> spielerEntfernen(@Valid @RequestBody SpielerIdRequest anfrage,
                                                 HttpServletRequest request,
                                                 @AuthenticationPrincipal AktiveSitzung sitzung) {

        spielerVerwaltungService.entfernen(
                anfrage.spielerId(), sitzung.spielerId(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }

    /**
     * Sperrt ein Spielerprofil oder gibt es wieder frei.
     *
     * <p>Beim Sperren werden die offenen Sitzungen des Profils widerrufen; der Gesperrte ist
     * damit sofort abgemeldet und nicht erst nach Ablauf seiner Sitzung.
     *
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/user/blockieren", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> spielerBlockieren(@Valid @RequestBody SpielerBlockierenRequest anfrage,
                                                  HttpServletRequest request,
                                                  @AuthenticationPrincipal AktiveSitzung sitzung) {

        spielerVerwaltungService.blockieren(
                anfrage.spielerId(), anfrage.blockieren(),
                sitzung.spielerId(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }
}
