package de.fubo.appserver.repository.auth;

import de.fubo.appserver.domain.auth.AdminKonto;
import org.springframework.data.jpa.repository.JpaRepository;

/** Zugriff auf die einzeilige Tabelle {@code profil.admin_konto} (A22). */
public interface AdminKontoRepository extends JpaRepository<AdminKonto, Short> {
}
