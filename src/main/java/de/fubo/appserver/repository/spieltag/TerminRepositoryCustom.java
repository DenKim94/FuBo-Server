package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.spieltag.TerminEintrag;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * Erhoeht {@code teilnehmer_version} eines Termins um eins (A15).
     *
     * <p>Der Zaehler steigt bei <b>jeder</b> Teilnehmeraenderung und ist ab S5 der einzige
     * Ausloeser fuer das Zuruecksetzen des Generierungskontingents und das
     * Veraltet-Kennzeichen einer Teameinteilung. <b>Eine Luecke laesst sich spaeter nicht
     * mehr rekonstruieren</b> - deshalb wird er schon in S4 gefuehrt, obwohl ihn hier noch
     * niemand auswertet.
     *
     * @param terminId betroffener Termin
     * @return Anzahl geaenderter Zeilen; {@code 0}, wenn es die Id nicht gibt
     */
    int teilnehmerVersionErhoehen(Long terminId);

    /**
     * Erhoeht {@code teilnehmer_version} aller <b>kuenftigen</b> Termine, an denen ein
     * Spieler zugesagt hat (A15, Nachtrag aus S3, Abschnitt 3.5).
     *
     * <p>Eine Skillaenderung ist eine Teilnehmeraenderung: Sie aendert nicht, <i>wer</i>
     * mitspielt, wohl aber die Grundlage jeder Teameinteilung. In S3 war der Vorgang mangels
     * {@code spieltag}-Dienst nicht umsetzbar und stand dort als vorbereiteter Kommentar.
     *
     * <p><b>Nur kuenftige Termine.</b> Eine Skillaenderung macht die Einteilung eines
     * vergangenen Spiels nicht ungueltig - es ist gespielt worden.
     *
     * @param spielerId Profil, dessen Skillwerte sich geaendert haben
     * @param jetzt     Vergleichszeitpunkt aus der {@code Clock}-Bean
     * @return Anzahl betroffener Termine
     */
    int teilnehmerVersionErhoehenFuerSpieler(Long spielerId, LocalDateTime jetzt);

    /**
     * Setzt alle geplanten Termine auf {@code ABGESCHLOSSEN}, deren Beginn lange genug
     * zurueckliegt (A18, Ergaenzung vom 30.08.2026).
     *
     * @param grenze spaetester Beginn, der noch abgeschlossen wird - also
     *               "jetzt minus 30 Minuten"
     * @return Anzahl abgeschlossener Termine
     */
    int abgelaufeneAbschliessen(LocalDateTime grenze);

    /**
     * Meldet, ob fachliche Daten auf den Termin verweisen (A19).
     *
     * <p>Geprueft werden {@code teilnahme}, {@code team_generierung},
     * {@code generierung_kontingent} und {@code ergebnis}. {@code team_zuteilung} haengt an
     * der Generierung und ist damit mitgeprueft.
     *
     * @param terminId zu pruefender Termin
     * @return {@code true}, wenn mindestens ein Datensatz auf ihn zeigt
     */
    boolean istReferenziert(Long terminId);
}
