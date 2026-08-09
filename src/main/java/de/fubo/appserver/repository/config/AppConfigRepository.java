package de.fubo.appserver.repository.config;

import de.fubo.appserver.domain.config.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** Zugriff auf die einzeilige Admin-Konfiguration {@code configs.app_config}. */
public interface AppConfigRepository extends JpaRepository<AppConfig, Short> {
}
