package de.fubo.appserver.domain.spieltag;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Der Zustand eines Termins, soweit die Hallenabsage ihn braucht (A23, S7 Abschnitte 3.2 bis 4.3).
 *
 * <h2>Warum nicht die {@link Termin}-Entity</h2>
 * Der Absagepfad erhoeht {@code termin.version} per SQL - zweimal sogar, wenn er den Termin mit
 * absagt. <b>Waere die Entity im selben Vorgang geladen, traege sie danach eine veraltete
 * Version im Speicher</b>, und der naechste Flush scheiterte an einem Sperrkonflikt, den niemand
 * verursacht hat. Dieselbe Ueberlegung wie bei {@link Terminzustand} im Generierungslauf und
 * beim Rueckmeldepfad aus S4.
 *
 * <p><b>Und es ist nicht dasselbe wie {@link Terminzustand}.</b> Der traegt {@code teamsFixiert}
 * und {@code teilnehmerVersion} und nichts davon, was in einer Nachricht steht; dieser hier
 * traegt Datum, Uhrzeit und Ort, weil sie in Betreff und Datenblock gehoeren, kennt dafuer den
 * Teilnehmerkreis nicht - die Hallenabsage geht ihn nichts an. Ein gemeinsamer Record mit sieben
 * Feldern bediente beide Vorgaenge halb.
 *
 * @param status          Zustand des Termins; {@code ABGESCHLOSSEN} wird abgelehnt, {@code GEPLANT}
 *                        wird im selben Vorgang auf {@code ABGESAGT} gesetzt (Entscheidung des
 *                        Haupt-Entwicklers vom 13.09.2026)
 * @param datum           Datum in Ortszeit; geht in Betreff und Datenblock
 * @param uhrzeit         Uhrzeit in Ortszeit; zusammen mit {@code datum} die Grundlage der
 *                        Fristberechnung
 * @param ort             Spielort oder {@code null}; fehlt er, entfaellt die Ortszeile der
 *                        Nachricht ganz - nicht "Ort: unbekannt"
 * @param halleAbgesagtAm Zeitpunkt einer bereits versandten Absage oder {@code null}.
 *                        <b>Nur fuer die Meldung:</b> Ob versendet werden darf, entscheidet der
 *                        bedingte {@code UPDATE} und nicht dieser Wert - zwischen Lesen und
 *                        Schreiben liegt sonst ein Fenster, in dem zwei Klicks beide durchkommen
 */
public record Hallentermin(TerminStatus status,
                           LocalDate datum,
                           LocalTime uhrzeit,
                           String ort,
                           OffsetDateTime halleAbgesagtAm) {
}
