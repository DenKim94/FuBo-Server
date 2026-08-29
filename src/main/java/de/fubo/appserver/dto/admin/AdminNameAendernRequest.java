package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/name/aendern} (S3, Vorgabe des
 * Haupt-Entwicklers vom 29.08.2026).
 *
 * <p>Aendert den <b>Anmeldenamen</b> des Admins - denselben Wert, den
 * {@code /auth/admin/anmelden} seit dem 29.08.2026 neben dem Passwort verlangt. Technisch ist
 * es der Name des Adminprofils; fachlich ist es ein Anmeldemerkmal, weshalb der Endpunkt
 * neben Passwort- und PIN-Aenderung steht und nicht bei der Spielerverwaltung.
 *
 * <p><b>Keine Profil-Id.</b> Es gibt genau einen Admin
 * ({@code uq_spieler_genau_ein_admin}); eine Id waere ein Feld ohne Auswahl - dieselbe
 * Ueberlegung wie bei {@link PinAendernRequest} und {@link PasswortAendernRequest}.
 *
 * <p><b>Zur Obergrenze:</b> 60 Zeichen, die Breite von {@code profil.spieler.name}. Eine
 * grosszuegigere Grenze endete in einer Datenbankausnahme und damit in einem {@code 500}
 * statt in einem {@code 400} mit Feldangabe.
 *
 * @param neuerName Klartext des neuen Anmeldenamens
 */
public record AdminNameAendernRequest(

        @NotBlank(message = "Der neue Anmeldename darf nicht leer sein.")
        @Size(max = 60, message = "Der Anmeldename darf hoechstens 60 Zeichen lang sein.")
        String neuerName) {

    /**
     * Der Name ohne Randleerzeichen.
     *
     * <p><b>Das ist hier keine Kosmetik.</b> {@code /auth/admin/anmelden} trimmt seine
     * Eingabe ebenfalls; ein mit Randleerzeichen gespeicherter Name liesse sich deshalb nie
     * eingeben. Der Admin sperrte sich mit der eigenen Umbenennung aus - und der
     * Passwort-Reset holt das Passwort zurueck, nie den Namen.
     */
    public String bereinigterName() {
        return neuerName == null ? null : neuerName.trim();
    }
}
