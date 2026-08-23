package de.fubo.appserver.service.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
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
 * <p>Das Admin-Konto legt der {@code AdminBootstrap} an; er laeuft ueber {@code @Order}
 * nach diesem Runner. Damit steht die Meldung zur zentralen PIN immer vor der zum
 * Admin-Konto im Log, und ein Startabbruch dort laesst die bereits angelegte PIN unberuehrt.
 */
@Component
@Order(PinBootstrap.REIHENFOLGE)
public class PinBootstrap implements ApplicationRunner {

    /** Laeuft vor {@code AdminBootstrap} (dort {@code 20}). */
    static final int REIHENFOLGE = 10;

    private static final Logger LOG = LoggerFactory.getLogger(PinBootstrap.class);

    /**
     * Stellenzahl der erzeugten Ersatz-PIN.
     *
     * <p><b>Vier, seit dem 23.08.2026</b> - vorher sechs. Grund ist der Endpunkt
     * {@code POST /admin/pin/aendern} aus S2b: Er laesst genau vier Ziffern zu. Eine
     * laengere Ersatz-PIN waere zwar staerker, liesse sich aber ueber ein Frontend, das auf
     * vier Stellen ausgelegt ist, gar nicht mehr eingeben - der Erststart endete in einer
     * Sackgasse.
     *
     * <p>10 000 Moeglichkeiten sind wenig. Tragfaehig wird das nur durch den
     * {@code BruteForceService}: fuenf Fehlversuche je Adresse, 30 insgesamt, steigende
     * Sperrdauern. Die Ersatz-PIN ist ausserdem als Uebergang gedacht und wird beim ersten
     * Anmelden gewechselt; die Startmeldung sagt das ausdruecklich.
     */
    private static final int ZUFALLS_PIN_STELLEN = 4;

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
