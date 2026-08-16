package de.fubo.appserver.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/auth/pin}.
 *
 * <p>Die Pruefung des Wertebereichs gehoert an die API-Grenze und damit hierher, nicht an
 * eine Entity: Ein leerer Koerper soll mit {@code 400} und Feldangabe beantwortet werden,
 * bevor ueberhaupt eine BCrypt-Berechnung anlaeuft.
 *
 * <p>Die Obergrenze ist keine fachliche Vorgabe, sondern schuetzt vor unnoetig teuren
 * Hash-Berechnungen mit sehr langen Eingaben. Eine <b>Mindest</b>laenge steht hier
 * bewusst nicht: Sie waere eine Auskunft ueber das Format der echten PIN.
 *
 * @param pin Klartext der eingegebenen zentralen PIN
 */
public record PinLoginRequest(
        @NotBlank(message = "Die PIN darf nicht leer sein.")
        @Size(max = 72, message = "Die PIN ist zu lang.")
        String pin) {
}
