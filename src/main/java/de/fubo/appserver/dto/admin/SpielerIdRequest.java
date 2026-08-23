package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotNull;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/user/entfernen} (S2b Abschnitt 8).
 *
 * <p>Die Id steht im Koerper und nicht im Pfad, weil das Aktionssegment am Ende des Pfades
 * steht ({@code /user/entfernen}) - die Versionierung des Projekts setzt darauf auf. Ein
 * Pfad wie {@code /user/{id}/entfernen} waere ebenfalls moeglich, verteilte die Eingabe aber
 * auf zwei Stellen.
 *
 * @param spielerId Id des zu entfernenden Profils
 */
public record SpielerIdRequest(

        @NotNull(message = "Die Profil-Id fehlt.")
        Long spielerId) {
}
