package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.spieltag.Termin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Zugriff auf {@code spieltag.termin} (S4).
 *
 * <p>Spring Data setzt dieses Interface und {@link TerminRepositoryCustom} zu einem Bean
 * zusammen; der Dienst kennt nur diesen Typ und sieht nicht, welcher Teil ueber JPA und
 * welcher ueber JDBC laeuft.
 *
 * <p><b>Warum beides:</b> Das Aendern und das Absagen brauchen die Entity, weil
 * {@code @Version} das Optimistic Locking traegt (A5). Das Anlegen braucht die
 * {@code ON CONFLICT}-Klausel, die JPA nicht kennt, und die Uebersicht eine Aggregation
 * ueber zwei Tabellen.
 */
public interface TerminRepository extends JpaRepository<Termin, Long>, TerminRepositoryCustom {

    /**
     * Meldet, ob ein <b>anderer</b> Termin bereits auf diesem Zeitpunkt liegt.
     *
     * <p>Gebraucht beim Aendern: Dort laesst sich der Konflikt nicht ueber
     * {@code ON CONFLICT} abfangen, weil ein {@code UPDATE} auf einer bestehenden Zeile
     * arbeitet - der Constraint braechte dann eine
     * {@code DataIntegrityViolationException} und daraus einen {@code 500}. Die Pruefung
     * liefert stattdessen {@code 409 TERMIN_BELEGT}; {@code uq_termin_zeit} bleibt als
     * letzte Instanz bestehen.
     *
     * <p><b>{@code IdNot} ist nicht optional.</b> Ohne diesen Teil meldete jede Aenderung,
     * die Datum und Uhrzeit unveraendert laesst, einen Konflikt mit dem Termin, der gerade
     * geaendert werden soll - eine reine Ortsaenderung waere unmoeglich.
     *
     * @param datum    zu pruefendes Datum
     * @param uhrzeit  zu pruefende Uhrzeit
     * @param terminId Termin, der dabei ausgenommen bleibt
     * @return {@code true}, wenn der Zeitpunkt von einem anderen Termin belegt ist
     */
    boolean existsByDatumAndUhrzeitAndIdNot(LocalDate datum, LocalTime uhrzeit, Long terminId);
}
