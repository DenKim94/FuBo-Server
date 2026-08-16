package de.fubo.appserver.repository.profil;

import de.fubo.appserver.domain.profil.Spieler;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Zugriff auf {@code profil.spieler}.
 *
 * <p>Spring Data setzt dieses Interface und {@link SpielerRepositoryCustom} zu einem Bean
 * zusammen; der Service kennt nur diesen Typ und sieht nicht, welcher Teil ueber JPA und
 * welcher ueber JDBC laeuft.
 */
public interface SpielerRepository extends JpaRepository<Spieler, Long>, SpielerRepositoryCustom {
}
