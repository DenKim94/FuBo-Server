package de.fubo.appserver.dto.push;

/**
 * Antwort von {@code POST /api/v1/admin/push/test} (S8 Abschnitt 10.2).
 *
 * <h2>Was der Probeversand beweist - und was nicht</h2>
 * Er geht an die <b>eigenen</b> aktiven Abonnements des Aufrufers und prueft die drei
 * Versandbedingungen <b>nicht</b> (Weggabelung D vom 14.09.2026): Der Admin ist hier Absender
 * und Empfaenger in einer Person und hat den Versand ausdruecklich angefordert. Ihn zu
 * zwingen, erst den Anlagenschalter einzuschalten, um zu pruefen, ob das Einschalten etwas
 * bringt, drehte die Reihenfolge um.
 *
 * <p><b>Der Preis gehoert in die Endpunktbeschreibung:</b> Ein erfolgreicher Probeversand
 * beweist <i>nicht</i>, dass Spieler etwas bekommen. Er beweist, dass VAPID-Schluessel,
 * Verschluesselung und der Weg zum Push-Dienst tragen. Deshalb zeigt die Oberflaeche
 * {@code anlageAktiv} aus {@code /push/status/lesen} daneben.
 *
 * <p><b>{@code empfaenger: 0} ist kein Fehler</b>, sondern die Auskunft "du hast auf diesem
 * Konto kein aktives Abonnement". Ein Fehlercode dafuer waere irrefuehrend: Der Aufruf hat
 * getan, was er sollte.
 *
 * @param empfaenger Zahl der angesprochenen Abonnements - <b>Geraete, nicht Personen</b>: Es
 *                   sind die des Aufrufers
 * @param zugestellt Zahl der Abonnements, deren Push-Dienst die Nachricht angenommen hat.
 *                   <b>Auch das ist keine Zustellbestaetigung</b> - Web Push kennt keine; ein
 *                   {@code 201} heisst "vom Dienst angenommen". Weicht die Zahl von
 *                   {@code empfaenger} ab, steht der Grund je Abonnement im
 *                   Anwendungsprotokoll
 */
public record Probeversand(int empfaenger, int zugestellt) {
}
