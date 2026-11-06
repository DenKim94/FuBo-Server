package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.Absageeintrag;

/**
 * Eine Absage in der Teilnehmerliste (Ergaenzung vom 06.11.2026).
 *
 * <p><b>Ein eigener Typ und nicht {@link TeilnehmerEintrag}.</b> {@code position} und
 * {@code wartet} haben bei einer Absage keine Bedeutung; mit {@code null} oder {@code false}
 * gefuellt, laesen sie sich wie eine Aussage ueber die Warteschlange. Gemeinsam sind nur
 * Name und Gast-Kennzeichen - und genau die traegt dieser Typ.
 *
 * <p><b>Keine Bewertung.</b> Die Liste erreicht jede Rolle, auch {@code GAST} (A12); anders
 * als bei den Zusagen auch fuer den Admin ohne Gast-Stufe.
 *
 * @param anzeigeName Profilname beziehungsweise Gastname. Das Anhaengen von "(Gast)" ist
 *                    Sache des Frontends (A8)
 * @param gast        {@code true}, wenn hinter dem Eintrag kein Profil steht
 */
public record AbsageEintrag(String anzeigeName, boolean gast) {

    /** Bildet den Eintrag des Repositorys auf den Vertrag ab. */
    public static AbsageEintrag von(Absageeintrag eintrag) {
        return new AbsageEintrag(eintrag.anzeigeName(), eintrag.gast());
    }
}
