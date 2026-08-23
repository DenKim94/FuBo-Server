package de.fubo.appserver.dto.admin;

import jakarta.validation.constraints.NotNull;

/**
 * Anfragekoerper von {@code POST /api/v1/admin/user/blockieren} (S2b Abschnitt 8).
 *
 * <p><b>Ein Endpunkt fuer beide Richtungen</b>, weil es dieselbe Spalte ist. Ohne die
 * Gegenrichtung kaeme der Admin an ein versehentlich gesperrtes Profil bis S3 nicht mehr
 * heran.
 *
 * <p>{@code Boolean} statt {@code boolean}: Nur ein Wrapper-Typ laesst sich von
 * {@code @NotNull} pruefen. Beim primitiven Typ waere ein fehlendes Feld stillschweigend
 * {@code false} - der Aufruf gaebe das Profil frei, obwohl der Aufrufer es sperren wollte.
 *
 * @param spielerId  Id des betroffenen Profils
 * @param blockieren {@code true} sperrt, {@code false} gibt wieder frei
 */
public record SpielerBlockierenRequest(

        @NotNull(message = "Die Profil-Id fehlt.")
        Long spielerId,

        @NotNull(message = "Es fehlt die Angabe, ob gesperrt oder freigegeben werden soll.")
        Boolean blockieren) {
}
