package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.team.Einteilungseintrag;

/**
 * Ein eingeteilter Teilnehmer an der API-Grenze (A12, S5 Abschnitt 9.2).
 *
 * <h2>Drei Felder, und der Test prueft die Liste vollstaendig</h2>
 * Keine Skillwerte, keine Scores, keine Gast-Stufe: Die Einteilung reist in der Einzelansicht
 * eines Termins mit und erreicht damit jede Rolle, auch {@code GAST}. <b>Geprueft wird die
 * vollstaendige Feldliste und nicht das Fehlen einzelner Namen</b> - ein spaeter ergaenztes
 * Feld faellt sonst niemandem auf.
 *
 * <h2>Derselbe Typ unter {@code /admin/}</h2>
 * {@code ManuelleEinteilung} verwendet ihn unveraendert. Ihn dort um Skillwerte zu erweitern
 * waere nach A12 erlaubt, hiesse aber, denselben Namen fuer zwei Formen zu benutzen; wird der
 * Bedarf konkret, bekommt er einen eigenen Typ.
 *
 * @param anzeigeName       Profilname oder Gastname
 * @param gast              {@code true} bei einem Gast
 * @param auswechselspieler {@code true} fuer genau einen Teilnehmer des groesseren Teams bei
 *                          ungerader Teilnehmerzahl (A20b)
 */
public record TeamEintrag(String anzeigeName, boolean gast, boolean auswechselspieler) {

    /** Bildet das Wertobjekt auf den Vertrag ab. */
    public static TeamEintrag von(Einteilungseintrag eintrag) {
        return new TeamEintrag(eintrag.anzeigeName(), eintrag.gast(), eintrag.auswechselspieler());
    }
}
