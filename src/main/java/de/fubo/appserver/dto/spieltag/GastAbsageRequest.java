package de.fubo.appserver.dto.spieltag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/teilnahme/gast-absagen} (A7, A17;
 * Ergaenzung vom 24.10.2026).
 *
 * <p>Die Teilnahme wird wie bei {@link GastStufeRequest} ueber Termin <i>und</i> Gastname
 * angesprochen, nicht ueber ihre Id: Der Admin sieht in der Teilnehmerliste Namen, keine
 * Schluessel, und {@code uq_teilnahme_gast} macht das Paar eindeutig.
 *
 * <p><b>Der Name wird zeichengenau verglichen</b> - so, wie er in der Zeile steht. Er stammt
 * aus {@code teilnehmer[].anzeigeName}; ihn zu normalisieren traefe im Zweifel eine andere
 * Zeile. Nur Randleerzeichen entfernt {@link #gastNameBereinigt()}, wie beim Nachbarn.
 *
 * <p><b>Ein Name, der zu einem Profil gehoert, trifft nie:</b> Die Suche laeuft ueber
 * {@code gast_name}, und diese Spalte ist bei einem Spieler {@code NULL}. Der Endpunkt kann
 * also keinen Spieler absagen - das bleibt Sache des Spielers selbst.
 *
 * @param terminId betroffener Termin
 * @param gastName Name des Gastes, wie er in der Teilnehmerliste steht
 */
public record GastAbsageRequest(

        @NotNull(message = "Die Termin-Id fehlt.")
        Long terminId,

        @NotBlank(message = "Der Gastname fehlt.")
        @Size(max = 40, message = "Der Gastname darf höchstens 40 Zeichen lang sein.")
        String gastName) {

    /** Der Gastname ohne Randleerzeichen; verglichen wird sonst zeichengenau. */
    public String gastNameBereinigt() {
        return gastName == null ? null : gastName.trim();
    }
}
