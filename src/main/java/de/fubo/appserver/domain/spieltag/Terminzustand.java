package de.fubo.appserver.domain.spieltag;

/**
 * Der Zustand eines Termins, soweit die Teamgenerierung ihn braucht (S5 Abschnitte 2.4, 7.1).
 *
 * <h2>Warum nicht die {@link Termin}-Entity</h2>
 * Der Generierungslauf liest {@code teilnehmer_version} zu Beginn und prueft sie am Ende
 * gegen (6.3). <b>Eine geladene Entity lieferte den Stand aus dem Persistence-Context</b> -
 * und der ist genau dann veraltet, wenn zwischendurch ein natives {@code UPDATE} den Zaehler
 * erhoeht hat, also bei jeder Zu- und Absage. Dieselbe Ueberlegung, aus der der
 * Rueckmeldepfad in S4 den Termin nativ liest statt ueber {@code findById}.
 *
 * <p><b>Und es haelt die Entity aus dem Vorgang heraus.</b> Waere sie geladen, braechte der
 * naechste Flush einen Sperrkonflikt, sobald irgendwo ein natives {@code UPDATE} auf
 * {@code termin.version} dazwischenliegt - ein Konflikt, den niemand verursacht hat.
 *
 * @param status            Zustand des Termins; generiert wird nur bei {@link TerminStatus#GEPLANT}
 * @param teamsFixiert      {@code true} ab Terminbeginn (A18, 10.2); dann {@code 409 TEAMS_FIXIERT}
 * @param teilnehmerVersion Stand des Teilnehmerkreises (A15); Schluesselteil des Kontingents
 */
public record Terminzustand(TerminStatus status, boolean teamsFixiert, int teilnehmerVersion) {
}
