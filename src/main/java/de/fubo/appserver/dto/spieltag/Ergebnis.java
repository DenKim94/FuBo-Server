package de.fubo.appserver.dto.spieltag;

import de.fubo.appserver.domain.spieltag.Sieger;

import java.time.OffsetDateTime;

/**
 * Das Ergebnis eines Termins nach aussen (A9, A21; S6 Abschnitt 5.3).
 *
 * <h2>Es erreicht jede Rolle, auch {@code GAST}</h2>
 * Deshalb <b>keine Skillwerte und keine Ids von Personen</b>: {@link #erfasstVon} ist ein
 * Anzeigename und keine Profil-Id. Wer die Id braucht, ist der Admin, und der hat das
 * Audit-Log. Dieselbe Zurueckhaltung wie bei der Teilnehmerliste (A12).
 *
 * <h2>Warum schon das Erfassen diesen Koerper zurueckgibt</h2>
 * {@code 201} mit dem Ergebnis und nicht {@code 204}. <b>Grund ist die {@link #version}:</b>
 * Ohne sie muesste der Client den Termin sofort danach noch einmal lesen, nur um korrigieren
 * zu koennen. Dazu kommt {@link #erfasstVon} - wer zuerst war, ist genau die Auskunft, die
 * der Zweitschnellste braucht.
 *
 * <p>Derselbe Record erscheint als nullbares Feld {@code ergebnis} in
 * {@code TerminDetails}; {@code null} heisst dort "noch nicht erfasst" und ist kein Fehler.
 *
 * <p><b>Zum gleichlautenden Namen:</b> Es gibt eine Entity
 * {@code domain.spieltag.Ergebnis} mit demselben einfachen Namen. Das ist Absicht und kein
 * Versehen - der Vertrag fuehrt das Schema als {@code Ergebnis}, und die Entity heisst nach
 * ihrer Tabelle. Beide treffen sich ausschliesslich in {@link #von}, wo der Quelltyp
 * deshalb voll qualifiziert steht; jede andere Klasse importiert genau einen von beiden und
 * kann sie nicht verwechseln.
 *
 * @param sieger       Ausgang des Spiels
 * @param deutlich     ob der Sieg deutlich ausfiel; bei einem Unentschieden immer
 *                     {@code false}
 * @param erfasstVon   Anzeigename des Erfassers <b>zum Zeitpunkt der Erfassung</b>; er wurde
 *                     kopiert und aendert sich nicht mehr, auch nicht bei einer Umbenennung
 * @param erfasstAm    Zeitpunkt des ersten Eintrags
 * @param korrigiertAm Zeitpunkt der letzten Korrektur oder {@code null}; der unkorrigierte
 *                     Zustand ist {@code null}, kein Datum
 * @param version      Stand fuer {@code /admin/ergebnis/korrigieren}
 */
public record Ergebnis(Sieger sieger,
                       boolean deutlich,
                       String erfasstVon,
                       OffsetDateTime erfasstAm,
                       OffsetDateTime korrigiertAm,
                       Long version) {

    /**
     * Bildet die Entity auf den Vertrag ab.
     *
     * <p>Die Abbildung steht hier und nicht im Dienst: Sie gehoert zum DTO, und der Dienst
     * soll nicht wissen muessen, wie der Vertrag aussieht - dieselbe Aufteilung wie bei
     * {@code SpielerDetails#von}.
     *
     * <p><b>Sie ist zugleich die Stelle, an der die Entity endet.</b> Was hier nicht
     * uebernommen wird, verlaesst den Server nicht: die Id der Zeile und
     * {@code erfasst_von_spieler_id}.
     *
     * @param quelle die gespeicherte Zeile
     * @return das Antwortobjekt
     */
    public static Ergebnis von(de.fubo.appserver.domain.spieltag.Ergebnis quelle) {
        return new Ergebnis(
                quelle.getSieger(),
                quelle.isDeutlich(),
                quelle.getErfasstVonBezeichnung(),
                quelle.getErfasstAm(),
                quelle.getKorrigiertAm(),
                quelle.getVersion());
    }
}
