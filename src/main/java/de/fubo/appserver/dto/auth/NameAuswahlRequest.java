package de.fubo.appserver.dto.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Anfragekoerper von {@code POST /api/auth/user} (Namensauswahl, zweite Login-Stufe).
 *
 * <p>Uebertragen wird die Id, nicht der Name: Der Name ist zwar eindeutig
 * ({@code uq_spieler_name}), aendert sich aber ueber das Admin-CRUD (S3). Eine Auswahl
 * ueber die Id bleibt davon unberuehrt.
 *
 * @param spielerId Id des gewaehlten Profils aus der Namensliste
 */
public record NameAuswahlRequest(
        @NotNull(message = "Es muss ein Profil gewaehlt werden.")
        @Positive(message = "Die Profil-Id muss positiv sein.")
        Long spielerId) {
}
