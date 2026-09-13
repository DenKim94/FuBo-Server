package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.TerminEintrag;
import de.fubo.appserver.domain.spieltag.TerminMitTeilnehmern;
import de.fubo.appserver.domain.spieltag.TerminStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

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
 * <h2>Die Teameinteilung kam mit S5 dazu (Weggabelung B)</h2>
 * Nach derselben Ueberlegung wie die Teilnehmerliste und wieder <b>additiv</b>: Ein neues,
 * nullbares Feld bricht keinen bestehenden Client. Ein eigener Endpunkt
 * {@code GET /teams/{terminId}/lesen} bleibt vertretbar, sobald die Einteilung haeufiger
 * einzeln nachgeladen wird als der Termin - heute waeren es zwei Aufrufe fuer eine Ansicht.
 *
 * <h2>Das Ergebnis kam mit S6 dazu (Weggabelung B)</h2>
 * Das vierte additive Feld nach demselben Muster, und es traegt zugleich die {@code version},
 * die {@code /admin/ergebnis/korrigieren} zurueckverlangt. <b>Ein dritter Aufruf nur fuer den
 * Ausgang eines Spiels waere die schlechtere Antwort</b> - das Dashboard zeigt Termin,
 * Teilnehmer, Teams und Ergebnis ohnehin zusammen.
 *
 * <h2>Der Hallenmodus kam mit S7 dazu</h2>
 * Zwei Felder auf einmal, und beide additiv: {@code halleAbgesagtAm} ist eine Spalte des
 * Termins, {@code halleAbsageMoeglichBis} eine Ableitung aus {@code datum}, {@code uhrzeit} und
 * der Konfiguration. <b>Ein Feld "darf ich jetzt noch absagen" gibt es bewusst nicht</b> - das
 * waere eine Berechtigungsaussage in einem rollenneutralen Antwortobjekt und erschiene bei
 * {@code USER} und {@code GAST} bedeutungslos mit. Der Adminbildschirm rechnet die Frage aus dem
 * Zeitpunkt selbst; durchgesetzt wird sie ohnehin serverseitig.
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
 * @param teams              die aktuelle Teameinteilung oder {@code null}, solange niemand
 *                           generiert hat. <b>{@code null} ist kein Fehler</b>, sondern der
 *                           Normalzustand - und die Antwort auf "gibt es schon eine
 *                           Einteilung", ohne die Wahl zwischen {@code 404} (klingt nach
 *                           Fehler) und {@code 204} (klingt nach "nichts zu holen")
 * @param ergebnis           der erfasste Ausgang oder {@code null}, solange niemand erfasst
 *                           hat - ebenfalls kein Fehler, sondern der Normalzustand jedes
 *                           Termins bis zum Abpfiff. <b>Unabhaengig von {@code teams}:</b>
 *                           Eine Einteilung ohne Ergebnis ist der Regelfall vor dem Spiel,
 *                           ein Ergebnis ohne Einteilung kann es nicht geben
 * @param halleAbgesagtAm    Zeitpunkt der versandten Absage an den Hallenbetreiber (A23) oder
 *                           {@code null}, solange nichts versandt wurde. Der Wert belegt den
 *                           <b>Versand</b>, nicht die Zustellung - eine Empfangsbestaetigung
 *                           gibt es nicht. Ist er gesetzt, gehoert der Absageknopf
 *                           ausgeblendet; ein zweiter Aufruf laeuft in
 *                           {@code 409 HALLE_BEREITS_ABGESAGT}
 * @param halleAbsageMoeglichBis spaetester Zeitpunkt, zu dem die Absage noch angenommen wird:
 *                           {@code datum + uhrzeit} minus {@code halleVorlaufStunden}.
 *                           <b>Immer gefuellt</b>, auch fuer vergangene und abgesagte Termine
 *                           und auch ohne hinterlegte Hallenadresse; liegt er in der
 *                           Vergangenheit, ist das Fenster zu. Ortszeit ohne Zone
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
                            Teilnehmerliste teilnehmerliste,
                            Teameinteilung teams,
                            Ergebnis ergebnis,
                            OffsetDateTime halleAbgesagtAm,
                            LocalDateTime halleAbsageMoeglichBis) {

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
                Teilnehmerliste.von(gelesen.teilnehmer()),
                gelesen.einteilung() == null ? null : Teameinteilung.von(gelesen.einteilung()),
                // Ergebnis ist hier der DTO aus diesem Paket, gelesen.ergebnis() die
                // gleichnamige Entity aus domain.spieltag - die Zuordnung macht das Paket.
                gelesen.ergebnis() == null ? null : Ergebnis.von(gelesen.ergebnis()),
                eintrag.halleAbgesagtAm(),
                gelesen.halleAbsageMoeglichBis());
    }
}
