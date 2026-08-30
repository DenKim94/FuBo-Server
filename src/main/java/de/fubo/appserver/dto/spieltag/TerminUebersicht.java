package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.TerminEintrag;
import de.fubo.appserver.domain.spieltag.TerminStatus;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Antwortobjekt der Terminliste {@code GET /api/v1/termine/lesen} (S4, Abschnitt 2).
 *
 * <p>Sie ist der Einstieg beider Dashboards aus A9 und liefert je Termin genug fuer eine
 * Kachel, ohne dass der Client einen zweiten Aufruf braucht: Stammdaten, Zahl der Zusagen
 * und die eigene Rueckmeldung.
 *
 * <p><b>Keine Skillwerte und keine Teilnehmernamen.</b> Der Endpunkt liegt ausserhalb von
 * {@code /admin/}, und A12 verlangt, dass Bewertungen den Server dorthin nicht verlassen.
 * Die Teilnehmerliste kommt mit S4-Paket 7 an die Einzelansicht, nicht an die Liste.
 *
 * @param terminId           technischer Schluessel
 * @param serieId            Serie oder {@code null} bei einem Einzeltermin
 * @param datum              Datum in Ortszeit
 * @param uhrzeit            Uhrzeit in Ortszeit
 * @param ort                Spielort oder {@code null}
 * @param status             Zustand des Termins; abgesagte bleiben in der Liste
 * @param teilnehmerVersion  Zaehler der Teilnehmeraenderungen (A15); in S4 nur gefuehrt
 * @param zusagen            Anzahl der Zusagen; Absagen zaehlen nicht mit
 * @param eigeneRueckmeldung <b>dreiwertig:</b> {@code true} zugesagt, {@code false} abgesagt,
 *                           {@code null} noch nicht gemeldet. Ein {@code if} ueber diesem Feld
 *                           behandelt den dritten Fall wie den zweiten - genau davor warnt
 *                           die Vertragsbeschreibung
 */
public record TerminUebersicht(Long terminId,
                               Long serieId,
                               LocalDate datum,
                               LocalTime uhrzeit,
                               String ort,
                               TerminStatus status,
                               int teilnehmerVersion,
                               int zusagen,
                               Boolean eigeneRueckmeldung) {

    /**
     * Bildet eine Ergebniszeile der Abfrage auf den Vertrag ab.
     *
     * <p>Die Abbildung steht im DTO und nicht im Dienst: Der Dienst soll nicht wissen
     * muessen, wie der Vertrag aussieht (Regel aus {@code AGENT_SERVER.md}).
     *
     * <p><b>{@code version} bleibt hier draussen</b>, obwohl der Record sie traegt. Wer
     * aendern will, oeffnet vorher die Einzelansicht - so wie bei der Konfiguration, wo
     * {@code lesen} dem {@code aendern} vorausgeht. Ein Zaehler in jeder Listenzeile lud
     * dazu ein, aus der Liste heraus zu schreiben, ohne den aktuellen Stand gesehen zu haben.
     */
    public static TerminUebersicht von(TerminEintrag eintrag) {
        return new TerminUebersicht(
                eintrag.id(),
                eintrag.serieId(),
                eintrag.datum(),
                eintrag.uhrzeit(),
                eintrag.ort(),
                eintrag.status(),
                eintrag.teilnehmerVersion(),
                eintrag.zusagen(),
                eintrag.eigeneRueckmeldung());
    }
}
