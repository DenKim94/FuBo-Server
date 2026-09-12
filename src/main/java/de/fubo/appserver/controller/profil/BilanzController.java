package de.fubo.appserver.controller.profil;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.domain.auth.AktiveSitzung;
import de.fubo.appserver.dto.profil.Bilanz;
import de.fubo.appserver.service.profil.BilanzService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die eigene Bilanz (A21 in der Ergaenzung vom 30.08.2026; S6 Abschnitt 4.5, Entscheidung des
 * Haupt-Entwicklers).
 *
 * <h2>Warum ein eigener Endpunkt noetig war</h2>
 * Die Bilanz erscheint fuer den Admin in {@code GET /admin/user/lesen}. Fuer den Spieler selbst
 * gab es <b>keinen</b> Ort: Die API kennt bis heute keinen Endpunkt, ueber den ein
 * {@code USER} sein eigenes Profil liest - die Namensliste traegt nur Name und Belegtstatus,
 * die Teilnehmerliste bewusst keine Bewertungen. Additiv ging es also nicht.
 *
 * <h2>Er nimmt keine Id entgegen, und das ist der Kern</h2>
 * Die Identitaet kommt aus der Sitzung. <b>Mit einer Id im Pfad waere es ein Endpunkt "fremde
 * Bilanz lesen"</b>, und darueber hat niemand entschieden - beantwortet werden soll "wie steht
 * es um <i>mich</i>". Wer die Bilanz aller sehen darf, ist der Admin, und er hat
 * {@code /admin/user/lesen}.
 *
 * <p><b>{@code GAST} darf aufrufen</b> und bekommt die leere Bilanz statt eines Fehlers: Er
 * fuehrt keine ({@code V011}), aber das ist kein Grund, ihm eine Fehlerseite zu zeigen. Die
 * Antwort ist dieselbe wie bei einem Spieler ohne gewertete Termine.
 *
 * <p>Der Pfad liegt <b>nicht</b> unter {@code /admin/} - kein A12-Fall: Die Bilanz ist
 * Statistik und kein Skillwert.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/bilanz")
public class BilanzController {

    private final BilanzService bilanzService;

    public BilanzController(BilanzService bilanzService) {
        this.bilanzService = bilanzService;
    }

    /**
     * Liefert die Bilanz des angemeldeten Profils.
     *
     * <p><b>{@code GET} und kein {@code POST}:</b> Es wird nichts geaendert, und es gibt keinen
     * Anfragekoerper - dieselbe Form wie {@code /auth/session/lesen} und {@code /termine/lesen}.
     *
     * <p>Immer {@code 200}; ein {@code 404} gaebe es nur fuer ein Profil, das es nicht gibt,
     * und dann haette der Aufrufer keine gueltige Sitzung.
     *
     * @param sitzung aufrufende Sitzung; ihre {@code spielerId} ist die ganze Eingabe
     * @return {@code 200} mit den drei Zaehlern
     */
    @GetMapping(value = "/lesen", version = ApiVersionConfig.VERSION)
    public Bilanz lesen(@AuthenticationPrincipal AktiveSitzung sitzung) {
        return Bilanz.von(bilanzService.fuerSpieler(sitzung.spielerId()));
    }
}
