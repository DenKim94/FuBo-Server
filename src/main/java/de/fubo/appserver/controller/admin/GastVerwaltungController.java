package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.admin.GastFreigebenRequest;
import de.fubo.appserver.dto.admin.GastPlatzInfo;
import de.fubo.appserver.service.auth.GastVerwaltungService;
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

import java.util.List;

/**
 * Die Gastplaetze aus Sicht des Admins (A17, Vorgabe des Haupt-Entwicklers vom 30.08.2026).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/admin/gast/...}. Die Filterchain verlangt fuer alles
 * unterhalb von {@code /api/*&#47;admin/**} die Rolle {@code ADMIN}; eine eigene Regel je Endpunkt
 * braucht es nicht.
 *
 * <p><b>Nicht zu verwechseln mit {@code /auth/gast/anmelden}.</b> Jener Endpunkt <i>belegt</i>
 * einen Platz und ist die Selbstbedienung des Gastes; diese beiden zeigen und raeumen ihn und
 * setzen {@code ROLE_ADMIN} voraus.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; Pruefungen,
 * Transaktionsgrenze, Sitzungswiderruf und Protokoll liegen im {@link GastVerwaltungService}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class GastVerwaltungController {

    private final GastVerwaltungService gastVerwaltungService;

    public GastVerwaltungController(GastVerwaltungService gastVerwaltungService) {
        this.gastVerwaltungService = gastVerwaltungService;
    }

    /**
     * Liefert alle Gastplaetze samt Zustand.
     *
     * <p><b>Wofuer der Endpunkt gedacht ist:</b> Er beantwortet die Betriebsfrage "warum bekommt
     * der naechste Gast keinen Platz". Die haeufigste Antwort ist ein belegter Platz ohne lebende
     * Sitzung - {@code belegt} wahr, {@code sitzungGueltig} falsch. Dieser Zustand entsteht
     * regelmaessig, weil eine ablaufende Gastsitzung ihren Platz <b>nicht</b> von selbst freigibt;
     * das tut erst der naechtliche Aufraeumlauf oder {@link #gastplaetzeFreigeben}.
     *
     * <p>Enthalten sind auch die <b>unwirksamen</b> Plaetze jenseits der aktuellen
     * {@code anzGuests}. Sie entstehen, sobald der Admin die Zahl senkt, und koennen belegt sein.
     *
     * @return {@code 200} mit allen Plaetzen, nach Nummer sortiert
     */
    @GetMapping(value = "/gast/lesen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<List<GastPlatzInfo>> gastplaetzeLesen() {
        return ResponseEntity.ok(gastVerwaltungService.uebersicht());
    }

    /**
     * Gibt einzelne oder alle belegten Gastplaetze frei und meldet die zugehoerigen Gaeste ab.
     *
     * <p><b>Der Vorgang meldet ab, er raeumt nicht nur eine Tabelle auf.</b> Ein Gast, der noch
     * aktiv ist, fliegt sofort heraus - Begruendung am {@link GastVerwaltungService}. Wer nur
     * verwaiste Plaetze aufraeumen will, prueft vorher {@link #gastplaetzeLesen} auf
     * {@code sitzungGueltig: false}.
     *
     * <p><b>Antwortet mit {@code 204}</b> und nicht mit der neuen Liste. Der Aufrufer, der gerade
     * freigegeben hat, braucht sie nur, wenn er sie anzeigt - und dann holt er sie ueber
     * {@link #gastplaetzeLesen}, die ohnehin frisch rechnet.
     *
     * @param anfrage entweder Platznummern oder das ausdrueckliche {@code alle}
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung; nie {@code null}, weil die Filterchain den Zugriff
     *                sonst gar nicht durchgelassen haette
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/gast/freigeben", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> gastplaetzeFreigeben(@Valid @RequestBody GastFreigebenRequest anfrage,
                                                     HttpServletRequest request,
                                                     @AuthenticationPrincipal AktiveSitzung sitzung) {

        gastVerwaltungService.freigeben(anfrage, sitzung.spielerId(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }
}
