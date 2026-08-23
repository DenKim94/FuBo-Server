package de.fubo.appserver.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/auth/passwort/bestaetigen} (A22).
 *
 * <p>PIN und neues Passwort kommen zusammen - der Reset ist bewusst zweistufig und nicht
 * dreistufig (Herleitung in {@code PasswortResetService}).
 *
 * <p><b>Die Reihenfolge der Pruefungen ist Teil des Entwurfs:</b> Bean Validation laeuft
 * hier, <i>bevor</i> der Controller den Versuchszaehler beruehrt. Ein an der Passwortregel
 * gescheiterter Aufruf ({@code 400}) kostet damit keinen der fuenf Versuche. Ohne diese
 * Reihenfolge waere ein zu kurzes Passwort trotz richtiger PIN ein verlorener Versuch.
 *
 * @param bestaetigungsPin fuenfstellige PIN aus der E-Mail
 * @param neuesPasswort    Klartext des neuen Admin-Passworts
 */
public record PasswortResetBestaetigenRequest(

        /*
         * Anders als bei der zentralen PIN steht das Format hier ausdruecklich im Vertrag:
         * Der Server hat die PIN selbst erzeugt und verraet damit nichts, was ein Angreifer
         * nicht ohnehin aus der eigenen E-Mail wuesste.
         */
        @NotBlank(message = "Die Bestaetigungs-PIN darf nicht leer sein.")
        @Pattern(regexp = "\\d{5}", message = "Die Bestaetigungs-PIN besteht aus fuenf Ziffern.")
        String bestaetigungsPin,

        /*
         * Untergrenze 10, keine Vorgaben zu Zeichenklassen: Laenge traegt mehr als
         * erzwungene Sonderzeichen, die Menschen zu "Passwort1!" verleiten.
         *
         * Obergrenze 72, weil BCrypt darueber hinaus STILLSCHWEIGEND abschneidet - ein
         * Passwort mit 100 Zeichen waere nur in seinen ersten 72 wirksam, ohne dass es
         * jemandem auffiele.
         */
        @NotBlank(message = "Das neue Passwort darf nicht leer sein.")
        @Size(min = 10, max = 72, message = "Das Passwort muss zwischen 10 und 72 Zeichen lang sein.")
        String neuesPasswort) {
}
