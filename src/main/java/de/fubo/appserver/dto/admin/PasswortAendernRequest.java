package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/passwort/aendern} (A22).
 *
 * <p><b>Das alte Passwort wird verlangt, obwohl die Sitzung bereits als Admin ausgewiesen
 * ist.</b> Ein unbeaufsichtigter Rechner mit offener Sitzung soll nicht genuegen, um das
 * Passwort zu uebernehmen und den rechtmaessigen Admin auszusperren. Die Sitzung beweist,
 * <i>wer</i> handelt; das alte Passwort beweist, dass es der Berechtigte selbst ist.
 *
 * @param altesPasswort bisheriges Passwort im Klartext
 * @param neuesPasswort neues Passwort im Klartext
 */
public record PasswortAendernRequest(

        /*
         * Keine Mindestlaenge fuer das alte Passwort: Sie waere eine Auskunft ueber das
         * Format des echten Passworts. Die Obergrenze schuetzt vor unnoetig teuren
         * BCrypt-Berechnungen - dieselbe Ueberlegung wie bei AdminLoginRequest.
         */
        @NotBlank(message = "Das bisherige Passwort darf nicht leer sein.")
        @Size(max = 72, message = "Das Passwort ist zu lang.")
        String altesPasswort,

        /* Dieselben Grenzen wie beim Reset - eine zweite Regel waere eine Fehlerquelle. */
        @NotBlank(message = "Das neue Passwort darf nicht leer sein.")
        @Size(min = 10, max = 72, message = "Das Passwort muss zwischen 10 und 72 Zeichen lang sein.")
        String neuesPasswort) {
}
