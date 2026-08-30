package de.fubo.appserver.dto.spieltag;

import jakarta.validation.constraints.NotNull;

/**
 * Anfragekoerper von {@code POST /api/v1/termine/rueckmeldung} (A7, A8; S4 Abschnitt 5.1).
 *
 * <h2>Ein Endpunkt fuer beide Richtungen</h2>
 * Wie {@code /admin/user/blockieren}, das aus demselben Grund {@code blockieren: true|false}
 * fuehrt. Zwei Endpunkte hiessen zwei Pfade fuer dieselbe Zeile, dieselbe Transaktion und
 * denselben Zaehler.
 *
 * <h2>Und einer fuer beide Rollen</h2>
 * <b>Ein Gast schickt keinen Namen mit - er ist schon jemand.</b> Der Dienst entscheidet an
 * der Sitzung, welche Spalte gefuellt wird. Der Name aus dem Anfragekoerper waere ein Weg,
 * unter beliebigen Namen zuzusagen und die Teilnehmerliste mit Phantomen zu fuellen;
 * dasselbe Prinzip, mit dem {@code spielerId} nie aus dem Koerper kommt.
 *
 * <p><b>{@code Boolean} statt {@code boolean}:</b> Ein fehlendes Feld ist bei einem Grundtyp
 * nicht von einem angegebenen {@code false} zu unterscheiden - der Aufruf traege dann eine
 * Absage ein, die niemand geschickt hat.
 *
 * @param terminId betroffener Termin
 * @param zusage   {@code true} Zusage, {@code false} Absage
 */
public record TeilnahmeRequest(

        @NotNull(message = "Die Termin-Id fehlt.")
        Long terminId,

        @NotNull(message = "Es fehlt die Angabe, ob zu- oder abgesagt wird.")
        Boolean zusage) {
}
