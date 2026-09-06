package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.spieltag.ManuelleAuswahl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/teams/generieren} (A24, S5 Abschnitt 9.4).
 *
 * <pre>
 * { "spielerIds": [3, 7, 12, 19],
 *   "gaeste": [ { "stufe": "STARK", "name": "Testgast 1" }, { "stufe": "SCHWACH" } ] }
 * </pre>
 *
 * <h2>Kein {@code algorithmType} und kein {@code auswechselModus}</h2>
 * Beides entscheidet {@code configs.app_config}, wie beim Termin-Lauf. Ein Feld hier waere eine
 * zweite Stelle, an der das Verfahren gewaehlt wird - und die erste, an der beide Laeufe
 * verschieden funktionieren, ohne dass es jemand angeordnet haette.
 *
 * <h2>Beide Listen duerfen fehlen, aber nicht beide leer sein</h2>
 * Ein Lauf nur mit Gaesten ist zulaessig, einer nur mit Profilen ohnehin. Dass gar niemand
 * genannt ist, faengt der {@code AufstellungService} mit {@code 400 EINGABE_UNGUELTIG} ab -
 * <b>nicht die Bean Validation</b>: Die Bedingung verbindet zwei Felder, und eine
 * Klassen-Annotation dafuer waere schwerer zu lesen als die Pruefung an der Stelle, an der
 * ohnehin alle uebrigen Faelle geprueft werden.
 *
 * @param spielerIds Ids vorhandener Spielerprofile; darf fehlen oder leer sein
 * @param gaeste     frei angelegte Gaeste mit Stufe; darf fehlen oder leer sein
 */
public record ManuelleGenerierungRequest(
        List<@NotNull(message = "Eine Profil-Id ist leer.") Long> spielerIds,
        List<@Valid @NotNull(message = "Ein Gasteintrag ist leer.") GastAuswahl> gaeste) {

    /**
     * Bildet auf den Domaentyp ab.
     *
     * <p>{@link ManuelleAuswahl} nimmt {@code null}-Listen entgegen und macht leere daraus -
     * das erspart hier eine Fallunterscheidung und jedem Leser des Dienstes eine zweite.
     */
    public ManuelleAuswahl nachDomaene() {
        return new ManuelleAuswahl(
                spielerIds,
                gaeste == null ? null : gaeste.stream().map(GastAuswahl::nachDomaene).toList());
    }
}
