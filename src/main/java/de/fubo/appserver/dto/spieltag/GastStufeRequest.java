package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.auth.GastStufe;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/teilnahme/gast-stufe} (A17, S4 Abschnitt 6.3).
 *
 * <p>Die Teilnahme wird ueber Termin <i>und</i> Gastname angesprochen, nicht ueber ihre Id:
 * Der Admin sieht in der Teilnehmerliste Namen, keine Schluessel, und
 * {@code uq_teilnahme_gast} macht das Paar eindeutig. Eine Id waere ein zweiter Wert, den die
 * Liste nur fuer diesen einen Aufruf mitfuehren muesste.
 *
 * <p><b>Der Name wird zeichengenau verglichen</b> - so, wie er in der Zeile steht. Er stammt
 * aus der Antwort des Servers; ihn zu normalisieren traefe im Zweifel eine andere Zeile.
 *
 * @param terminId betroffener Termin
 * @param gastName Name des Gastes, wie er in der Teilnehmerliste steht
 * @param stufe    neue Selbsteinschaetzung; STARK, MITTEL oder SCHWACH
 */
public record GastStufeRequest(

        @NotNull(message = "Die Termin-Id fehlt.")
        Long terminId,

        @NotBlank(message = "Der Gastname fehlt.")
        @Size(max = 40, message = "Der Gastname darf höchstens 40 Zeichen lang sein.")
        String gastName,

        @NotNull(message = "Die Skill-Stufe fehlt.")
        GastStufe stufe) {

    /** Der Gastname ohne Randleerzeichen; verglichen wird sonst zeichengenau. */
    public String gastNameBereinigt() {
        return gastName == null ? null : gastName.trim();
    }
}
