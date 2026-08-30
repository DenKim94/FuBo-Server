package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.TerminEintrag;
import de.fubo.appserver.domain.spieltag.TerminMitTeilnehmern;
import de.fubo.appserver.domain.spieltag.TerminStatus;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Antwortobjekt der Einzelansicht {@code GET /api/v1/termine/{terminId}/lesen}
 * (S4, Abschnitt 2.1).
 *
 * <p>Dieselben Felder wie {@link TerminUebersicht}, dazu die {@code version}.
 *
 * <h2>Warum die Version nach aussen geht</h2>
 * {@code /admin/termin/aendern} verlangt sie zurueck. Ohne diesen Wert ueberschriebe der
 * zuletzt gespeicherte Browser-Tab die Aenderung des anderen lautlos. Der Wert ist kein
 * Geheimnis: Er zaehlt Schreibvorgaenge und verraet nichts ueber ihren Inhalt - dieselbe
 * Abwaegung wie bei {@code Konfiguration}.
 *
 * <h2>Die Teilnehmerliste kam mit Paket 7 dazu (30.08.2026)</h2>
 * Als zusaetzliches Feld dieser Antwort und <b>nicht</b> als eigener Endpunkt: Wer einen
 * Termin oeffnet, will die Teilnehmer sehen, und zwei Aufrufe fuer eine Ansicht sind zwei
 * Gelegenheiten fuer einen inkonsistenten Stand. Der Zusatz war additiv und damit nicht
 * brechend - der Client-Track musste nichts zuruecknehmen.
 *
 * @param terminId           technischer Schluessel
 * @param serieId            Serie oder {@code null} bei einem Einzeltermin
 * @param datum              Datum in Ortszeit
 * @param uhrzeit            Uhrzeit in Ortszeit
 * @param ort                Spielort oder {@code null}
 * @param status             Zustand des Termins
 * @param teilnehmerVersion  Zaehler der Teilnehmeraenderungen (A15)
 * @param zusagen            Anzahl der Zusagen
 * @param eigeneRueckmeldung dreiwertig, siehe {@link TerminUebersicht}
 * @param version            Stand der Zeile fuer das Optimistic Locking
 * @param teilnehmerliste    die Zusagen in Warteschlangenreihenfolge samt der Grenzen aus
 *                           der Konfiguration
 */
public record TerminDetails(Long terminId,
                            Long serieId,
                            LocalDate datum,
                            LocalTime uhrzeit,
                            String ort,
                            TerminStatus status,
                            int teilnehmerVersion,
                            int zusagen,
                            Boolean eigeneRueckmeldung,
                            Long version,
                            Teilnehmerliste teilnehmerliste) {

    /** Bildet Termin und Teilnehmer auf den Vertrag ab. */
    public static TerminDetails von(TerminMitTeilnehmern gelesen) {
        TerminEintrag eintrag = gelesen.termin();
        return new TerminDetails(
                eintrag.id(),
                eintrag.serieId(),
                eintrag.datum(),
                eintrag.uhrzeit(),
                eintrag.ort(),
                eintrag.status(),
                eintrag.teilnehmerVersion(),
                eintrag.zusagen(),
                eintrag.eigeneRueckmeldung(),
                eintrag.version(),
                Teilnehmerliste.von(gelesen.teilnehmer()));
    }
}
