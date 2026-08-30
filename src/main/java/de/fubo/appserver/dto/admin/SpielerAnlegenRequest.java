package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/user/anlegen} (A13, S2b Abschnitt 8).
 *
 * <h2>Die Skillwerte sind Pflicht und muessen vollstaendig sein</h2>
 * Seit dem 30.08.2026 (Vorgabe des Haupt-Entwicklers) verlangt der Endpunkt einen Wert je
 * <b>aktiver</b> Kategorie. Bis dahin waren sie optional, und nicht genannte Kategorien bekamen
 * die Vorgabe der Stufe {@code MITTEL}.
 *
 * <p><b>Der Grund fuer die Umkehr:</b> Eine Vorgabe ist eine Behauptung ueber einen Spieler, die
 * niemand aufgestellt hat. Sie fiel im Betrieb nicht auf, ging aber unveraendert in die
 * Teameinteilung ein - und dem Ergebnis sah niemand an, dass die Grundlage geraten war. Lieber
 * eine Ablehnung beim Anlegen als ein Profil, dessen Staerke erfunden ist.
 *
 * <p><b>{@code /admin/user/bearbeiten} bleibt davon unberuehrt:</b> Dort ist eine Teilmenge
 * weiterhin erlaubt. Kein Widerspruch - ein bestehendes Profil hat bereits vollstaendige Werte,
 * und einen einzelnen davon zu korrigieren soll nicht bedeuten, alle fuenf erneut zu senden.
 *
 * @param name   Anzeigename des neuen Profils
 * @param skills Skillwerte je Kategorieschluessel; Pflicht, mit einem Wert je aktiver Kategorie
 */
public record SpielerAnlegenRequest(

        @NotBlank(message = "Der Name darf nicht leer sein.")
        @Size(max = 60, message = "Der Name darf hoechstens 60 Zeichen lang sein.")
        String name,

        /*
         * Bewusst eine offene Karte und kein Record je Kategorie: Die Kategorien stehen
         * datengetrieben in profil.skill_kategorie und koennen sich aendern. Ein festes
         * Feldgeruest hier muesste bei jeder neuen Kategorie nachgezogen werden - und der
         * Vertrag behauptete eine Vollstaendigkeit, die die Datenbank nicht garantiert.
         *
         * Die Pruefung gegen Schluessel und Wertebereich uebernimmt der Service; Bean
         * Validation kann sie nicht leisten, weil die zulaessigen Werte erst zur Laufzeit
         * feststehen.
         */
        Map<String, Integer> skills) {

    /**
     * Der Name ohne Randleerzeichen.
     *
     * <p>Die Auslegung des Anfragekoerpers gehoert an die API-Grenze und damit hierher, nicht
     * in die Fachlogik - dieselbe Regel wie bei {@code GastAnmeldungRequest}. Ohne das
     * Trimmen waeren " Max" und "Max" zwei verschiedene Namen, und die Belegtpruefung liefe
     * ins Leere.
     */
    public String nameGetrimmt() {
        return name == null ? null : name.trim();
    }

    /**
     * Die Karte, wie sie ankam - auch {@code null}.
     *
     * <p><b>Bewusst ohne Ersatz durch eine leere Karte.</b> Bis zum 30.08.2026 stand hier
     * {@code skillsOderLeer()}, weil ein fehlendes Feld gleichbedeutend mit "keine Angabe" war.
     * Seit die Werte Pflicht sind, ist es das Gegenteil: ein fehlendes Feld ist ein Fehler. Der
     * Dienst prueft {@code null}, leere Karte und Teilmenge in derselben Zusicherung und
     * beantwortet alle drei mit derselben Meldung - eine Unterscheidung hier verschoebe nur die
     * Stelle, an der sie wieder zusammengefuehrt werden muss.
     */
    public Map<String, Integer> skillsOderNull() {
        return skills;
    }
}
