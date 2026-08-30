package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.Teilnehmereintrag;
import de.fubo.appserver.domain.spieltag.Teilnehmeruebersicht;

/**
 * Ein Eintrag der Teilnehmerliste (S4, Abschnitt 7.2).
 *
 * <p><b>Eine Liste mit Kennzeichen, nicht zwei getrennte Listen</b> (Weggabelung D). Die
 * Reihenfolge ist die <i>eine</i> Information, die beide Gruppen verbindet: Wer nachrueckt,
 * steht schon fest. Zwei Arrays zwaengen den Client, sie fuer die Anzeige wieder
 * zusammenzufuegen, und beim Nachruecken wanderte ein Eintrag zwischen ihnen hin und her.
 *
 * <p><b>Keine Skillwerte und keine Gast-Stufe.</b> Die Liste erreicht jede Rolle, auch
 * {@code GAST} - A12 verlangt, dass Bewertungen den Server dorthin nicht verlassen. Damit
 * sieht die Antwort fuer alle Rollen gleich aus; eine rollenabhaengige Liste waere der
 * teurere Weg, weil sie an jeder Stelle mitgedacht werden muesste.
 *
 * @param position    Rang in der Meldereihenfolge, beginnend bei 1
 * @param anzeigeName Profilname beziehungsweise Gastname. Das Anhaengen von "(Gast)" ist
 *                    Sache des Frontends (A8); {@code gast} sagt ihm, wann
 * @param gast        {@code true}, wenn hinter dem Eintrag kein Profil steht
 * @param wartet      {@code true}, sobald die Position ueber {@code maxTeilnehmer} liegt
 */
public record TeilnehmerEintrag(int position, String anzeigeName, boolean gast, boolean wartet) {

    /**
     * Bildet einen Eintrag ab und entscheidet dabei, ob er wartet.
     *
     * <p>Die Entscheidung faellt hier und nicht in der Abfrage: {@code maxTeilnehmer} ist
     * Konfiguration und kann sich aendern, ohne dass jemand zusagt oder absagt. Sie in die
     * Datenbank zu verlagern hiesse, die Grenze in jede Abfrage zu reichen.
     */
    public static TeilnehmerEintrag von(Teilnehmereintrag eintrag, Teilnehmeruebersicht uebersicht) {
        return new TeilnehmerEintrag(
                eintrag.position(),
                eintrag.anzeigeName(),
                eintrag.istGast(),
                uebersicht.wartet(eintrag));
    }
}
