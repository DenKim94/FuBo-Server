package de.fubo.appserver.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/auth/admin/anmelden} (A22).
 *
 * <p>Die zweite Login-Stufe fuer den Adminzugang. Er ist die Alternative zur Namensauswahl:
 * Das Adminprofil ist ein technisches Konto und steht in der Namensliste nicht zur Verfuegung.
 *
 * <p>Es wird ausschliesslich das Passwort uebertragen - keine Kennung. Es gibt genau einen
 * Admin ({@code ck_admin_konto_singleton}), ein Benutzername waere also ein Feld ohne
 * Auswahl. Weniger uebertragen heisst auch: weniger, das in einem Protokoll landen kann.
 *
 * <p>Die Obergrenze ist keine fachliche Vorgabe, sondern schuetzt vor unnoetig teuren
 * BCrypt-Berechnungen mit sehr langen Eingaben - dieselbe Ueberlegung wie bei der zentralen
 * PIN. Eine <b>Mindest</b>laenge steht bewusst nicht hier: Sie waere eine Auskunft ueber das
 * Format des echten Passworts.
 *
 * @param passwort Klartext des Admin-Passworts
 */
public record AdminLoginRequest(
        @NotBlank(message = "Das Passwort darf nicht leer sein.")
        @Size(max = 72, message = "Das Passwort ist zu lang.")
        String passwort) {
}
