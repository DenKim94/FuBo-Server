package de.fubo.appserver.dto.auth;

import de.fubo.appserver.domain.auth.GastStufe;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/auth/gast/anmelden} (A8, A17).
 *
 * <p><b>Warum die Selbsteinschaetzung optional ist:</b> A17 nennt die Zuweisung einer
 * Skill-Stufe ausdruecklich optional. Fehlt die Angabe, gilt {@link GastStufe#MITTEL} -
 * derselbe Wert, den auch das Datenmodell als Vorgabe fuehrt. Ein Pflichtfeld haette den
 * Gast gezwungen, sich einzuordnen, bevor er ueberhaupt hineinsieht.
 *
 * <p><b>Zur Laenge des Namens:</b> Die Spalte {@code profil.session.gast_name} ist
 * {@code VARCHAR(40)}. Waere die Pruefung hier grosszuegiger, endete ein zu langer Name in
 * einer Datenbankausnahme und damit in einem {@code 500} statt in einem {@code 400} mit
 * Feldangabe. Die Untergrenze von zwei Zeichen haelt Eintraege wie "-" aus der
 * Teilnehmerliste heraus.
 *
 * <p>Das Anhaengen von "(Gast)" an den Anzeigenamen ist Sache des Frontends (Anforderung 8)
 * und gehoert nicht in den gespeicherten Wert - sonst stuende es spaeter doppelt da.
 *
 * @param gastName temporaerer Anzeigename des Gastes
 * @param stufe    Selbsteinschaetzung; {@code null} bedeutet {@link GastStufe#MITTEL}
 */
public record GastAnmeldungRequest(
        @NotBlank(message = "Der Gastname darf nicht leer sein.")
        @Size(min = 2, max = 40, message = "Der Gastname muss zwischen 2 und 40 Zeichen lang sein.")
        String gastName,

        GastStufe stufe) {

    /** Vorgabe, wenn der Gast keine Selbsteinschaetzung abgibt. */
    private static final GastStufe VORGABE_STUFE = GastStufe.MITTEL;

    /**
     * Liefert die gewaehlte Stufe oder die Vorgabe.
     *
     * <p>Die Ableitung steht im Record und nicht im Service: Sie gehoert zur Auslegung des
     * Anfragekoerpers und damit an die API-Grenze.
     */
    public GastStufe stufeOderVorgabe() {
        return stufe != null ? stufe : VORGABE_STUFE;
    }

    /** Entfernt Randleerzeichen; der Vergleich auf Belegung wuerde sie sonst mitzaehlen. */
    public String bereinigterName() {
        return gastName == null ? null : gastName.trim();
    }
}
