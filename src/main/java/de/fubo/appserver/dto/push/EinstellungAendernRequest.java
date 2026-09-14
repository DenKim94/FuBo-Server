package de.fubo.appserver.dto.push;

import jakarta.validation.constraints.NotNull;

/**
 * Anfragekoerper von {@code POST /api/v1/push/einstellung/aendern} (A25f).
 *
 * <h2>{@code Boolean} mit {@code @NotNull}, nie {@code boolean}</h2>
 * Ein primitiver Wahrheitswert waere bei fehlendem Feld stillschweigend {@code false} - ein
 * Client, der das Feld falsch benennt, schaltete Push also ab und bekaeme {@code 200}.
 * <b>Dieselbe Regel und derselbe Grund wie bei {@code hallenModusAktiv} und
 * {@code pushAktiv}</b> im Voll-Update der Konfiguration: Bei Zahlenfeldern faengt
 * {@code @Min} ein fehlendes Feld ab, weil eine fehlende Zahl als {@code 0} ankommt - fuer
 * einen Wahrheitswert gibt es diese Untergrenze nicht.
 *
 * <h2>Eine Spieler-Id gibt es nicht und wird es nicht geben</h2>
 * Der Schalter ist <b>nicht ueberschreibbar</b> (A25f): Die Id kommt aus der Sitzung, und es
 * gibt bewusst keinen Admin-Endpunkt dafuer. Er wirkt auf <b>beide</b> Versandanlaesse; eine
 * Aufteilung nach Anlass gibt es nicht - wer abschaltet, erfaehrt auch eine Terminabsage erst
 * beim Oeffnen der Anwendung. Darauf ist beim Abschalten einmal hinzuweisen.
 *
 * @param pushErwuenscht ob dieser Spieler Benachrichtigungen empfangen will
 */
public record EinstellungAendernRequest(
        @NotNull(message = "Das Feld pushErwuenscht ist erforderlich.")
        Boolean pushErwuenscht) {
}
