package de.fubo.appserver.service.config;

import de.fubo.appserver.domain.config.AppConfig;
import de.fubo.appserver.repository.config.AppConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liest die einzeilige Admin-Konfiguration aus {@code configs.app_config}. */
@Service
public class ConfigService {

    /** Die Tabelle ist per CHECK-Constraint auf genau diese Zeile beschraenkt. */
    private static final short KONFIG_ID = 1;

    private final AppConfigRepository appConfigRepository;

    public ConfigService(AppConfigRepository appConfigRepository) {
        this.appConfigRepository = appConfigRepository;
    }

    /**
     * Liefert die aktuelle Konfiguration.
     *
     * <p>Bewusst ohne Zwischenspeicher: Der Aufruf ist ein Primaerschluessel-Zugriff auf
     * eine einzeilige Tabelle, die dauerhaft im Puffer der Datenbank liegt. Ein Cache
     * braeuchte eine Invalidierung, sobald der Admin Werte aendert (S3) - zusaetzlicher
     * Zustand fuer einen Gewinn, den erst eine Messung rechtfertigen wuerde. Innerhalb
     * eines Requests laeuft der Zugriff in derselben Transaktion wie die Sitzungspruefung.
     *
     * @throws IllegalStateException wenn die Seed-Zeile fehlt - das waere ein defekter
     *                               Migrationsstand und kein fachlicher Fehlerfall
     */
    @Transactional(readOnly = true)
    public AppConfig lesen() {
        return appConfigRepository.findById(KONFIG_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "configs.app_config enthaelt keine Zeile mit id = 1 - Migrationsstand pruefen."));
    }

    // TODO: Konfigurationen aktualisieren
}
