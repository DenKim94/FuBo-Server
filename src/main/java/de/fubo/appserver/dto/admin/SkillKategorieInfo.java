package de.fubo.appserver.dto.admin;

import de.fubo.appserver.domain.profil.SkillKategorie;

import java.math.BigDecimal;

/**
 * Antwortobjekt von {@code GET /api/v1/admin/skills/lesen} (S3, Abschnitt 4).
 *
 * <h2>Wofuer das Frontend die Kategorien braucht</h2>
 * Das Datenmodell fuehrt sie bewusst als <b>Daten</b> und nicht als Spalten oder
 * Aufzaehlungstyp - eine neue Kategorie ist damit eine Datenaenderung statt einer
 * Schemamigration. Genau dieser Vorteil verfaellt, wenn der Client die fuenf Kategorien fest
 * verdrahtet: Dann ist eine neue Kategorie eben doch ein Frontend-Release.
 *
 * <p>Das Bearbeitungsformular braucht vier Angaben, die es nirgends sonst bekommt: den
 * {@code schluessel} als Feldnamen im Anfragekoerper von {@code bearbeiten}, die
 * {@code bezeichnung} als Beschriftung, {@code minWert}/{@code maxWert} als Grenzen des
 * Eingabefelds und die {@code reihenfolge} fuer die Anzeige.
 *
 * <h2>Warum das Gewicht mitgeliefert wird</h2>
 * Das Formular braucht es nicht. Es erklaert dem Admin aber, warum Torwart anders zaehlt
 * (0.30 gegen 1.00), und ist die einzige Stelle, an der diese Zahl je sichtbar wird. Sie ist
 * kein Geheimnis - anders als die Skillwerte einzelner Spieler, die sie gewichtet.
 *
 * <p><b>Getrennt vom Wertobjekt {@link SkillKategorie}</b>, obwohl beide heute dieselben
 * Komponenten tragen. Dieselbe Begruendung wie bei {@code NamensEintrag} und
 * {@code NameOption}: Der eine bildet das Abfrageergebnis ab, der andere den Vertrag zum
 * Frontend. Sobald die Abfrage ein Feld mehr liefert, das nicht nach aussen darf, ist die
 * Trennung genau das, was den Fehler verhindert.
 *
 * @param schluessel  fachlicher Schluessel, etwa {@code ANGRIFF}
 * @param bezeichnung Beschriftung fuer das Formular
 * @param gewicht     Gewicht in der Zielfunktion: 1.00, fuer {@code TORWART} 0.30
 * @param reihenfolge Anzeigereihenfolge; die Antwort ist bereits danach sortiert
 * @param minWert     kleinster zulaessiger Wert
 * @param maxWert     groesster zulaessiger Wert; fuer {@code TORWART} 3, sonst 6
 */
public record SkillKategorieInfo(String schluessel, String bezeichnung, BigDecimal gewicht,
                                 int reihenfolge, int minWert, int maxWert) {

    /**
     * Uebersetzt das Wertobjekt der Abfrage in das Antwortobjekt der API-Grenze.
     *
     * <p>Die Abbildung steht hier und nicht im Service: Sie gehoert zum DTO, und der Service
     * soll nicht wissen muessen, wie der Vertrag aussieht.
     */
    public static SkillKategorieInfo von(SkillKategorie kategorie) {
        return new SkillKategorieInfo(
                kategorie.schluessel(),
                kategorie.bezeichnung(),
                kategorie.gewicht(),
                kategorie.reihenfolge(),
                kategorie.minWert(),
                kategorie.maxWert());
    }
}
