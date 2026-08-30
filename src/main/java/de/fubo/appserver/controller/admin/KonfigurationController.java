package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.admin.Konfiguration;
import de.fubo.appserver.dto.admin.KonfigurationAendernRequest;
import de.fubo.appserver.service.config.ConfigService;
import de.fubo.appserver.utils.ClientIpErmittler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liest und aendert die anwendungsweite Admin-Konfiguration
 * (A10, A11, A14, A15, A17, A23; S3 Abschnitt 5).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/admin/config/...}. Die Filterchain verlangt fuer
 * alles unterhalb von {@code /api/*&#47;admin/**} die Rolle {@code ADMIN}; eine eigene Regel je
 * Endpunkt braucht es nicht.
 *
 * <p><b>Zwei Endpunkte, nicht einer.</b> {@code aendern} ist ein Voll-Update und setzt auf der
 * Version auf, die {@code lesen} geliefert hat - beide gehoeren zu einem Ablauf, aber zu
 * verschiedenen Zeitpunkten. Ein einzelner Endpunkt, der bei einem leeren Koerper liest und sonst
 * schreibt, spaerte nichts und verwischte den Unterschied zwischen Lesen und Schreiben.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; Versionsvergleich,
 * Plausibilitaet, Gastplaetze, Transaktionsgrenze und Protokoll liegen im {@link ConfigService}.
 * Anders als beim {@code SkillKategorieController} kommt dieser Bereich deshalb nicht ohne Dienst
 * aus - hier ist eine ganze Reihe von Entscheidungen zu treffen.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class KonfigurationController {

    private final ConfigService configService;

    public KonfigurationController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Liefert die aktuelle Konfiguration samt Version.
     *
     * <p><b>Vor jedem Schreibzugriff aufzurufen:</b> {@link #konfigurationAendern} verlangt die
     * {@code version} aus dieser Antwort zurueck und lehnt eine veraltete mit
     * {@code 409 DATEN_VERALTET} ab.
     *
     * <p><b>Ohne {@code Cache-Control}-Header.</b> Serverseitig gibt es keinen Zwischenspeicher;
     * dem Browser das Zwischenspeichern zu erlauben hiesse, dass der Admin nach einer Aenderung
     * seine eigenen alten Werte saehe - und mit ihnen eine veraltete Version, die jeden weiteren
     * Speicherversuch in einen Konflikt liefe.
     *
     * @return {@code 200} mit den elf aenderbaren Feldern, {@code geaendertAm} und {@code version}
     */
    @GetMapping(value = "/config/lesen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Konfiguration> konfigurationLesen() {
        return ResponseEntity.ok(Konfiguration.von(configService.lesen()));
    }

    /**
     * Schreibt die Konfiguration vollstaendig.
     *
     * <p>Der Anfragekoerper enthaelt alle elf aenderbaren Felder und die Version - Begruendung am
     * DTO {@link KonfigurationAendernRequest}.
     *
     * <p><b>Antwortet mit {@code 204} und nicht mit dem neuen Stand.</b> Der Client haette davon
     * nur die neue Version; alles Uebrige hat er gerade selbst geschickt. Wer weiterarbeiten will,
     * liest neu - das ist derselbe Aufruf, den er vor dem Speichern ohnehin gemacht hat.
     *
     * @param anfrage alle elf Felder samt der Version, auf der sie aufsetzen
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung; nie {@code null}, weil die Filterchain den Zugriff
     *                sonst gar nicht durchgelassen haette
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/config/aendern", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> konfigurationAendern(@Valid @RequestBody KonfigurationAendernRequest anfrage,
                                                     HttpServletRequest request,
                                                     @AuthenticationPrincipal AktiveSitzung sitzung) {

        configService.aktualisieren(anfrage, sitzung.spielerId(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }
}
