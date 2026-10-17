package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/name/bestaetigen} (A22, Ergaenzung vom
 * 04.10.2026).
 *
 * <p><b>Bewusst ohne Namen.</b> Der Name steht seit {@code /admin/name/aendern} am Vorgang, und
 * die Nachricht an den Admin hat ihn genannt. Ein Namensfeld hier erlaubte, mit einer PIN einen
 * anderen Namen zu setzen als den, den der Admin bestaetigt zu haben glaubt.
 *
 * <p>Die Bean Validation laeuft vor dem Versuchszaehler: Eine PIN im falschen Format
 * ({@code 400}) kostet keinen der fuenf Versuche.
 *
 * @param bestaetigungsPin fuenfstellige PIN aus der E-Mail
 */
public record AdminNameBestaetigenRequest(

        @NotBlank(message = "Die Bestaetigungs-PIN darf nicht leer sein.")
        @Pattern(regexp = "\\d{5}", message = "Die Bestaetigungs-PIN besteht aus fuenf Ziffern.")
        String bestaetigungsPin) {
}
