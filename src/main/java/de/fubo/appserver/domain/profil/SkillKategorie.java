package de.fubo.appserver.domain.profil;

/**
 * Eine Skillkategorie samt ihrem Wertebereich - schlankes Wertobjekt aus
 * {@code profil.skill_kategorie} (S2b, Abschnitt 8).
 *
 * <p><b>Warum die Kategorien aus der Datenbank kommen und nicht aus einem Aufzaehlungstyp:</b>
 * {@code profil.skill_kategorie} ist datengetrieben - Kategorien lassen sich hinzufuegen,
 * abschalten und in ihrem Wertebereich verstellen, ohne die Anwendung neu zu bauen. Eine fest
 * verdrahtete Liste liefe frueher oder spaeter auseinander. Genau deshalb kennt auch der
 * Torwart-Bereich (0 bis 3) keinen Sonderfall im Code.
 *
 * <p>Wie alle Typen in {@code domain} ueberschreitet auch dieser die API-Grenze nie.
 *
 * @param schluessel fachlicher Schluessel, etwa {@code ANGRIFF}
 * @param minWert    kleinster zulaessiger Wert
 * @param maxWert    groesster zulaessiger Wert; fuer {@code TORWART} 3, sonst 6
 */
public record SkillKategorie(String schluessel, int minWert, int maxWert) {

    /** Meldet, ob ein Wert in den Bereich dieser Kategorie passt. */
    public boolean enthaelt(int wert) {
        return wert >= minWert && wert <= maxWert;
    }
}
