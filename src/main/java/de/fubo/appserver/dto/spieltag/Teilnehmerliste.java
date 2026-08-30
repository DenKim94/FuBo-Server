package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.Teilnehmeruebersicht;

import java.util.List;

/**
 * Die Teilnehmer eines Termins samt der Kennzahlen, an denen sie zu messen sind
 * (A10, A11; S4 Abschnitt 7.2).
 *
 * <p>Sie ist Teil von {@link TerminDetails} und hat bewusst <b>keinen eigenen Endpunkt</b>:
 * Wer einen Termin oeffnet, will die Teilnehmer sehen, und zwei Aufrufe fuer eine Ansicht
 * sind zwei Gelegenheiten fuer einen inkonsistenten Stand.
 *
 * <p><b>Ohne {@code terminId} und ohne {@code zusagen}</b>, anders als in Abschnitt 7.2 der
 * Anleitung skizziert: Beide stehen bereits im umgebenden {@link TerminDetails}. Zweimal
 * dieselbe Zahl in einer Antwort ist eine Einladung, die falsche zu lesen - und eine
 * Gelegenheit, dass sie auseinanderlaufen.
 *
 * @param minTeilnehmer       Mindestteilnehmerzahl (A10)
 * @param maxTeilnehmer       Hoechstzahl; ab Position {@code maxTeilnehmer + 1} wird gewartet (A11)
 * @param mindestzahlErreicht beantwortet A10 unmittelbar. Gezaehlt werden <b>alle</b> Zusagen,
 *                            auch die wartenden - wer wartet, hat zugesagt und rueckt nach.
 *                            Die Farbe des Balkens gehoert nicht in den Vertrag; der Server
 *                            liefert die Tatsache
 * @param teilnehmer          die Zusagen in Warteschlangenreihenfolge. <b>Im Frontend nicht
 *                            umsortieren</b> - sonst stimmt {@code position} nicht mehr mit
 *                            der Anzeige ueberein
 */
public record Teilnehmerliste(short minTeilnehmer,
                              short maxTeilnehmer,
                              boolean mindestzahlErreicht,
                              List<TeilnehmerEintrag> teilnehmer) {

    /** Bildet die Uebersicht des Dienstes auf den Vertrag ab. */
    public static Teilnehmerliste von(Teilnehmeruebersicht uebersicht) {
        return new Teilnehmerliste(
                uebersicht.minTeilnehmer(),
                uebersicht.maxTeilnehmer(),
                uebersicht.mindestzahlErreicht(),
                uebersicht.teilnehmer().stream()
                        .map(eintrag -> TeilnehmerEintrag.von(eintrag, uebersicht))
                        .toList());
    }
}
