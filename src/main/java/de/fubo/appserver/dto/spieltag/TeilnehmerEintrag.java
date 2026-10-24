package de.fubo.appserver.dto.spieltag;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.fubo.appserver.domain.auth.GastStufe;
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
 * <p><b>Keine Skillwerte, und die Gast-Stufe nur fuer den Admin.</b> Die Liste erreicht jede
 * Rolle, auch {@code GAST} - A12 verlangt, dass Bewertungen den Server dorthin nicht
 * verlassen. Bis zum 24.10.2026 sah die Antwort deshalb fuer alle Rollen gleich aus; eine
 * rollenabhaengige Liste ist der teurere Weg, weil sie an jeder Stelle mitgedacht werden
 * muss. <b>Der Preis wurde bewusst bezahlt</b> (Entscheidung des Entwicklers vom 24.10.2026):
 * Nach dem Abmelden eines Gastes war seine Stufe fuer den Admin unsichtbar, obwohl sie in der
 * Teilnahme steht. Die Stelle ist <b>eine</b>: {@code TerminService#einzelnLesen} entscheidet
 * anhand der Rolle, ob die Abfrage die Stufe liest; dieses DTO reicht nur durch, was
 * ankommt. Bei {@code USER} und {@code GAST} fehlt {@code gastStufe} im JSON, der Schluessel
 * ist nicht einmal mit dem Wert {@code null} vorhanden.
 *
 * @param position    Rang in der Meldereihenfolge, beginnend bei 1
 * @param anzeigeName Profilname beziehungsweise Gastname. Das Anhaengen von "(Gast)" ist
 *                    Sache des Frontends (A8); {@code gast} sagt ihm, wann
 * @param gast        {@code true}, wenn hinter dem Eintrag kein Profil steht
 * @param wartet      {@code true}, sobald die Position ueber {@code maxTeilnehmer} liegt
 * @param gastStufe   die Stufe, mit der der Teamgenerator den Gast rechnet - nach einer
 *                    Korrektur durch {@code gast-stufe} die korrigierte. <b>Nur in der
 *                    Antwort an eine Adminsitzung und nur bei {@code gast = true}</b>; sonst
 *                    fehlt das Feld. Der Wert gilt dem Teilnahmestand, nicht dem einer
 *                    Sitzung: Er bleibt sichtbar, wenn der Gast laengst abgemeldet ist
 */
public record TeilnehmerEintrag(int position,
                                String anzeigeName,
                                boolean gast,
                                boolean wartet,
                                @JsonInclude(JsonInclude.Include.NON_NULL) GastStufe gastStufe) {

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
                uebersicht.wartet(eintrag),
                eintrag.gastStufe());
    }
}
