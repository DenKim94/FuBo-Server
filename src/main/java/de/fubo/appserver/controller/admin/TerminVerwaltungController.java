package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.spieltag.SerieAngelegt;
import de.fubo.appserver.dto.spieltag.SerieAnlegenRequest;
import de.fubo.appserver.dto.spieltag.TerminAendernRequest;
import de.fubo.appserver.dto.spieltag.TerminAngelegt;
import de.fubo.appserver.dto.spieltag.TerminAnlegenRequest;
import de.fubo.appserver.dto.spieltag.TerminIdRequest;
import de.fubo.appserver.service.spieltag.SerienService;
import de.fubo.appserver.service.spieltag.TerminService;
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
 * Termine und Terminserien aus Sicht des Admins (A18; S4 Abschnitte 3 und 4).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/admin/termin/...} und
 * {@code /api/{version}/admin/serie/anlegen}. Die Filterchain verlangt fuer alles unterhalb
 * von {@code /api/*&#47;admin/**} die Rolle {@code ADMIN}; eine eigene Regel je Endpunkt
 * braucht es nicht.
 *
 * <p><b>Zum Paketschnitt:</b> Der Controller liegt in {@code controller/admin}, seine DTOs
 * dagegen in {@code dto/spieltag}. Das ist kein Widerspruch: {@code admin} ist ein
 * Zugriffs-, kein Datenbereich - dieselbe Aufteilung wie bei den Profilen, wo
 * {@code NamenController} (auth) und {@code SpielerController} (admin) auf derselben Tabelle
 * arbeiten. Die Regel, nach der Skill-DTOs unter {@code dto/admin} bleiben, greift hier
 * nicht: Termine tragen keine Bewertungen.
 *
 * <p><b>Das Aktionssegment im Pfad</b> ({@code /termin/anlegen} statt {@code POST /termin})
 * setzt die Linie aus S2b und S3 fort - der Haupt-Entwickler hat sie dort ausdruecklich der
 * reinen HTTP-Methoden-Semantik vorgezogen, weil so jede Operation unabhaengig
 * versionierbar bleibt.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe; Zeitpruefung,
 * Kollisionsbehandlung, Versionsvergleich, Transaktionsgrenze und Protokoll liegen im
 * {@link TerminService} und im {@link SerienService}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class TerminVerwaltungController {

    private final TerminService terminService;
    private final SerienService serienService;

    public TerminVerwaltungController(TerminService terminService, SerienService serienService) {
        this.terminService = terminService;
        this.serienService = serienService;
    }

    /**
     * Legt einen Einzeltermin an.
     *
     * <p><b>{@code 201} mit der Id</b>, nicht {@code 204}: Die Id ist der einzige Wert, den
     * der Aufrufer nicht schon kennt - ohne sie muesste er die Terminliste erneut lesen, um
     * den eben angelegten Termin aendern oder absagen zu koennen.
     *
     * @param anfrage Datum, Uhrzeit und optionaler Ort
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung
     * @return {@code 201} mit Id, Datum und Uhrzeit
     */
    @PostMapping(value = "/termin/anlegen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<TerminAngelegt> terminAnlegen(@Valid @RequestBody TerminAnlegenRequest anfrage,
                                                        HttpServletRequest request,
                                                        @AuthenticationPrincipal AktiveSitzung sitzung) {

        TerminAngelegt angelegt = terminService.anlegen(
                anfrage.datum(), anfrage.uhrzeit(), anfrage.ortBereinigt(),
                sitzung.spielerId(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.status(HttpStatus.CREATED).body(angelegt);
    }

    /**
     * Aendert Datum, Uhrzeit oder Ort eines Termins.
     *
     * <p><b>Antwortet mit {@code 204} und nicht mit dem neuen Stand.</b> Der Client haette
     * davon nur die neue Version; alles Uebrige hat er gerade selbst geschickt. Wer
     * weiterarbeiten will, liest neu - das ist derselbe Aufruf, den er vor dem Speichern
     * ohnehin gemacht hat.
     *
     * @param anfrage Id, Version und die zu aendernden Felder
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/termin/aendern", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> terminAendern(@Valid @RequestBody TerminAendernRequest anfrage,
                                              HttpServletRequest request,
                                              @AuthenticationPrincipal AktiveSitzung sitzung) {

        terminService.aendern(anfrage, sitzung.spielerId(), ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }

    /**
     * Sagt einen Termin ab.
     *
     * <p><b>Ein Termin wird nie geloescht, nur auf {@code ABGESAGT} gesetzt</b> - Begruendung
     * am {@code TerminService}. Die Absage ist endgueltig; das gehoert in die
     * Bestaetigungsabfrage der Oberflaeche.
     *
     * @param anfrage Id des abzusagenden Termins
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung
     * @return {@code 204} ohne Inhalt
     */
    @PostMapping(value = "/termin/absagen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<Void> terminAbsagen(@Valid @RequestBody TerminIdRequest anfrage,
                                              HttpServletRequest request,
                                              @AuthenticationPrincipal AktiveSitzung sitzung) {

        terminService.absagen(anfrage.terminId(), sitzung.spielerId(),
                ClientIpErmittler.ermitteln(request));

        return ResponseEntity.noContent().build();
    }

    /**
     * Legt eine befristete Terminserie an und erzeugt ihre Termine.
     *
     * <p><b>{@code 201} mit beiden Listen:</b> den erzeugten und den uebersprungenen
     * Terminen. Kollidierende Zeitpunkte lassen die Serie nicht scheitern - die Oberflaeche
     * sollte die zweite Liste anzeigen, sonst faellt eine unvollstaendige Serie erst beim
     * Nachzaehlen auf.
     *
     * @param anfrage Titel, Wochentag, Uhrzeit, Zeitraum und optionaler Ort
     * @param request fuer die Ermittlung der Client-IP
     * @param sitzung aufrufende Adminsitzung
     * @return {@code 201} mit der Serie samt beider Listen
     */
    @PostMapping(value = "/serie/anlegen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<SerieAngelegt> serieAnlegen(@Valid @RequestBody SerieAnlegenRequest anfrage,
                                                      HttpServletRequest request,
                                                      @AuthenticationPrincipal AktiveSitzung sitzung) {

        SerieAngelegt angelegt = serienService.anlegen(anfrage, sitzung.spielerId(),
                ClientIpErmittler.ermitteln(request));

        return ResponseEntity.status(HttpStatus.CREATED).body(angelegt);
    }
}
