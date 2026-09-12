package de.fubo.appserver.repository.spieltag;

import java.util.Optional;

/**
 * Handgeschriebener Teil des {@link ErgebnisRepository} (A21, S6 Abschnitt 2.3).
 *
 * <p>Hier liegt genau eine Anweisung, und sie liegt hier aus genau einem Grund: <b>Der erste
 * Eintrag gilt</b>, und diese Entscheidung faellt in der Datenbank. Das braucht
 * {@code ON CONFLICT ... DO NOTHING RETURNING id} - eine Klausel, die JPA nicht kennt.
 *
 * <p>Alles Uebrige - Lesen und Korrigieren - laeuft ueber die Entity, weil die Korrektur
 * {@code @Version} braucht und natives SQL es nicht bekommt. Dieselbe Aufteilung wie bei
 * {@link TerminRepositoryCustom}.
 */
public interface ErgebnisRepositoryCustom {

    /**
     * Traegt das Ergebnis ein, sofern der Termin noch keines hat.
     *
     * <h2>Eine Anweisung, kein "erst pruefen, dann einfuegen"</h2>
     * Zwei Spieler, die nach dem Abpfiff gleichzeitig tippen, sind hier <b>der Normalfall</b>.
     * Eine vorgelagerte Pruefung liesse ein Fenster offen, in dem der Zweite denselben Termin
     * belegt; der {@code INSERT} braeche dann doch an {@code uq_ergebnis_termin} - mit genau
     * dem {@code 500}, den die Pruefung verhindern sollte.
     *
     * <h2>Die Bezeichnung entsteht in der Datenbank</h2>
     * {@code COALESCE} ueber den Profilnamen und den Gastnamen der Sitzung, wie bei
     * {@code team_generierung.erzeugt_von_*}. <b>Der Name wird kopiert, nicht verwiesen</b> -
     * ein spaeter entferntes Profil liesse die Auskunft sonst leer, obwohl der Eintrag
     * stattgefunden hat.
     *
     * @param terminId  betroffener Termin
     * @param sieger    {@code 'A'}, {@code 'B'} oder {@code 'U'}
     * @param deutlich  ob der Sieg deutlich ausfiel
     * @param spielerId Profil-Id des Erfassers oder {@code null} bei einem Gast
     * @param gastName  Gastname des Erfassers oder {@code null} bei einem Spieler
     * @return die Id der angelegten Zeile; <b>{@link Optional#empty()} heisst "liegt schon
     *         vor"</b> und ist kein technischer Fehler
     */
    Optional<Long> einfuegen(Long terminId, char sieger, boolean deutlich,
                             Long spielerId, String gastName);
}
