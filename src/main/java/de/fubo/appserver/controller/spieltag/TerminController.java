package de.fubo.appserver.controller.spieltag;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.spieltag.TerminDetails;
import de.fubo.appserver.dto.spieltag.TerminUebersicht;
import de.fubo.appserver.service.spieltag.TerminService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Termine aus Sicht der Spieler und Gaeste (A7, A9; S4 Abschnitt 2).
 *
 * <p><b>Zum Pfad:</b> {@code /api/{version}/termine/...}. Der Bereich liegt bewusst
 * <b>nicht</b> unter {@code /admin/} - die Filterchain gibt damit alles ab der Stufe
 * {@code PROFILE_AUTHENTICATED} frei, also auch fuer Gaeste (Weggabelung F). Eine neue Regel
 * in {@code SecurityConfig} braucht es nicht; die Zuordnung entsteht allein durch den Pfad.
 *
 * <p><b>Warum Gaeste lesen duerfen:</b> Ein Gast, der Termine nicht sieht, kann nicht
 * zusagen. A8 und A17 waeren damit ohne Zweck, und der Gastplatz aus S2 waere eine
 * Vorkehrung fuer nichts.
 *
 * <p><b>Was auch ein Gast nicht sieht:</b> Skillwerte. Die Antworten dieses Controllers
 * tragen keine Bewertungen - fuer alle Rollen gleich. Damit bleibt A12 gewahrt, ohne dass
 * die Antwort je nach Rolle anders aussehen muesste; eine rollenabhaengige Antwort waere der
 * teurere Weg, weil sie an jeder Stelle mitgedacht werden muesste.
 *
 * <p>Der Controller enthaelt keine Fachlogik ausser der Uebergabe und der Abbildung auf den
 * Vertrag; Stichtag, Zeitzone und Transaktionsgrenze liegen im {@link TerminService}.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/termine")
public class TerminController {

    private final TerminService terminService;

    public TerminController(TerminService terminService) {
        this.terminService = terminService;
    }

    /**
     * Liefert die Termine ab einem Stichtag.
     *
     * <p>Der Einstieg beider Dashboards aus A9: je Termin die Stammdaten, die Zahl der
     * Zusagen und die eigene Rueckmeldung - genug fuer die Kachelansicht, ohne dass der
     * Client je Termin einen zweiten Aufruf braucht.
     *
     * <p><b>{@code @DateTimeFormat} ist hier nicht ueberfluessig.</b> Ohne die Angabe haengt
     * die Auslegung eines Abfrageparameters an der Voreinstellung des Konvertierungsdienstes;
     * mit ihr steht das erwartete Format am Endpunkt und stimmt mit dem ueberein, was der
     * Vertrag zusagt ({@code format: date}).
     *
     * @param ab      Stichtag einschliesslich; fehlt er, gilt der heutige Tag
     * @param sitzung aufrufende Sitzung; nie {@code null}, weil die Filterchain den Zugriff
     *                sonst gar nicht durchgelassen haette
     * @return {@code 200} mit den Terminen, nach Datum und Uhrzeit sortiert
     */
    @GetMapping(value = "/lesen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<List<TerminUebersicht>> termineLesen(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ab,
            @AuthenticationPrincipal AktiveSitzung sitzung) {

        List<TerminUebersicht> termine = terminService.uebersichtLesen(ab, sitzung).stream()
                .map(TerminUebersicht::von)
                .toList();

        return ResponseEntity.ok(termine);
    }

    /**
     * Liefert einen einzelnen Termin samt seiner {@code version}.
     *
     * <p><b>Der Aufruf vor jeder Aenderung:</b> {@code /admin/termin/aendern} verlangt die
     * Version aus dieser Antwort zurueck - derselbe Ablauf wie bei der Konfiguration.
     *
     * <p><b>Die Teilnehmerliste kommt mit S4-Paket 7 dazu</b>, als zusaetzliches Feld dieser
     * Antwort und nicht als zweiter Endpunkt: Wer einen Termin oeffnet, will die Teilnehmer
     * sehen, und zwei Aufrufe fuer eine Ansicht sind zwei Gelegenheiten fuer einen
     * inkonsistenten Stand.
     *
     * @param terminId gesuchter Termin
     * @param sitzung  aufrufende Sitzung
     * @return {@code 200} mit dem Termin; {@code 404}, wenn es die Id nicht gibt
     */
    @GetMapping(value = "/{terminId}/lesen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<TerminDetails> terminLesen(@PathVariable Long terminId,
                                                     @AuthenticationPrincipal AktiveSitzung sitzung) {

        return ResponseEntity.ok(TerminDetails.von(terminService.einzelnLesen(terminId, sitzung)));
    }
}
