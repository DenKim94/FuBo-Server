package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.profil.Bilanzstand;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Die Bilanz-Zaehler in {@code profil.spieler} (A21 in der Ergaenzung vom 30.08.2026,
 * {@code V011}; S6 Abschnitt 4).
 *
 * <h2>Ein eigenes Repository ohne Entity</h2>
 * Geschrieben wird mit <b>einer</b> Anweisung ueber alle Betroffenen; gelesen wird ein
 * Wertobjekt. Eine Entity braeuchte es dafuer nicht - und sie waere hier sogar schaedlich:
 * Die {@link de.fubo.appserver.domain.profil.Spieler}-Entity bildet die drei Spalten bereits
 * ab, und ein zweiter schreibender Weg ueber dieselben Felder machte es zur Glueckssache,
 * welcher zuletzt gewinnt. Derselbe Grund wie bei {@code GastSlotRepository}.
 *
 * <p>Das Repository liegt in {@code repository.profil} und nicht in {@code repository.spieltag},
 * obwohl seine Abfrage vier Spieltagstabellen liest: <b>Geschrieben wird {@code profil.spieler}</b>,
 * und die Ablage richtet sich nach dem Ziel, nicht nach der Herkunft der Zahlen.
 */
@Repository
public class BilanzRepository {

    /**
     * Berechnet die Bilanz aller an einem Termin Beteiligten neu (S6 Abschnitt 4.2).
     *
     * <h2>Innen eingrenzen, aussen zaehlen</h2>
     * Die <b>innere</b> Unterabfrage bestimmt die Betroffenen: alle Spieler der aktuellen
     * Einteilung dieses einen Termins. Die <b>aeussere</b> zaehlt fuer sie ueber <i>alle</i>
     * ihre Termine. Wer nur ueber den einen Termin zaehlte, schriebe jedem Spieler die Bilanz
     * <i>dieses Spiels</i> - und loeschte damit seine gesamte Historie. Das ist der Fehler,
     * den ein einzelner Testfall mit zwei Terminen aufdeckt und jeder andere durchgehen laesst.
     *
     * <p><b>Jeder Betroffene kommt in {@code b} vor</b>, weil er an mindestens diesem einen
     * Ergebnis beteiligt ist. Eine Zeile, die aus {@code b} herausfiele, bliebe unveraendert
     * stehen - hier kann das nicht passieren, und der Grund gehoert genau deshalb hierhin.
     *
     * <p><b>Massgeblich ist die Einteilung mit {@code abgeloest_am IS NULL}</b>, auf beiden
     * Ebenen. Ein abgeloester Lauf beschreibt einen Teilnehmerkreis, der so nicht gespielt hat.
     *
     * <p><b>{@code tn.spieler_id IS NOT NULL} steht ausdruecklich da</b> und nicht nur implizit
     * ueber den Join: Es ist der Ausschluss der Gaeste, und der soll beim Lesen auffallen.
     * Wartende stehen in keiner Einteilung, das Adminprofil kann nicht zusagen - beide findet
     * der Join ohnehin nicht.
     *
     * <p><b>{@code deutlich} kommt nicht vor.</b> Es beschreibt die Hoehe, nicht den Ausgang.
     * Der Auswechselspieler dagegen zaehlt mit: Er steht in {@code team_zuteilung} wie alle
     * anderen, und er hat gespielt.
     *
     * <p><b>{@code FILTER} statt {@code sum(CASE ...)}</b> - Standard-SQL, und drei Zeilen, die
     * man nebeneinander lesen kann.
     *
     * <h2>Warum {@code version} mitzaehlt</h2>
     * Das ist hier keine Formalie, sondern der Riegel gegen einen stillen Datenverlust: Die
     * {@code Spieler}-Entity bildet die drei Zaehler ab, und Hibernate schreibt beim Flush alle
     * Spalten. Laedt ein anderer Vorgang - {@code /admin/user/bearbeiten} - dasselbe Profil und
     * flusht es nach der Neuberechnung, schriebe er die <b>alte</b> Bilanz aus seinem
     * Schnappschuss zurueck und machte die Neuberechnung rueckgaengig; ohne Fehlermeldung, ohne
     * Spur. Mit erhoehter {@code version} scheitert dieser Flush stattdessen laut an einem
     * Sperrkonflikt und wird als {@code 409 DATEN_VERALTET} beantwortet. <b>Das ist der
     * gewuenschte Ausgang</b> - die Zeile <i>hat</i> sich geaendert.
     *
     * <p><b>{@code geaendert_am} bleibt unberuehrt.</b> Es ist die Spur einer Stammdatenpflege;
     * die Bilanz ist keine.
     */
    private static final String SQL_NEU_BERECHNEN = """
            UPDATE profil.spieler s
               SET anz_siege         = b.siege,
                   anz_niederlagen   = b.niederlagen,
                   anz_unentschieden = b.unentschieden,
                   version           = s.version + 1
              FROM (
                    SELECT tn.spieler_id,
                           count(*) FILTER (WHERE e.sieger <> 'U' AND e.sieger =  tz.team) AS siege,
                           count(*) FILTER (WHERE e.sieger <> 'U' AND e.sieger <> tz.team) AS niederlagen,
                           count(*) FILTER (WHERE e.sieger =  'U')                         AS unentschieden
                      FROM spieltag.ergebnis e
                      JOIN spieltag.team_generierung tg ON tg.termin_id = e.termin_id
                                                       AND tg.abgeloest_am IS NULL
                      JOIN spieltag.team_zuteilung  tz ON tz.generierung_id = tg.id
                      JOIN spieltag.teilnahme       tn ON tn.id = tz.teilnahme_id
                     WHERE tn.spieler_id IS NOT NULL
                       AND tn.spieler_id IN (SELECT tn2.spieler_id
                                               FROM spieltag.team_generierung tg2
                                               JOIN spieltag.team_zuteilung  tz2 ON tz2.generierung_id = tg2.id
                                               JOIN spieltag.teilnahme       tn2 ON tn2.id = tz2.teilnahme_id
                                              WHERE tg2.termin_id = :terminId
                                                AND tg2.abgeloest_am IS NULL
                                                AND tn2.spieler_id IS NOT NULL)
                     GROUP BY tn.spieler_id
                   ) b
             WHERE s.id = b.spieler_id
            """;

    /**
     * Die Bilanz eines einzelnen Profils.
     *
     * <p>Gelesen wird die gespeicherte Zeile und <b>nicht</b> erneut gerechnet: Die Zaehler
     * sind die Projektion, und sie stehen in derselben Transaktion bereits richtig. Eine
     * zweite Rechnung an dieser Stelle koennte gar nicht abweichen - sie waere nur teurer und
     * eine zweite Stelle, an der die Zaehlweise steht.
     *
     * <p>Ohne Einschraenkung auf {@code rolle} oder {@code aktiv}: Die Id kommt aus der
     * Sitzung, nicht aus einem Anfragekoerper; wer angemeldet ist, darf seine eigene Bilanz
     * sehen. Ein gesperrtes Profil hat ohnehin keine gueltige Sitzung mehr.
     */
    private static final String SQL_LESEN = """
            SELECT anz_siege, anz_niederlagen, anz_unentschieden
              FROM profil.spieler
             WHERE id = :spielerId
            """;

    private final JdbcClient jdbc;

    public BilanzRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Berechnet die Bilanz aller Beteiligten dieses Termins neu.
     *
     * <p>Aufzurufen auf <b>beiden</b> Pfaden - beim Erfassen und beim Korrigieren -, jeweils
     * in derselben Transaktion wie die Aenderung an {@code spieltag.ergebnis}. Sonst gaebe es
     * einen Moment, in dem das Ergebnis steht und die Bilanz es noch nicht kennt; und wenn der
     * zweite Schritt scheitert, dauerhaft.
     *
     * @param terminId Termin, dessen Einteilung die Betroffenen bestimmt
     * @return Zahl der geaenderten Profile; sie ist die Zahl der eingeteilten <b>Spieler</b>
     *         ohne Gaeste und geht als Beleg in das Audit-Log
     */
    public int neuBerechnen(Long terminId) {
        return jdbc.sql(SQL_NEU_BERECHNEN).param("terminId", terminId).update();
    }

    /**
     * Liest die gespeicherte Bilanz eines Profils.
     *
     * @param spielerId betroffenes Profil
     * @return die Bilanz oder {@link Optional#empty()}, wenn es das Profil nicht (mehr) gibt
     */
    public Optional<Bilanzstand> lesen(Long spielerId) {
        return jdbc.sql(SQL_LESEN)
                .param("spielerId", spielerId)
                .query((rs, zeile) -> new Bilanzstand(
                        rs.getInt("anz_siege"),
                        rs.getInt("anz_niederlagen"),
                        rs.getInt("anz_unentschieden")))
                .optional();
    }
}
