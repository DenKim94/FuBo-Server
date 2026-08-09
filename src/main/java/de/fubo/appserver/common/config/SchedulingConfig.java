package de.fubo.appserver.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aktiviert die Auswertung von {@code @Scheduled}-Methoden.
 *
 * <p>Ohne diese Klasse laeuft der Aufraeumjob fuer abgelaufene Sitzungen
 * ({@code SessionService#alteSitzungenEntfernen}) nie - und zwar ohne Fehlermeldung.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
