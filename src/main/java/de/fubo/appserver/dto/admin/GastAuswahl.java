package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.auth.GastStufe;
import de.fubo.appserver.domain.spieltag.Gastauswahl;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ein frei angelegter Gast im Anfragekoerper des manuellen Laufs (A24, S5 Abschnitt 9.4).
 *
 * <h2>Die Stufe ist Pflicht, der Name nicht</h2>
 * Anders als bei der Zusage am Termin (A17) gibt es hier niemanden, der eine fehlende Stufe
 * spaeter nachtraegt - der Ersatzwert {@code MITTEL} gehoert zur Termin-Quelle. Der Name darf
 * fehlen; die Vorgabe {@code Gast 1}, {@code Gast 2}, ... vergibt der
 * {@code AufstellungService} und nicht dieses DTO, weil sie an der Dopplungspruefung haengt.
 *
 * <h2>Getrimmt wird hier</h2>
 * Das Entfernen von Randleerzeichen ist Auslegung des Anfragekoerpers und gehoert an die
 * API-Grenze. Ein danach leerer Name wird zu {@code null} und damit zum Fall "ohne Namen" -
 * ein Gast namens " " stuende sonst als Leerstelle im Team.
 *
 * @param stufe Selbsteinschaetzung, hier vom Admin gesetzt
 * @param name  Anzeigename oder {@code null}
 */
public record GastAuswahl(
        @NotNull(message = "Die Stufe des Gastes fehlt.")
        GastStufe stufe,

        @Size(max = 40, message = "Der Gastname darf höchstens 40 Zeichen lang sein.")
        String name) {

    /** Entfernt Randleerzeichen; ein danach leerer Name zaehlt wie ein fehlender. */
    public GastAuswahl {
        if (name != null) {
            String bereinigt = name.trim();
            name = bereinigt.isEmpty() ? null : bereinigt;
        }
    }

    /** Bildet auf den Domaentyp ab, den der {@code AufstellungService} entgegennimmt. */
    public Gastauswahl nachDomaene() {
        return new Gastauswahl(stufe, name);
    }
}
