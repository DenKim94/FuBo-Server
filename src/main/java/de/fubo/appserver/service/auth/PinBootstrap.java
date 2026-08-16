package de.fubo.appserver.service.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Legt beim Start die zentrale PIN an, falls {@code profil.zugangsdaten} noch leer ist
 * (Abschnitt 9 der Umsetzungsanleitung, Teil "zentrale PIN").
 *
 * <p><b>Warum nicht als Flyway-Migration?</b> Ein BCrypt-Hash in einer Migration waere ein
 * Geheimnis in der Git-Historie, und Migrationen sind unveraenderlich - der Hash liesse
 * sich nachtraeglich nicht mehr entfernen.
 *
 * <p>Der Runner laeuft, nachdem der Kontext vollstaendig hochgefahren ist; Flyway ist zu
 * diesem Zeitpunkt sicher durch. Er ist idempotent: Existiert die Zeile bereits, passiert
 * nichts.
 *
 * <p><b>Noch offen (Abschnitt 9):</b> Das Admin-Konto wird hier nicht angelegt. Dafuer
 * braucht es die Auswahl ueber {@code ADMIN_NAME}/{@code ADMIN_EMAIL} aus der {@code .env}
 * samt Startabbruch, wenn kein passendes Profil existiert.
 */
@Component
public class PinBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PinBootstrap.class);

    /** Stellenzahl der erzeugten Ersatz-PIN. */
    private static final int ZUFALLS_PIN_STELLEN = 6;

    private static final SecureRandom ZUFALL = new SecureRandom();

    private final PinService pinService;

    /**
     * Aus der {@code .env} ueber {@code spring.config.import} oder aus einer echten
     * Umgebungsvariablen. Fehlt der Wert, wird eine Zufalls-PIN erzeugt.
     */
    private final String initialePin;

    public PinBootstrap(PinService pinService, @Value("${FUBO_INITIAL_PIN:}") String initialePin) {
        this.pinService = pinService;
        this.initialePin = initialePin;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (pinService.istHinterlegt()) {
            LOG.debug("Zentrale PIN ist bereits hinterlegt - Bootstrap uebersprungen.");
            return;
        }

        boolean ausKonfiguration = initialePin != null && !initialePin.isBlank();
        String pin = ausKonfiguration ? initialePin.trim() : zufallsPin();

        pinService.setzen(pin, null);

        if (ausKonfiguration) {
            // Der Betreiber kennt den Wert bereits - er gehoert nicht zusaetzlich ins Log.
            LOG.warn("Zentrale PIN aus FUBO_INITIAL_PIN uebernommen. Bitte nach dem ersten "
                    + "Anmelden aendern und die Variable aus der Umgebung entfernen.");
        } else {
            // Einziger Moment, in dem die PIN im Klartext existiert.
            LOG.warn("Keine FUBO_INITIAL_PIN gesetzt. Zentrale PIN erzeugt: {} "
                    + "- bitte notieren und umgehend aendern.", pin);
        }
    }

    /** Erzeugt eine rein numerische PIN aus einer kryptografisch sicheren Quelle. */
    private static String zufallsPin() {
        StringBuilder pin = new StringBuilder(ZUFALLS_PIN_STELLEN);
        for (int stelle = 0; stelle < ZUFALLS_PIN_STELLEN; stelle++) {
            pin.append(ZUFALL.nextInt(10));
        }
        return pin.toString();
    }
}
