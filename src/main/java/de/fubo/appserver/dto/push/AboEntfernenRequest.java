package de.fubo.appserver.dto.push;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Anfragekoerper von {@code POST /api/v1/push/abo/entfernen} (A25c).
 *
 * <h2>Nur die Adresse, und nur die des aufrufenden Geraets</h2>
 * Der Widerruf betrifft <b>ein</b> Abonnement. Der Dienst nimmt die Spieler-Id aus der Sitzung
 * hinzu; ohne diese zweite Bedingung entfernte ein Aufrufer mit einer fremden Adresse das
 * Abonnement eines anderen - der Endpunkt liegt ausserhalb von {@code /admin/} und steht jedem
 * Angemeldeten offen.
 *
 * <p><b>Abschalten und Widerrufen sind zwei Handlungen.</b> Dieser Aufruf betrifft nur das
 * aufrufende Geraet; {@code /push/einstellung/aendern} gilt fuer alle Geraete des Spielers und
 * loescht keine Abonnements - sonst verlangte das Wiedereinschalten einen neuen
 * Browserdialog. Die Oberflaeche bietet beides an und benennt den Unterschied.
 *
 * <p><b>Ein unbekanntes Abonnement ist {@code 200}</b>, nicht {@code 404}: Loeschen ist
 * idempotent, und eine Fallunterscheidung im Client haette keinen Nutzen.
 *
 * @param endpoint Adresse des Abonnements, wie der Browser sie liefert
 */
public record AboEntfernenRequest(
        @NotBlank(message = "Die Endpoint-Adresse darf nicht leer sein.")
        @Size(max = 2048, message = "Die Endpoint-Adresse ist zu lang.")
        @Pattern(regexp = "^https://.+", message = "Die Endpoint-Adresse muss mit https:// beginnen.")
        String endpoint) {

    /** Entfernt Randleerzeichen; Begruendung bei {@code AboAnlegenRequest}. */
    public String bereinigterEndpoint() {
        return endpoint == null ? null : endpoint.trim();
    }
}
