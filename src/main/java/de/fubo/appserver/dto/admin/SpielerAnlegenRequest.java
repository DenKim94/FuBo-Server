package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/user/anlegen} (A13, S2b Abschnitt 8).
 *
 * @param name   Anzeigename des neuen Profils
 * @param skills Skillwerte je Kategorieschluessel; darf fehlen oder unvollstaendig sein
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

    /** Nie {@code null} - ein fehlendes Feld ist gleichbedeutend mit "keine Angabe". */
    public Map<String, Integer> skillsOderLeer() {
        return skills == null ? Map.of() : skills;
    }
}
