package de.fubo.appserver.repository.spieltag;

import de.fubo.appserver.domain.spieltag.Ergebnis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Zugriff auf {@code spieltag.ergebnis} (A21, S6).
 *
 * <p>Spring Data setzt dieses Interface und {@link ErgebnisRepositoryCustom} zu einem Bean
 * zusammen; der Dienst kennt nur diesen Typ und sieht nicht, welcher Teil ueber JPA und
 * welcher ueber JDBC laeuft.
 *
 * <p><b>Warum beides:</b> Der Eintrag braucht die {@code ON CONFLICT}-Klausel, die JPA nicht
 * kennt. Die Korrektur braucht {@code @Version}, das natives SQL nicht bekommt - Hibernate
 * setzt es in die {@code WHERE}-Bedingung des {@code UPDATE} und erkennt daran den
 * Sperrkonflikt.
 */
public interface ErgebnisRepository extends JpaRepository<Ergebnis, Long>, ErgebnisRepositoryCustom {

    /**
     * Liefert das Ergebnis eines Termins.
     *
     * <p>Ueber die Entity und nicht nativ: Hier gibt es keinen Zaehler, der zwischendurch
     * nativ hochgezaehlt wuerde, und damit auch nicht den Grund, aus dem der Schreibpfad der
     * Teamgenerierung den Termin nativ liest.
     *
     * <p><b>{@link Optional#empty()} ist der Normalzustand</b> und kein Fehler: Solange
     * niemand erfasst hat, gibt es kein Ergebnis. Die Einzelansicht des Termins traegt dafuer
     * ein leeres Feld.
     *
     * <p>Eine zweite Zeile kann es nicht geben - {@code uq_ergebnis_termin} laesst sie nicht
     * zu. Deshalb {@code Optional} und keine Liste.
     *
     * @param terminId gesuchter Termin
     * @return das Ergebnis oder {@link Optional#empty()}
     */
    Optional<Ergebnis> findByTerminId(Long terminId);
}
