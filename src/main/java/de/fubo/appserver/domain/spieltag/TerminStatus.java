package de.fubo.appserver.domain.spieltag;

/**
 * Zustand eines Termins ({@code spieltag.termin.status}, S4 Abschnitt 3.4).
 *
 * <p>Die drei Werte sind in {@code V005} als CHECK-Constraint
 * ({@code ck_termin_status}) festgeschrieben. Ein vierter waere deshalb keine
 * Erweiterung dieses Aufzaehlungstyps, sondern eine Migration.
 *
 * <p><b>Der Uebergang ist eine Einbahnstrasse.</b> Aus {@link #GEPLANT} fuehrt ein Weg
 * heraus, aber keiner zurueck - Begruendung an {@link #ABGESAGT}.
 */
public enum TerminStatus {

    /**
     * Der Regelfall. Nur hier laesst sich der Termin aendern oder absagen, und nur hier
     * nimmt er ab S4-Paket 5 Rueckmeldungen an.
     */
    GEPLANT,

    /**
     * Der Termin faellt aus.
     *
     * <p><b>Endgueltig.</b> Ein abgesagter Termin laesst sich nicht wieder auf
     * {@link #GEPLANT} setzen: Die Zusagen dazwischen sind unbrauchbar geworden, weil
     * niemand weiss, wer von der Absage schon erfahren hat. Wer den Termin doch braucht,
     * legt ihn neu an - und {@code uq_termin_zeit} zwingt ihn dabei, den alten vorher zu
     * verschieben.
     *
     * <p>Die Zeile bleibt bestehen: Fuenf Tabellen haengen mit {@code ON DELETE CASCADE}
     * am Termin, und die Rueckmeldungen sind der einzige Beleg dafuer, wer zugesagt hatte.
     */
    ABGESAGT,

    /**
     * Der Termin ist gespielt und ausgewertet.
     *
     * <p><b>Heute setzt kein Endpunkt diesen Wert.</b> Er entsteht mit der
     * Ergebniserfassung in S6 (A21) und steht hier, weil der CHECK-Constraint aus
     * {@code V005} ihn bereits zulaesst - ein Aufzaehlungstyp, dem ein Wert der Spalte
     * fehlt, laeuft beim Lesen in eine {@code IllegalArgumentException}.
     */
    ABGESCHLOSSEN
}
