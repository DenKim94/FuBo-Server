package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.Zugangsdaten;
import org.springframework.data.jpa.repository.JpaRepository;

/** Zugriff auf die einzeilige Tabelle {@code profil.zugangsdaten} (zentrale PIN, A3). */
public interface ZugangsdatenRepository extends JpaRepository<Zugangsdaten, Short> {
}
