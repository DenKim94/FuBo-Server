package de.fubo.appserver.dto.spieltag;

import jakarta.validation.constraints.NotNull;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/termin/absagen} (S4, Abschnitt 3.4).
 *
 * <p>Die Id steht im Koerper und nicht im Pfad, weil das Aktionssegment am Ende des Pfades
 * steht ({@code /termin/absagen}) - dieselbe Aufteilung wie bei
 * {@code /admin/user/entfernen}.
 *
 * <p><b>Ohne {@code version}, anders als beim Aendern.</b> Das Absagen setzt ein Feld auf
 * einen festen Wert; es gibt nichts, was eine gleichzeitige Aenderung ueberschreiben
 * koennte. Ein zweiter Aufruf laeuft ohnehin in {@code 409 TERMIN_GESCHLOSSEN}, weil der
 * Termin dann nicht mehr {@code GEPLANT} ist.
 *
 * @param terminId Id des abzusagenden Termins
 */
public record TerminIdRequest(

        @NotNull(message = "Die Termin-Id fehlt.")
        Long terminId) {
}
