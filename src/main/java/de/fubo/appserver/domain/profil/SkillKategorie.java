package de.fubo.appserver.domain.profil;

import java.math.BigDecimal;

/**
 * Eine Skillkategorie samt ihrem Wertebereich - schlankes Wertobjekt aus
 * {@code profil.skill_kategorie} (S2b Abschnitt 8, erweitert in S3 Abschnitt 4).
 *
 * <p><b>Warum die Kategorien aus der Datenbank kommen und nicht aus einem Aufzaehlungstyp:</b>
 * {@code profil.skill_kategorie} ist datengetrieben - Kategorien lassen sich hinzufuegen,
 * abschalten und in ihrem Wertebereich verstellen, ohne die Anwendung neu zu bauen. Eine fest
 * verdrahtete Liste liefe frueher oder spaeter auseinander. Genau deshalb kennt auch der
 * Torwart-Bereich (0 bis 3) keinen Sonderfall im Code.
 *
 * <p>Wie alle Typen in {@code domain} ueberschreitet auch dieser die API-Grenze nie; nach
 * aussen geht {@code dto.admin.SkillKategorieInfo}.
 *
 * <h2>Erweiterung in S3</h2>
 * Bis S2b trug der Record nur {@code schluessel}, {@code minWert} und {@code maxWert} - mehr
 * brauchte die Pruefung beim Anlegen nicht. S3 liefert die Kategorien an das Frontend aus, und
 * dafuer fehlten drei Angaben: {@code bezeichnung} als Beschriftung, {@code reihenfolge} fuer
 * die Anzeige und {@code gewicht} zur Erklaerung, warum Torwart anders zaehlt.
 *
 * <p><b>Bestehende Aufrufer bleiben unberuehrt.</b> {@code SpielerVerwaltungService#pruefeSkills}
 * liest weiterhin nur {@code schluessel()}, {@code minWert()}, {@code maxWert()} und
 * {@link #enthaelt(int)} - zusaetzliche Komponenten eines Records stoeren keinen Leser.
 *
 * @param schluessel  fachlicher Schluessel, etwa {@code ANGRIFF}
 * @param bezeichnung Beschriftung in deutscher Schreibweise, fuer das Adminformular
 * @param gewicht     Gewicht in der Zielfunktion des Teamgenerators: 1.00 fuer die vier
 *                    Feldkategorien, 0.30 fuer {@code TORWART}
 * @param reihenfolge Anzeigereihenfolge; ueber {@code uq_skill_kategorie_reihenfolge} eindeutig
 * @param minWert     kleinster zulaessiger Wert
 * @param maxWert     groesster zulaessiger Wert; fuer {@code TORWART} 3, sonst 6
 */
public record SkillKategorie(String schluessel, String bezeichnung, BigDecimal gewicht,
                             int reihenfolge, int minWert, int maxWert) {

    /** Meldet, ob ein Wert in den Bereich dieser Kategorie passt. */
    public boolean enthaelt(int wert) {
        return wert >= minWert && wert <= maxWert;
    }
}
