package de.fubo.appserver.domain.spieltag;

import java.util.List;

/**
 * Die vom Admin benannte Teilnehmermenge eines manuellen Generierungslaufs (A24, S5 2.5).
 *
 * <h2>Warum ein Domaentyp und nicht das DTO des Endpunkts</h2>
 * Der {@code AufstellungService} liegt zwei Schichten unter der API-Grenze; ein DTO dort
 * hiesse, dass der Dienst den Vertrag kennt. Der Endpunkt aus Abschnitt 9.4 bildet seinen
 * Anfragekoerper auf diesen Typ ab - dort geschieht auch das Entfernen von Randleerzeichen,
 * das an die API-Grenze gehoert.
 *
 * <h2>Kein {@code algorithmType}</h2>
 * Das Verfahren entscheidet {@code configs.app_config}, wie beim Termin-Lauf. Ein Feld hier
 * waere eine zweite Stelle, an der das Verfahren gewaehlt wird - und die erste, an der beide
 * Laeufe verschieden funktionieren, ohne dass es jemand angeordnet haette.
 *
 * @param spielerIds Ids vorhandener Spielerprofile; darf leer sein, solange Gaeste genannt sind.
 *                   <b>Genannte, aber ungueltige Ids werden abgelehnt, nicht still gefiltert</b>
 *                   (2.5): Am Termin ergibt sich die Menge, hier hat der Admin jeden Einzelnen
 *                   benannt
 * @param gaeste     frei angelegte Gaeste mit Stufe; darf leer sein, solange Spieler genannt sind
 */
public record ManuelleAuswahl(List<Long> spielerIds, List<Gastauswahl> gaeste) {

    /** Beide Listen nie {@code null} - das erspart jedem Leser eine Fallunterscheidung. */
    public ManuelleAuswahl {
        spielerIds = spielerIds == null ? List.of() : List.copyOf(spielerIds);
        gaeste = gaeste == null ? List.of() : List.copyOf(gaeste);
    }

    /** Zahl der genannten Teilnehmer, Spieler und Gaeste zusammen. */
    public int anzahl() {
        return spielerIds.size() + gaeste.size();
    }
}
