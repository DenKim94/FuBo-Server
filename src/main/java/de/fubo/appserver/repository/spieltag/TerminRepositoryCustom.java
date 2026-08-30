package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.spieltag.TerminEintrag;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Handgeschriebener Teil des {@link TerminRepository} (S4, Abschnitte 2 bis 4).
 *
 * <p>Die beiden Leseabfragen verbinden {@code spieltag.termin} mit einer Aggregation ueber
 * {@code spieltag.teilnahme}. Ueber abgeleitete Methodennamen laesst sich das nicht
 * ausdruecken, und eine JPQL-Fassung braeuchte eine Assoziation zwischen den Entities - die
 * es hier bewusst nicht gibt.
 *
 * <p>Das Einfuegen laeuft ebenfalls hier, weil es eine {@code ON CONFLICT}-Klausel braucht:
 * Sie entscheidet den Wettlauf um einen Zeitpunkt in einer einzigen Anweisung, waehrend
 * "erst pruefen, dann schreiben" ein Fenster dazwischen liesse.
 */
public interface TerminRepositoryCustom {

    /**
     * Liefert alle Termine ab einem Stichtag samt Zusagenzahl und eigener Rueckmeldung.
     *
     * <p>Sortiert nach Datum und Uhrzeit - das ist zugleich die Reihenfolge, in der das
     * Dashboard sie anzeigt (A9).
     *
     * @param ab        Stichtag einschliesslich
     * @param spielerId Profil-Id des Aufrufers oder {@code null} bei einer Gastsitzung
     * @param gastName  Gastname des Aufrufers oder {@code null} bei einer Spielersitzung
     * @return Termine ab dem Stichtag; leere Liste, wenn keiner passt
     */
    List<TerminEintrag> findeUebersicht(LocalDate ab, Long spielerId, String gastName);

    /**
     * Liefert einen einzelnen Termin in derselben Form wie die Uebersicht.
     *
     * @param terminId  gesuchter Termin
     * @param spielerId Profil-Id des Aufrufers oder {@code null} bei einer Gastsitzung
     * @param gastName  Gastname des Aufrufers oder {@code null} bei einer Spielersitzung
     * @return der Termin oder {@link Optional#empty()}, wenn es ihn nicht gibt
     */
    Optional<TerminEintrag> findeEintrag(Long terminId, Long spielerId, String gastName);

    /**
     * Legt einen Termin an, sofern der Zeitpunkt noch frei ist.
     *
     * <p><b>Eine Anweisung statt zweier.</b> Die Anleitung schlaegt vor, vorher zu pruefen
     * und den Constraint zusaetzlich zu behalten; das erreicht dasselbe Ziel - eine
     * verstaendliche Meldung statt eines {@code 500} -, laesst zwischen Pruefung und
     * Einfuegen aber ein Fenster offen, in dem eine zweite Anfrage denselben Zeitpunkt
     * belegt. Dann braeche der {@code INSERT} doch am Constraint, und der Aufrufer bekaeme
     * genau den {@code 500}, den die Pruefung verhindern sollte.
     * {@code ON CONFLICT ... DO NOTHING} schliesst das Fenster: Es gibt keinen zweiten
     * Zeitpunkt, an dem sich der Bestand aendern koennte.
     *
     * <p>Fuer die Serie ist derselbe Rueckgabewert die Antwort auf Weggabelung A: Ein leeres
     * {@link Optional} heisst "uebersprungen" und nicht "gescheitert".
     *
     * @param serieId Serie oder {@code null} fuer einen Einzeltermin
     * @param datum   Datum in Ortszeit
     * @param uhrzeit Uhrzeit in Ortszeit
     * @param ort     Spielort oder {@code null}
     * @return Id des angelegten Termins oder {@link Optional#empty()}, wenn der Zeitpunkt
     *         bereits belegt war
     */
    Optional<Long> einfuegenWennFrei(Long serieId, LocalDate datum, LocalTime uhrzeit, String ort);
}
