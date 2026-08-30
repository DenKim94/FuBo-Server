package de.fubo.appserver.dto.admin;

import java.util.List;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/gast/freigeben} (A17, Vorgabe vom 30.08.2026).
 *
 * <h2>Genau eines der beiden Felder</h2>
 * Entweder {@code slotIds} oder {@code alle} - nie beide und nie keines. Ein leerer Koerper wird
 * mit {@code 400} abgelehnt, statt "alle" zu bedeuten: <b>Ein Sammelabbruch soll kein Versehen
 * sein koennen.</b> Dieselbe Haltung wie bei {@link SpielerBearbeitenRequest}, das einen Aufruf
 * ohne jede Angabe ebenfalls ablehnt, weil er nichts taete und trotzdem einen Protokolleintrag
 * hinterliesse - hier taete er im Gegenteil zu viel.
 *
 * <p><b>{@code alle: false} ist keine Angabe</b>, sondern ein Fehler. Der Wahrheitswert traegt
 * keine zweite Bedeutung; wer nichts freigeben will, ruft den Endpunkt nicht auf.
 *
 * <h2>Warum keine Bean Validation</h2>
 * "Genau eines von beiden" ist eine feldeuebergreifende Regel, und die kann Bean Validation
 * nicht. Sie liegt deshalb vollstaendig im Dienst - dort, wo auch die Pruefung gegen die
 * vorhandenen Platznummern stattfindet, die ohnehin erst zur Laufzeit feststehen. Zwei
 * Fehlerquellen an einer Stelle ergeben eine Meldung; verteilt ergaeben sie zwei mit
 * unterschiedlichem Wortlaut fuer denselben fachlichen Fall.
 *
 * @param slotIds Platznummern aus {@code /admin/gast/lesen}; {@code null}, wenn {@code alle} gilt
 * @param alle    {@code true} fuer alle belegten Plaetze; sonst {@code null}
 */
public record GastFreigebenRequest(List<Integer> slotIds, Boolean alle) {

    /** {@code true}, wenn der Sammelabbruch ausdruecklich verlangt wurde. */
    public boolean alleGewuenscht() {
        return Boolean.TRUE.equals(alle);
    }

    /** {@code true}, wenn mindestens eine Platznummer genannt ist. */
    public boolean nenntPlaetze() {
        return slotIds != null && !slotIds.isEmpty();
    }
}
